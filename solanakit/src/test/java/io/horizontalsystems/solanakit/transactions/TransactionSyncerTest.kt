package io.horizontalsystems.solanakit.transactions

import SplTokenAccountWithPublicKey
import com.solana.api.Api
import com.solana.api.Meta
import com.solana.core.PublicKey
import com.solana.networking.NetworkingRouter
import com.solana.networking.RpcError
import com.solana.networking.RpcRequest
import com.solana.networking.RpcResponse
import com.solana.networking.decodeRpcResponse
import io.horizontalsystems.solanakit.SolanaKit
import io.horizontalsystems.solanakit.database.transaction.TransactionStorage
import io.horizontalsystems.solanakit.database.transaction.dao.TransactionsDao.TransactionKey
import io.horizontalsystems.solanakit.models.FullTransaction
import io.horizontalsystems.solanakit.models.LastSyncedTransaction
import io.horizontalsystems.solanakit.network.RpcExecutor
import io.horizontalsystems.solanakit.network.RpcOutcome
import io.horizontalsystems.solanakit.noderpc.endpoints.RpcSignatureInformation
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.sol4k.Base58
import java.io.IOException

class TransactionSyncerTest {

    private class ChainTx(val signature: String, val slot: Long, val streams: Set<String>, val position: Int, val blockTime: Long?)

    private val storage = mockk<TransactionStorage>(relaxed = true)
    private val pendingTransactionSyncer = mockk<PendingTransactionSyncer>(relaxed = true)
    private val transactionManager = mockk<TransactionManager>(relaxed = true)
    private val router = mockk<NetworkingRouter>()
    private val rpc = mockk<RpcExecutor>()
    private val fetcher = TransactionFetcher(rpc)

    private val chain = mutableListOf<ChainTx>()
    private val cursors = mutableMapOf<String, String>()
    private val storedRows = mutableSetOf<String>()
    private val handled = mutableListOf<FullTransaction>()
    private val replaced = mutableListOf<Set<String>>()
    private val notified = mutableListOf<List<String>>()
    private val transactionRequests = mutableListOf<String>()
    private val transactionResponses = mutableMapOf<String, () -> RpcOutcome<TransactionResult>>()
    private var listingRequests = 0
    private var tokenAccounts = emptyList<String>()
    private var discoveryFails = false
    private var listingFails = false
    private var hiddenAfterFirstListing = emptySet<String>()
    private var allTransactionsFail = false
    private var mintAccountsResponse: suspend (Int) -> RpcResponse<Any?> = { count -> mintAccounts(count) }

    private var syncer = newSyncer()

    init {
        coEvery { storage.tokenTransferRepairCursor() } returns null
        coEvery { storage.tokenTransferTransactionHashes(any(), any(), any()) } returns emptyList()
        coEvery { storage.getSyncedBlockTime(any()) } answers { cursors[firstArg()]?.let { LastSyncedTransaction(firstArg(), it) } }
        coEvery { storage.setSyncedBlockTime(any()) } answers {
            val row = firstArg<LastSyncedTransaction>()
            cursors[row.syncSourceName] = row.hash
        }
        coEvery { storage.syncSourceNames(any()) } answers { cursors.keys.filter { it.startsWith(firstArg<String>()) } }
        coEvery { storage.storedHashes(any()) } answers { firstArg<List<String>>().filterTo(HashSet()) { it in storedRows } }
        coEvery { transactionManager.store(any(), any()) } answers { record(firstArg(), secondArg()) }
        coEvery { transactionManager.handle(any(), any()) } answers {
            transactionManager.notifyTransactionsUpdate(record(firstArg(), secondArg()))
        }
        every { transactionManager.notifyTransactionsUpdate(any()) } answers {
            firstArg<List<FullTransaction>>().takeIf { it.isNotEmpty() }?.let { notified += it.map { tx -> tx.transaction.hash } }
        }
        coEvery { router.makeRequest<Any?>(any(), any()) } coAnswers { respond(firstArg()) }
        every { rpc.isKeyedAvailable() } returns false
        coEvery { rpc.execute<Any?>(any(), any(), any()) } answers { transactionOutcome(signatureOf(firstArg())) }
    }

    @Test
    fun sync_pendingSyncCancelled_leavesSyncingState() = runBlocking {
        coEvery { pendingTransactionSyncer.sync() } throws CancellationException()

        syncIgnoringCancellation()

        assertFalse(syncer.syncState.value is SolanaKit.SyncState.Syncing)
    }

    @Test
    fun sync_storageReadCancelled_leavesSyncingState() = runBlocking {
        coEvery { storage.getSyncedBlockTime(any()) } throws CancellationException()

        syncIgnoringCancellation()

        assertFalse(syncer.syncState.value is SolanaKit.SyncState.Syncing)
    }

    @Test
    fun sync_firstPass_seedsForwardCursorPerStreamFromStoredBackwardListing() = runBlocking {
        tokenAccounts = listOf(TOKEN_A, TOKEN_B)
        onChain("w1", slot = 1)
        onChain("a1", slot = 2, TOKEN_A)
        onChain("w2", slot = 3)

        syncer.sync()

        assertEquals("w2", forwardCursor(USER))
        assertEquals("a1", forwardCursor(TOKEN_A))
        assertNull(forwardCursor(TOKEN_B))
        assertEquals("done", backwardCursor())
    }

    @Test
    fun sync_firstPassNothingStored_setsNoForwardCursor() = runBlocking {
        tokenAccounts = listOf(TOKEN_A)
        onChain("w1", slot = 1)
        onChain("a1", slot = 2, TOKEN_A)
        allTransactionsFail = true

        syncer.sync()

        assertNull(forwardCursor(USER))
        assertNull(forwardCursor(TOKEN_A))
        assertTrue(backwardCursor() in setOf(null, "top"))
        assertTrue(syncer.syncState.value is SolanaKit.SyncState.Synced)
    }

    @Test
    fun sync_laterListingFromLaggingNode_newestTransactionStoredOnceNodeCatchesUp() = runBlocking {
        onChain("y", slot = 1)
        onChain("x", slot = 2)
        hiddenAfterFirstListing = setOf("x")

        syncer.sync()
        hiddenAfterFirstListing = emptySet()
        syncer.sync()

        assertTrue("x" in storedRows)
        assertTrue("y" in storedRows)
    }

    @Test
    fun sync_forwardTransientFailure_storesLeadingGroupsAndResumesNextPass() = runBlocking {
        syncedWithOldTransaction()
        onChain("n1", slot = 5)
        onChain("n2", slot = 6)
        onChain("n3", slot = 7)
        transactionResponses["n2"] = { RpcOutcome.TransportFailure(IOException("timeout")) }

        syncer.sync()

        assertEquals(setOf("old", "n1"), storedRows)
        assertEquals("n1", forwardCursor(USER))

        transactionResponses.clear()
        syncer.sync()

        assertEquals(setOf("old", "n1", "n2", "n3"), storedRows)
        assertEquals("n3", forwardCursor(USER))
    }

    @Test
    fun sync_listingFails_cursorsUnchangedAndNotSynced() = runBlocking {
        syncedWithOldTransaction()
        onChain("n1", slot = 5)
        val cursorsBefore = cursors.toMap()
        listingFails = true

        syncer.sync()

        assertEquals(cursorsBefore, cursors)
        assertFalse("n1" in transactionRequests)
        assertTrue(syncer.syncState.value is SolanaKit.SyncState.NotSynced)
    }

    @Test
    fun sync_discoveryFails_passAbortedAndCursorsUnchanged() = runBlocking {
        syncedWithOldTransaction()
        onChain("n1", slot = 5)
        val cursorsBefore = cursors.toMap()
        val listingsBefore = listingRequests
        discoveryFails = true

        syncer.sync()

        assertEquals(cursorsBefore, cursors)
        assertEquals(listingsBefore, listingRequests)
        assertFalse("n1" in storedRows)
        assertTrue(syncer.syncState.value is SolanaKit.SyncState.NotSynced)
    }

    @Test
    fun sync_slotGroupPartlyFetched_storesNothingOfThatSlotUntilComplete() = runBlocking {
        syncedWithOldTransaction()
        onChain("a", slot = 5)
        onChain("b", slot = 5)
        transactionResponses["a"] = { RpcOutcome.TransportFailure(IOException("timeout")) }

        syncer.sync()

        assertEquals(listOf("b", "a"), transactionRequests)
        assertFalse("b" in storedRows)
        assertEquals("old", forwardCursor(USER))

        transactionResponses.clear()
        syncer.sync()

        assertEquals(setOf("a", "b"), handled.takeLast(2).map { it.transaction.hash }.toSet())
        assertEquals(1, transactionRequests.count { it == "b" })
        assertEquals("b", forwardCursor(USER))
    }

    @Test
    fun sync_signatureInWalletAndTokenStream_fetchedAndStoredOnce() = runBlocking {
        tokenAccounts = listOf(TOKEN_A)
        syncedWithOldTransaction()
        onChain("x", slot = 5, USER, TOKEN_A)

        syncer.sync()

        assertEquals(1, transactionRequests.count { it == "x" })
        assertEquals(1, handled.count { it.transaction.hash == "x" })
        assertEquals("x", forwardCursor(USER))
        assertEquals("x", forwardCursor(TOKEN_A))
    }

    @Test
    fun sync_storedSignature_notFetchedButCursorMovesPastIt() = runBlocking {
        syncedWithOldTransaction()
        onChain("sent", slot = 5)
        storedRows += "sent"
        onChain("n", slot = 6)

        syncer.sync()

        assertFalse("sent" in transactionRequests)
        assertTrue("n" in storedRows)
        assertEquals("n", forwardCursor(USER))
    }

    @Test
    fun sync_storedHistoryWithGaps_backwardFillsGapsAndReachesDone() = runBlocking {
        onChain("g1", slot = 1)
        onChain("g2", slot = 2)
        onChain("g3", slot = 3)
        storedRows += setOf("g1", "g3")

        syncer.sync()

        assertEquals(listOf("g2"), transactionRequests)
        assertTrue("g2" in storedRows)
        assertEquals("done", backwardCursor())
    }

    @Test
    fun sync_backwardPageOfStoredRowsOnly_advancesCursorPastIt() = runBlocking {
        onChain("u", slot = 1)
        (2L..1001L).forEach { slot -> onChain("s$slot", slot) }
        storedRows += (2L..1001L).map { "s$it" }

        syncer.sync()

        assertTrue(transactionRequests.isEmpty())
        assertEquals("s3", backwardCursor())

        syncer.sync()

        assertTrue("u" in storedRows)
        assertEquals("done", backwardCursor())
    }

    @Test
    fun sync_truncatedPageBoundarySlot_notProcessedUntilListedInFull() = runBlocking {
        onChain("z0", slot = 50)
        onChain("z1", slot = 50)
        (100L..1098L).forEach { slot -> onChain("s$slot", slot) }
        storedRows += (100L..1098L).map { "s$it" }

        syncer.sync()

        assertTrue(transactionRequests.isEmpty())
        assertEquals("s100", backwardCursor())

        syncer.sync()

        assertEquals(setOf("z0", "z1"), handled.map { it.transaction.hash }.toSet())
        assertEquals("done", backwardCursor())
    }

    @Test
    fun sync_fullPageWithinOneSlot_pagedFurtherInSamePass() = runBlocking {
        onChain("y", slot = 400)
        repeat(1000) { onChain("s$it", slot = 500) }
        storedRows += List(1000) { "s$it" }

        syncer.sync()

        assertTrue("y" in storedRows)
        assertEquals("done", backwardCursor())
    }

    @Test
    fun sync_slotGroupLargerThanBudget_completesOverPassesViaCarryOver() = runBlocking {
        syncedWithOldTransaction()
        val group = List(25) { "g$it" }
        group.forEach { onChain(it, slot = 9) }

        syncer.sync()

        assertTrue(group.none { it in storedRows })
        assertEquals("old", forwardCursor(USER))

        syncer.sync()

        assertEquals(group.toSet(), handled.takeLast(25).map { it.transaction.hash }.toSet())
        assertTrue(group.all { signature -> transactionRequests.count { it == signature } == 1 })
        assertEquals(group.last(), forwardCursor(USER))
    }

    @Test
    fun sync_tokenAccountDiscoveredAfterInitialisation_listedInFullAndStored() = runBlocking {
        onChain("old", slot = 1)
        onChain("t1", slot = 2, TOKEN_A)
        onChain("t2", slot = 3, TOKEN_A)
        syncer.sync()
        assertEquals("done", backwardCursor())

        tokenAccounts = listOf(TOKEN_A)
        syncer.sync()

        assertTrue(storedRows.containsAll(listOf("t1", "t2")))
        assertEquals("t2", forwardCursor(TOKEN_A))
    }

    @Test
    fun sync_tokenAccountBackAfterAbsence_resumesFromOwnCursorAndStoresMissedTransfer() = runBlocking {
        tokenAccounts = listOf(TOKEN_A)
        onChain("old", slot = 1)
        onChain("t1", slot = 2, TOKEN_A)
        syncer.sync()
        assertEquals("t1", forwardCursor(TOKEN_A))

        restartWithTokenAccounts()
        onChain("close", slot = 4, USER, TOKEN_A)
        onChain("recreate", slot = 5, USER, TOKEN_A)
        onChain("t2", slot = 6, TOKEN_A)
        syncer.sync()
        assertEquals("t1", forwardCursor(TOKEN_A))
        assertFalse("t2" in storedRows)

        restartWithTokenAccounts(TOKEN_A)
        syncer.sync()

        assertTrue("t2" in storedRows)
        assertEquals("t2", forwardCursor(TOKEN_A))
    }

    @Test
    fun sync_tokenAccountClosedDuringBackwardWalk_olderTransfersStoredBeforeDone() = runBlocking {
        tokenAccounts = listOf(TOKEN_A)
        onChain("ta", slot = 1, TOKEN_A)
        (2L..26L).forEach { slot -> onChain("w$slot", slot) }
        syncer.sync()
        assertEquals("w7", backwardCursor())

        restartWithTokenAccounts()
        syncer.sync()

        assertTrue("ta" in storedRows)
        assertEquals("done", backwardCursor())
    }

    @Test
    fun sync_unusableTransaction_skippedAfterThreePassesTransientNever() = runBlocking {
        syncedWithOldTransaction()
        onChain("u", slot = 5)
        onChain("n", slot = 6)
        transactionResponses["u"] = { RpcOutcome.Success(null) }

        repeat(2) { syncer.sync() }
        assertEquals("old", forwardCursor(USER))

        syncer.sync()
        assertEquals("n", forwardCursor(USER))
        assertFalse("u" in storedRows)

        onChain("v", slot = 7)
        transactionResponses["v"] = { RpcOutcome.HttpFailure(429, retryAfter = null, body = "") }
        repeat(5) { syncer.sync() }
        assertEquals("n", forwardCursor(USER))
    }

    @Test
    fun sync_transactionWithoutBlockTime_storedWithListingBlockTime() = runBlocking {
        onChain("listed", slot = 1, blockTime = 1_700_000_000)
        onChain("unknown", slot = 2, blockTime = null)

        syncer.sync()

        val timestamps = handled.associate { it.transaction.hash to it.transaction.timestamp }
        assertEquals(mapOf("listed" to 1_700_000_000L, "unknown" to 0L), timestamps)
    }

    @Test
    fun sync_cancelledWhileFetchingForward_rethrowsAndKeepsCursors() = runBlocking {
        syncedWithOldTransaction()
        onChain("n1", slot = 5)
        val cursorsBefore = cursors.toMap()
        transactionResponses["n1"] = { throw CancellationException("cancelled") }

        try {
            syncer.sync()
            fail("cancellation was swallowed")
        } catch (_: CancellationException) {
        }

        assertEquals(cursorsBefore, cursors)
        assertTrue(syncer.syncState.value is SolanaKit.SyncState.NotSynced)
    }

    @Test
    fun sync_transactionWithMoreThanHundredUserMints_storedWithEveryTransfer() = runBlocking {
        syncedWithOldTransaction()
        onChain("many", slot = 5)
        val mints = List(101) { index -> Base58.encode(ByteArray(32) { if (it == 0) index.toByte() else 21 }) }
        val result = transactionResult(
            "many", listOf(USER), emptyList(), fee = 5_000, preBalances = listOf(1), postBalances = listOf(1),
            preTokenBalances = mints.mapIndexed { index, mint -> tokenBalance(mint, amount = "0", owner = USER, accountIndex = index + 1) },
            postTokenBalances = mints.mapIndexed { index, mint -> tokenBalance(mint, amount = "1", owner = USER, accountIndex = index + 1) },
        )
        transactionResponses["many"] = { RpcOutcome.Success(result) }

        syncer.sync()

        assertEquals(101, handled.single { it.transaction.hash == "many" }.tokenTransfers.size)
        assertEquals("many", forwardCursor(USER))
    }

    @Test
    fun sync_severalSlotGroupsStored_notifiedOnceWithAll() = runBlocking {
        syncedWithOldTransaction()
        notified.clear()
        onChain("a", slot = 5)
        onChain("b", slot = 6)
        onChain("c", slot = 7)

        syncer.sync()

        assertEquals(setOf("a", "b", "c"), notified.single().toSet())
    }

    @Test
    fun sync_userMintClosedOnChain_storesTransactionAndAdvancesCursor() = runBlocking {
        syncedWithOldTransaction()
        onChain("closedMint", slot = 5)
        givenTransaction("closedMint", blockTime = 50)
        mintAccountsResponse = { count -> RpcResponse(result = List(count) { null }) }

        syncer.sync()

        assertTrue(handled.any { it.transaction.hash == "closedMint" })
        assertEquals("closedMint", forwardCursor(USER))
    }

    @Test
    fun mintAccountsSerializer_closedMintAnsweredNull_decodesToNullEntry() {
        val body = """{"jsonrpc":"2.0","result":{"context":{"slot":1},"value":[null]},"id":"1"}"""

        val response = decodeRpcResponse(body, mintAccountsSerializer, retryAfter = null)

        assertEquals(listOf(null), response.result)
    }

    @Test
    fun sync_walletWithoutOwnSignatures_backwardWalkNotResetAndReachesDone() = runBlocking {
        tokenAccounts = listOf(TOKEN_A)
        (1L..1001L).forEach { slot -> onChain("t$slot", slot, TOKEN_A) }
        storedRows += (3L..1001L).map { "t$it" }

        syncer.sync()
        assertEquals("t3", backwardCursor())

        syncer.sync()

        assertTrue(storedRows.containsAll(listOf("t1", "t2")))
        assertEquals("done", backwardCursor())
    }

    @Test
    fun sync_passRunning_secondCallReturnsImmediately() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        coEvery { pendingTransactionSyncer.sync() } coAnswers { gate.await() }

        val first = launch { syncer.sync() }
        yield()
        syncer.sync()
        gate.complete(Unit)
        first.join()

        coVerify(exactly = 1) { pendingTransactionSyncer.sync() }
    }

    @Test
    fun sync_repairCursorDone_fetchesNoTransaction() = runBlocking {
        coEvery { storage.tokenTransferRepairCursor() } returns "done"

        syncer.sync()

        coVerify(exactly = 0) { storage.tokenTransferTransactionHashes(any(), any(), any()) }
        assertTrue(transactionRequests.isEmpty())
    }

    @Test
    fun sync_repairBatchEmpty_savesDoneCursor() = runBlocking {
        syncer.sync()

        coVerify { storage.saveTokenTransferRepairCursor("done") }
    }

    @Test
    fun sync_repairBatchOfTwo_handlesRemappedTransactionsAndSavesCursorOfLastRow() = runBlocking {
        givenRepairBatch()
        givenTransaction(HASH_A, blockTime = 290)
        givenTransaction(HASH_B, blockTime = 190)

        syncer.sync()

        val repaired = repairedTransactions()
        assertEquals(listOf(HASH_A, HASH_B), repaired.map { it.transaction.hash })
        assertTrue(repaired.all { it.tokenTransfers.single().tokenTransfer.incoming })
        coVerify { storage.tokenTransferTransactionHashes(null, null, REPAIR_BATCH) }
        coVerify { storage.saveTokenTransferRepairCursor("200:$HASH_B") }
    }

    @Test
    fun sync_historyAndRepairInOnePass_notifiedOnceWithBoth() = runBlocking {
        givenRepairBatch()
        givenTransaction(HASH_A, blockTime = 290)
        givenTransaction(HASH_B, blockTime = 190)
        onChain("fresh", slot = 5)

        syncer.sync()

        assertEquals(setOf("fresh", HASH_A, HASH_B), notified.single().toSet())
    }

    @Test
    fun sync_repairTransactionNotFound_skippedInThirdPassAndCursorAdvances() = runBlocking {
        givenRepairBatch()
        transactionResponses[HASH_A] = { RpcOutcome.Success(null) }
        givenTransaction(HASH_B, blockTime = 190)

        repeat(2) { syncer.sync() }
        coVerify(exactly = 0) { storage.saveTokenTransferRepairCursor(any()) }

        syncer.sync()

        assertEquals(listOf(HASH_B), repairedTransactions().map { it.transaction.hash })
        coVerify { storage.saveTokenTransferRepairCursor("200:$HASH_B") }
    }

    @Test
    fun sync_repairNetworkErrorOnSecondHash_savesCursorOfFirst() = runBlocking {
        givenRepairBatch()
        givenTransaction(HASH_A, blockTime = 290)

        syncer.sync()

        assertEquals(listOf(HASH_A), repairedTransactions().map { it.transaction.hash })
        coVerify { storage.saveTokenTransferRepairCursor("300:$HASH_A") }
    }

    @Test
    fun sync_repairTokenBalanceListsMissing_neverReplacesStoredTransfers() = runBlocking {
        givenRepairBatch()
        givenTransaction(HASH_A, blockTime = 290) { it.copy(preTokenBalances = null, postTokenBalances = null) }
        givenTransaction(HASH_B, blockTime = 190) { it.copy(preTokenBalances = emptyList(), postTokenBalances = emptyList()) }

        syncer.sync()

        assertEquals(listOf(HASH_A, HASH_B), repairedTransactions().map { it.transaction.hash })
        assertEquals(setOf(HASH_B), replaced.single { it.isNotEmpty() })
    }

    @Test
    fun sync_repairMintAccountsFail_doesNotHandleOrMoveCursor() = runBlocking {
        givenRepairBatch()
        givenTransaction(HASH_A, blockTime = 290)
        givenTransaction(HASH_B, blockTime = 190)
        mintAccountsResponse = { rpcError() }

        syncer.sync()

        assertTrue(handled.isEmpty())
        coVerify(exactly = 0) { storage.saveTokenTransferRepairCursor(any()) }
        assertTrue(syncer.syncState.value is SolanaKit.SyncState.Synced)
    }

    @Test
    fun sync_repairThrows_endsSynced() = runBlocking {
        coEvery { storage.tokenTransferRepairCursor() } throws IllegalStateException("broken row")

        syncer.sync()

        assertTrue(syncer.syncState.value is SolanaKit.SyncState.Synced)
    }

    @Test
    fun sync_repairCancelled_endsNotSynced() = runBlocking {
        coEvery { storage.tokenTransferRepairCursor() } throws CancellationException()

        syncIgnoringCancellation()

        assertTrue(syncer.syncState.value is SolanaKit.SyncState.NotSynced)
    }

    @Test
    fun sync_cancelledWhileRepairFetchesTransaction_endsNotSyncedWithoutCursorMove() = runBlocking {
        givenRepairBatch()
        lateinit var syncJob: Job
        transactionResponses[HASH_A] = { syncJob.cancel(); RpcOutcome.TransportFailure(IOException("cancelled")) }

        syncJob = launch { syncer.sync() }
        syncJob.join()

        assertTrue(syncer.syncState.value is SolanaKit.SyncState.NotSynced)
        coVerify(exactly = 0) { storage.saveTokenTransferRepairCursor(any()) }
    }

    @Test
    fun sync_cancelledWhileRepairFetchesMintAccounts_endsNotSyncedWithoutCursorMove() = runBlocking {
        givenRepairBatch()
        givenTransaction(HASH_A, blockTime = 290)
        givenTransaction(HASH_B, blockTime = 190)
        lateinit var syncJob: Job
        mintAccountsResponse = { syncJob.cancel(); rpcError() }

        syncJob = launch { syncer.sync() }
        syncJob.join()

        assertTrue(syncer.syncState.value is SolanaKit.SyncState.NotSynced)
        coVerify(exactly = 0) { storage.saveTokenTransferRepairCursor(any()) }
    }

    private fun newSyncer() = TransactionSyncer(
        publicKey = PublicKey(USER),
        rpcClient = Api(router),
        storage = storage,
        transactionManager = transactionManager,
        pendingTransactionSyncer = pendingTransactionSyncer,
        fetcher = fetcher,
    )

    // The token account list is cached per syncer, so a changed discovery needs a new one, as after a restart.
    private fun restartWithTokenAccounts(vararg accounts: String) {
        tokenAccounts = accounts.toList()
        syncer = newSyncer()
    }

    private fun record(transactions: List<FullTransaction>, replace: Set<String>): List<FullTransaction> {
        handled += transactions
        replaced += replace
        storedRows += transactions.map { it.transaction.hash }
        return transactions
    }

    private suspend fun syncedWithOldTransaction() {
        onChain("old", slot = 1)
        syncer.sync()
        assertEquals("done", backwardCursor())
        transactionRequests.clear()
    }

    private fun onChain(signature: String, slot: Long, vararg streams: String = arrayOf(USER), blockTime: Long? = slot * 10) {
        chain += ChainTx(signature, slot, streams.toSet(), chain.size, blockTime)
    }

    private fun forwardCursor(stream: String) = cursors["history-forward-v1:$stream"]

    private fun backwardCursor() = cursors["history-backward-v1"]

    private fun givenRepairBatch() {
        coEvery { storage.tokenTransferTransactionHashes(any(), any(), any()) } returns
            listOf(TransactionKey(300, HASH_A), TransactionKey(200, HASH_B))
    }

    private fun givenTransaction(hash: String, blockTime: Long, meta: (Meta) -> Meta = { it }) {
        val result = transactionResult(
            signature = hash,
            accountKeys = listOf("SENDER", "USER_ATA", "SENDER_ATA"),
            instructions = emptyList(),
            fee = 5_000L,
            preBalances = listOf(1L, 1L, 1L),
            postBalances = listOf(1L, 1L, 1L),
            preTokenBalances = listOf(
                tokenBalance(MINT, amount = "0", owner = USER, accountIndex = 1),
                tokenBalance(MINT, amount = "9", owner = "SENDER", accountIndex = 2),
            ),
            postTokenBalances = listOf(
                tokenBalance(MINT, amount = "5", owner = USER, accountIndex = 1),
                tokenBalance(MINT, amount = "4", owner = "SENDER", accountIndex = 2),
            ),
        )
        val adjusted = result.copy(blockTime = blockTime, meta = result.meta?.let(meta))
        transactionResponses[hash] = { RpcOutcome.Success(adjusted) }
    }

    private fun repairedTransactions(): List<FullTransaction> = handled.filter { it.transaction.hash in setOf(HASH_A, HASH_B) }

    private fun transactionOutcome(signature: String): RpcOutcome<TransactionResult> {
        transactionRequests += signature
        if (allTransactionsFail) return RpcOutcome.TransportFailure(IOException("unavailable"))
        transactionResponses[signature]?.let { return it() }
        val tx = chain.firstOrNull { it.signature == signature } ?: return RpcOutcome.TransportFailure(IOException("unknown"))
        val result = transactionResult(tx.signature, listOf(USER), emptyList(), fee = 5_000, preBalances = listOf(1), postBalances = listOf(1))
        return RpcOutcome.Success(result.copy(slot = tx.slot, blockTime = null))
    }

    private suspend fun respond(request: RpcRequest): RpcResponse<Any?> = when (request.method) {
        "getSignaturesForAddress" -> if (listingFails) rpcError() else RpcResponse(result = listing(request))
        "getTokenAccountsByOwner" ->
            if (discoveryFails) rpcError() else RpcResponse(result = tokenAccounts.map { SplTokenAccountWithPublicKey(it) })
        "getMultipleAccounts" -> mintAccountsResponse(checkNotNull(request.params).jsonArray[0].jsonArray.size)
        else -> rpcError()
    }

    // Like the node: newest first, `before`/`until` resolved by (slot, position) even for a foreign signature.
    private fun listing(request: RpcRequest): List<RpcSignatureInformation> {
        listingRequests++
        val params = checkNotNull(request.params).jsonArray
        val config = params[1].jsonObject
        val before = config["before"]?.jsonPrimitive?.contentOrNull?.let(::chainTx)
        val until = config["until"]?.jsonPrimitive?.contentOrNull?.let(::chainTx)
        val limit = config["limit"]?.jsonPrimitive?.int ?: TransactionSyncer.rpcSignaturesCount
        return chain.filter { params[0].jsonPrimitive.content in it.streams }
            .filterNot { listingRequests > 1 && it.signature in hiddenAfterFirstListing }
            .sortedWith(chainOrder.reversed())
            .filter { before == null || chainOrder.compare(it, before) < 0 }
            .filter { until == null || chainOrder.compare(it, until) > 0 }
            .take(limit)
            .map { RpcSignatureInformation(signature = it.signature, confirmationStatus = "finalized", slot = it.slot, blockTime = it.blockTime) }
    }

    private fun chainTx(signature: String) = chain.first { it.signature == signature }

    private fun signatureOf(request: RpcRequest) = checkNotNull(request.params?.jsonArray?.get(0)?.jsonPrimitive?.content)

    private fun rpcError(): RpcResponse<Any?> = RpcResponse(error = RpcError(code = -1, message = "unavailable"))

    // Like the node, which refuses more than 100 accounts in one request.
    private fun mintAccounts(count: Int): RpcResponse<Any?> =
        if (count > 100) rpcError() else RpcResponse(result = List(count) { mintAccountValue() })

    private fun mintAccountValue() = MintTokenAccountValue(
        data = MintTokenAccountInfo(
            parsed = MintTokenAccountInfoParsedData(
                info = MintTokenAccountTokenInfo(decimals = 6, isInitialized = true, mintAuthority = "AUTHORITY", supply = "1000"),
                type = "mint",
            ),
            program = "spl-token",
        ),
        owner = TransactionSyncer.tokenProgramId,
    )

    private suspend fun syncIgnoringCancellation() {
        try {
            syncer.sync()
        } catch (_: CancellationException) {
        }
    }

    private companion object {
        const val USER = "11111111111111111111111111111111"
        const val REPAIR_BATCH = 20
        const val HASH_A = "hashA"
        const val HASH_B = "hashB"
        val MINT: String = Base58.encode(ByteArray(32) { 9 })
        val TOKEN_A: String = Base58.encode(ByteArray(32) { 3 })
        val TOKEN_B: String = Base58.encode(ByteArray(32) { 4 })
        val chainOrder = compareBy<ChainTx>({ it.slot }, { it.position })
    }
}
