package io.horizontalsystems.solanakit.transactions

import com.solana.api.Api
import com.solana.core.PublicKey
import com.solana.networking.NetworkingRouter
import com.solana.networking.RpcError
import com.solana.networking.RpcRequest
import com.solana.networking.RpcResponse
import io.horizontalsystems.solanakit.SolanaKit
import io.horizontalsystems.solanakit.database.transaction.TransactionStorage
import io.horizontalsystems.solanakit.database.transaction.dao.TransactionsDao.TransactionKey
import io.horizontalsystems.solanakit.models.FullTransaction
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sol4k.Base58

class TransactionSyncerTest {

    private val storage = mockk<TransactionStorage>(relaxed = true)
    private val pendingTransactionSyncer = mockk<PendingTransactionSyncer>(relaxed = true)
    private val transactionManager = mockk<TransactionManager>(relaxed = true)
    private val router = mockk<NetworkingRouter>()
    private val transactionResponses = mutableMapOf<String, suspend () -> RpcResponse<Any?>>()
    private var mintAccountsResponse: suspend () -> RpcResponse<Any?> = { RpcResponse(result = listOf(mintAccountValue())) }

    private val syncer = TransactionSyncer(
        publicKey = PublicKey(USER),
        rpcClient = Api(router),
        storage = storage,
        transactionManager = transactionManager,
        pendingTransactionSyncer = pendingTransactionSyncer,
        limitFirstTimeTransactionCount = -1,
        limitTimeTransactionCount = BATCH_SIZE
    )

    init {
        coEvery { storage.lastNonPendingTransaction() } returns null
        coEvery { storage.tokenTransferRepairCursor() } returns null
        coEvery { storage.tokenTransferTransactionHashes(any(), any(), any()) } returns emptyList()
        coEvery { router.makeRequest<Any?>(any(), any()) } coAnswers { respond(firstArg()) }
    }

    @Test
    fun sync_pendingSyncCancelled_leavesSyncingState() = runBlocking {
        coEvery { pendingTransactionSyncer.sync() } throws CancellationException()

        syncIgnoringCancellation()

        assertFalse(syncer.syncState.value is SolanaKit.SyncState.Syncing)
    }

    @Test
    fun sync_storageReadCancelled_leavesSyncingState() = runBlocking {
        coEvery { storage.lastNonPendingTransaction() } throws CancellationException()

        syncIgnoringCancellation()

        assertFalse(syncer.syncState.value is SolanaKit.SyncState.Syncing)
    }

    @Test
    fun sync_repairCursorDone_fetchesNoTransaction() = runBlocking {
        coEvery { storage.tokenTransferRepairCursor() } returns "done"

        syncer.sync()

        coVerify(exactly = 0) { storage.tokenTransferTransactionHashes(any(), any(), any()) }
        coVerify(exactly = 0) { router.makeRequest<Any?>(match { it.method == "getTransaction" }, any()) }
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
        coVerify { storage.tokenTransferTransactionHashes(null, null, BATCH_SIZE) }
        coVerify { storage.saveTokenTransferRepairCursor("200:$HASH_B") }
    }

    @Test
    fun sync_repairTransactionNotFound_skipsItAndAdvancesCursor() = runBlocking {
        givenRepairBatch()
        transactionResponses[HASH_A] = { RpcResponse(result = null) }
        givenTransaction(HASH_B, blockTime = 190)

        syncer.sync()

        assertEquals(listOf(HASH_B), repairedTransactions().map { it.transaction.hash })
        coVerify { storage.saveTokenTransferRepairCursor("200:$HASH_B") }
    }

    @Test
    fun sync_repairNetworkErrorOnSecondHash_savesCursorOfFirst() = runBlocking {
        givenRepairBatch()
        givenTransaction(HASH_A, blockTime = 290)
        transactionResponses[HASH_B] = { rpcError() }

        syncer.sync()

        assertEquals(listOf(HASH_A), repairedTransactions().map { it.transaction.hash })
        coVerify { storage.saveTokenTransferRepairCursor("300:$HASH_A") }
    }

    @Test
    fun sync_repairMintAccountsFail_doesNotHandleOrMoveCursor() = runBlocking {
        givenRepairBatch()
        givenTransaction(HASH_A, blockTime = 290)
        givenTransaction(HASH_B, blockTime = 190)
        mintAccountsResponse = { rpcError() }

        syncer.sync()

        coVerify(exactly = 0) { transactionManager.handle(match { it.isNotEmpty() }) }
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
        transactionResponses[HASH_A] = { syncJob.cancel(); rpcError() }

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

    private fun givenRepairBatch() {
        coEvery { storage.tokenTransferTransactionHashes(any(), any(), any()) } returns
            listOf(TransactionKey(300, HASH_A), TransactionKey(200, HASH_B))
    }

    private fun givenTransaction(hash: String, blockTime: Long) {
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
        ).copy(blockTime = blockTime)
        transactionResponses[hash] = { RpcResponse(result = result) }
    }

    private fun repairedTransactions(): List<FullTransaction> {
        val handled = mutableListOf<List<FullTransaction>>()
        coVerify { transactionManager.handle(capture(handled)) }
        return handled.single { it.isNotEmpty() }
    }

    private suspend fun respond(request: RpcRequest): RpcResponse<Any?> = when (request.method) {
        "getTransaction" -> {
            val signature = request.params?.jsonArray?.get(0)?.jsonPrimitive?.content
            transactionResponses[signature]?.invoke() ?: rpcError()
        }
        "getMultipleAccounts" -> mintAccountsResponse()
        "getSignaturesForAddress" -> RpcResponse(result = emptyList<Any>())
        else -> rpcError()
    }

    private fun rpcError(): RpcResponse<Any?> = RpcResponse(error = RpcError(code = -1, message = "unavailable"))

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
        const val BATCH_SIZE = 2
        const val HASH_A = "hashA"
        const val HASH_B = "hashB"
        val MINT: String = Base58.encode(ByteArray(32) { 9 })
    }
}
