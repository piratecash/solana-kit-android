package io.horizontalsystems.solanakit.database

import com.solana.api.Header
import com.solana.api.Message
import com.solana.api.Meta
import com.solana.api.Status
import com.solana.api.TokenAmountInfo
import com.solana.api.TokenBalance
import io.horizontalsystems.solanakit.SolanaKit
import io.horizontalsystems.solanakit.database.transaction.TransactionStorage
import io.horizontalsystems.solanakit.database.transaction.dao.TransactionsDao.TransactionKey
import io.horizontalsystems.solanakit.models.FullTokenTransfer
import io.horizontalsystems.solanakit.models.FullTransaction
import io.horizontalsystems.solanakit.models.MintAccount
import io.horizontalsystems.solanakit.models.TokenTransfer
import io.horizontalsystems.solanakit.models.Transaction
import io.horizontalsystems.solanakit.transactions.SolanaTransactionMapper
import io.horizontalsystems.solanakit.transactions.TransactionManager
import io.horizontalsystems.solanakit.transactions.TransactionResult
import io.horizontalsystems.solanakit.transactions.TransactionSyncer
import io.horizontalsystems.solanakit.transactions.WellKnownPrograms
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.sol4k.Base58
import java.math.BigDecimal
import com.solana.api.Transaction as RpcTransaction

class TokenTransferRepairDesktopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val rpcTransactions = mutableMapOf<String, TransactionResult>()
    private lateinit var kit: SyncerFixture
    private lateinit var storage: TransactionStorage
    private lateinit var transactionManager: TransactionManager
    private lateinit var syncer: TransactionSyncer

    @Before
    fun setUp() {
        kit = SyncerFixture(tmp.root, "Solana-repair", USER, FakeRpcRouter(rpcTransactions))
        storage = kit.storage
        transactionManager = kit.transactionManager
        syncer = kit.syncer
    }

    @After
    fun tearDown() {
        kit.close()
    }

    @Test
    fun tokenTransferTransactionHashes_mixedRows_returnsConfirmedTokenRowsAfterCursorNewestFirst() = runBlocking {
        storeLegacyTransfer("hashA", timestamp = 300)
        storeLegacyTransfer("hashC", timestamp = 200)
        storeLegacyTransfer("hashB", timestamp = 200)
        storeLegacyTransfer("pending", timestamp = 250, pending = true)
        storeLegacyTransfer("external", timestamp = 260, external = true)
        storage.addTransactions(listOf(FullTransaction(Transaction("solOnly", 270, pending = false), emptyList())))

        assertEquals(
            listOf(TransactionKey(300, "hashA"), TransactionKey(200, "hashC"), TransactionKey(200, "hashB")),
            storage.tokenTransferTransactionHashes(null, null, -1),
        )
        assertEquals(
            listOf(TransactionKey(300, "hashA"), TransactionKey(200, "hashC")),
            storage.tokenTransferTransactionHashes(null, null, 2),
        )
        assertEquals(listOf(TransactionKey(200, "hashB")), storage.tokenTransferTransactionHashes(200, "hashC", 2))
    }

    @Test
    fun addTransactions_mappedIncomingTransferWithRecipientEntryFirst_listedUnderIncomingOnly() = runBlocking {
        val mapped = SolanaTransactionMapper.map(USER, listOf(incomingTransfer("qaHash", 1_700_000_000)), mapOf(MINT to MINT_ACCOUNT))

        storage.addTransactions(mapped)

        assertEquals(listOf("qaHash"), storage.getSplTransactions(MINT, true, null, null).map { it.transaction.hash })
        assertTrue(storage.getSplTransactions(MINT, false, null, null).isEmpty())
    }

    @Test
    fun sync_storedRowWithLegacyDirection_repairsDirectionAndEndpoints() = runBlocking {
        storeLegacyTransfer("legacyHash", timestamp = 1_700_000_100)
        rpcTransactions["legacyHash"] = incomingTransfer("legacyHash", blockTime = 1_700_000_000)

        syncer.sync()

        val repaired = storage.getFullTransactions(listOf("legacyHash")).single()
        assertTrue(syncer.syncState.value is SolanaKit.SyncState.Synced)
        assertTrue(repaired.tokenTransfers.single().tokenTransfer.incoming)
        assertEquals("SENDER", repaired.transaction.from)
        assertEquals(USER, repaired.transaction.to)
    }

    @Test
    fun sync_blockTimeEarlierThanDeviceTimestamp_cursorDoesNotSkipNextRow() = runBlocking {
        // Fills the repair batch so that it ends at hashB.
        (1..18).forEach { index ->
            storeLegacyTransfer("newer$index", timestamp = 300L + index)
            rpcTransactions["newer$index"] = incomingTransfer("newer$index", blockTime = 300L + index)
        }
        storeLegacyTransfer("hashA", timestamp = 300)
        storeLegacyTransfer("hashB", timestamp = 200)
        storeLegacyTransfer("hashC", timestamp = 195)
        rpcTransactions["hashA"] = incomingTransfer("hashA", blockTime = 300)
        rpcTransactions["hashB"] = incomingTransfer("hashB", blockTime = 190)
        rpcTransactions["hashC"] = incomingTransfer("hashC", blockTime = 195)

        syncer.sync()
        assertEquals("200:hashB", storage.tokenTransferRepairCursor())
        assertFalse(isIncoming("hashC"))

        syncer.sync()
        assertTrue(isIncoming("hashC"))
    }

    @Test
    fun handle_batchWithOneTransactionWithoutTokenTransfers_keepsItsStoredTransfer() = runBlocking {
        storeLegacyTransfer("hashA", timestamp = 300)
        storeLegacyTransfer("hashB", timestamp = 200)
        val remappedB = FullTokenTransfer(TokenTransfer("hashB", MINT, incoming = true, amount = BigDecimal.ONE), MINT_ACCOUNT)

        transactionManager.handle(
            listOf(
                FullTransaction(Transaction("hashA", 300, pending = false), emptyList()),
                FullTransaction(Transaction("hashB", 200, pending = false), listOf(remappedB)),
            )
        )

        val stored = storage.getFullTransactions(listOf("hashA", "hashB")).associateBy { it.transaction.hash }
        assertEquals(setOf("hashA", "hashB"), stored.keys)
        assertFalse(stored.getValue("hashA").tokenTransfers.single().tokenTransfer.incoming)
        assertTrue(stored.getValue("hashB").tokenTransfers.single().tokenTransfer.incoming)
    }

    @Test
    fun getTransactions_transactionWithTwoIncomingMints_returnedOnce() = runBlocking {
        val secondMint = MintAccount(Base58.encode(ByteArray(32) { 11 }), decimals = 6)
        storage.addTransactions(
            listOf(
                FullTransaction(
                    Transaction("swapHash", 300, pending = false),
                    listOf(
                        FullTokenTransfer(TokenTransfer("swapHash", MINT, incoming = true, amount = BigDecimal.ONE), MINT_ACCOUNT),
                        FullTokenTransfer(TokenTransfer("swapHash", secondMint.address, incoming = true, amount = BigDecimal.TEN), secondMint),
                    ),
                )
            )
        )

        assertEquals(listOf("swapHash"), storage.getTransactions(true, null, null).map { it.transaction.hash })
    }

    @Test
    fun sync_legacyTokenRowDisprovedBySolOnlyRemap_dropsTokenTransfersAndKeepsSolAmount() = runBlocking {
        storeLegacyTransfer("solHash", timestamp = 1_700_000_100)
        rpcTransactions["solHash"] = solTransferWithForeignTokenChange("solHash", blockTime = 1_700_000_000)

        syncer.sync()

        val repaired = storage.getFullTransactions(listOf("solHash")).single()
        assertTrue(repaired.tokenTransfers.isEmpty())
        assertEquals(0, BigDecimal(SOL_AMOUNT).compareTo(repaired.transaction.amount))
        assertEquals(listOf("solHash"), storage.getSolTransactions(true, null, null).map { it.transaction.hash })
    }

    @Test
    fun sync_ownedChangeOfMintWithoutMetadata_keepsStoredTokenTransfer() = runBlocking {
        storeLegacyTransfer("token2022Hash", timestamp = 1_700_000_100, mint = TOKEN_2022_MINT)
        rpcTransactions["token2022Hash"] = incomingTransfer("token2022Hash", blockTime = 1_700_000_000, mint = TOKEN_2022_MINT)

        syncer.sync()

        val tokenTransfer = storage.getFullTransactions(listOf("token2022Hash")).single().tokenTransfers.single().tokenTransfer
        assertEquals(TOKEN_2022_MINT, tokenTransfer.mintAddress)
        assertFalse(tokenTransfer.incoming)
    }

    @Test
    fun sync_remapWithTokenBalancesOfUnknownOwner_keepsStoredTokenTransferAndAdvancesCursor() = runBlocking {
        storeLegacyTransfer("noOwnerHash", timestamp = 1_700_000_100)
        rpcTransactions["noOwnerHash"] = incomingTransfer("noOwnerHash", blockTime = 1_700_000_000, recipientOwner = null)

        syncer.sync()

        assertEquals(1, storage.getFullTransactions(listOf("noOwnerHash")).single().tokenTransfers.size)
        assertEquals("1700000100:noOwnerHash", storage.tokenTransferRepairCursor())
    }

    @Test
    fun sync_remapOfFailedTransaction_keepsStoredTokenTransfer() = runBlocking {
        storeLegacyTransfer("failedHash", timestamp = 1_700_000_100)
        val error = buildJsonObject { put("InstructionError", "InsufficientFunds") }
        rpcTransactions["failedHash"] = incomingTransfer("failedHash", blockTime = 1_700_000_000, tokenDelta = 0, err = error)

        syncer.sync()

        assertEquals(1, storage.getFullTransactions(listOf("failedHash")).single().tokenTransfers.size)
    }

    @Test
    fun sync_remapWithoutMeta_keepsStoredRowAndAdvancesCursor() = runBlocking {
        storeLegacyTransfer("noMetaHash", timestamp = 1_700_000_100)
        val storedHeader = storage.getFullTransactions(listOf("noMetaHash")).single().transaction
        rpcTransactions["noMetaHash"] = incomingTransfer("noMetaHash", blockTime = 1_700_000_000).copy(meta = null)

        syncer.sync()

        val stored = storage.getFullTransactions(listOf("noMetaHash")).single()
        assertEquals(storedHeader, stored.transaction)
        assertEquals(1, stored.tokenTransfers.size)
        assertEquals("1700000100:noMetaHash", storage.tokenTransferRepairCursor())
    }

    @Test
    fun sync_remapWithOneTokenBalanceListMissing_keepsStoredTransferAmount() = runBlocking {
        listOf("noPreHash", "noPostHash").forEach { hash ->
            storeLegacyTransfer(hash, timestamp = 1_700_000_100)
            val remap = incomingTransfer(hash, blockTime = 1_700_000_000)
            val meta = checkNotNull(remap.meta)
            val partial = if (hash == "noPreHash") meta.copy(preTokenBalances = null) else meta.copy(postTokenBalances = null)
            rpcTransactions[hash] = remap.copy(meta = partial)
        }

        syncer.sync()

        val stored = storage.getFullTransactions(listOf("noPreHash", "noPostHash"))
        assertEquals(2, stored.size)
        stored.forEach { row ->
            val tokenTransfer = row.tokenTransfers.single().tokenTransfer
            assertEquals(0, BigDecimal(TRANSFER_AMOUNT).compareTo(tokenTransfer.amount))
            assertFalse(tokenTransfer.incoming)
        }
    }

    @Test
    fun sync_zeroAmountSpamWithUserAbsentFromAccountKeys_staysOutOfSolHistory() = runBlocking {
        storeLegacyTransfer("spamHash", timestamp = 1_700_000_100)
        rpcTransactions["spamHash"] = rpcResult(
            hash = "spamHash",
            blockTime = 1_700_000_000,
            accountKeys = listOf("FEE_PAYER", "USER_ATA", "SENDER_ATA", MINT, WellKnownPrograms.TOKEN_PROGRAM),
            mint = MINT,
            recipientOwner = USER,
            recipientAccountIndex = 1,
            preBalances = listOf(1_000_000_000L, 1L, 1L, 1L, 1L),
            postBalances = listOf(999_995_000L, 1L, 1L, 1L, 1L),
            tokenDelta = 0,
        )

        syncer.sync()

        assertTrue(storage.getSolTransactions(null, null, null).none { it.transaction.hash == "spamHash" })
    }

    private suspend fun isIncoming(hash: String): Boolean =
        storage.getFullTransactions(listOf(hash)).single().tokenTransfers.single().tokenTransfer.incoming

    // The shape stored before owner-based parsing: an incoming transfer saved as outgoing to the recipient's token account.
    private suspend fun storeLegacyTransfer(
        hash: String,
        timestamp: Long,
        pending: Boolean = false,
        external: Boolean = false,
        mint: String = MINT,
    ) {
        val transaction = Transaction(hash, timestamp, from = "SENDER", to = "USER_ATA", pending = pending, external = external)
        val tokenTransfer = TokenTransfer(hash, mint, incoming = false, amount = BigDecimal(TRANSFER_AMOUNT))
        storage.addTransactions(listOf(FullTransaction(transaction, listOf(FullTokenTransfer(tokenTransfer, MintAccount(mint, 6))))))
    }

    private fun incomingTransfer(
        hash: String,
        blockTime: Long,
        mint: String = MINT,
        recipientOwner: String? = USER,
        tokenDelta: Long = TRANSFER_AMOUNT,
        err: JsonObject? = null,
    ) = rpcResult(
        hash = hash,
        blockTime = blockTime,
        accountKeys = listOf("SENDER", "USER_ATA", "SENDER_ATA", mint, WellKnownPrograms.TOKEN_PROGRAM),
        mint = mint,
        recipientOwner = recipientOwner,
        recipientAccountIndex = 1,
        preBalances = List(5) { 1L },
        postBalances = List(5) { 1L },
        tokenDelta = tokenDelta,
        err = err,
    )

    // The user only receives SOL; the token balances that move belong to other owners.
    private fun solTransferWithForeignTokenChange(hash: String, blockTime: Long) = rpcResult(
        hash = hash,
        blockTime = blockTime,
        accountKeys = listOf("SENDER", USER, "OTHER_ATA", "SENDER_ATA", WellKnownPrograms.TOKEN_PROGRAM),
        mint = MINT,
        recipientOwner = "OTHER",
        recipientAccountIndex = 2,
        preBalances = listOf(1_000_000_000L, 0L, 1L, 1L, 1L),
        postBalances = listOf(1_000_000_000L - SOL_AMOUNT - 5_000L, SOL_AMOUNT, 1L, 1L, 1L),
    )

    private fun rpcResult(
        hash: String,
        blockTime: Long,
        accountKeys: List<String>,
        mint: String,
        recipientOwner: String?,
        recipientAccountIndex: Int,
        preBalances: List<Long>,
        postBalances: List<Long>,
        tokenDelta: Long = TRANSFER_AMOUNT,
        err: JsonObject? = null,
    ): TransactionResult {
        val message = Message(
            accountKeys = accountKeys,
            header = Header(numReadonlySignedAccounts = 0, numReadonlyUnsignedAccounts = 2, numRequiredSignatures = 1),
            instructions = emptyList(),
            recentBlockhash = "blockhash",
        )
        val senderAccountIndex = recipientAccountIndex + 1
        val meta = Meta(
            err = err,
            fee = 5_000,
            innerInstructions = emptyList(),
            preTokenBalances = listOf(
                tokenBalance(mint, recipientAccountIndex, recipientOwner, 1_493_856),
                tokenBalance(mint, senderAccountIndex, "SENDER", 1_463_422),
            ),
            postTokenBalances = listOf(
                tokenBalance(mint, recipientAccountIndex, recipientOwner, 1_493_856 + tokenDelta),
                tokenBalance(mint, senderAccountIndex, "SENDER", 1_463_422 - tokenDelta),
            ),
            postBalances = postBalances,
            preBalances = preBalances,
            status = Status(null),
        )
        return TransactionResult(blockTime, meta, slot = 1, transaction = RpcTransaction(message, listOf(hash)))
    }

    private fun tokenBalance(mint: String, accountIndex: Int, owner: String?, amount: Long) = TokenBalance(
        accountIndex = accountIndex.toDouble(),
        mint = mint,
        uiTokenAmount = TokenAmountInfo(amount = amount.toString(), decimals = 6, uiAmount = null, uiAmountString = ""),
        owner = owner,
    )

    private companion object {
        val USER: String = Base58.encode(ByteArray(32) { 7 })
        val MINT: String = Base58.encode(ByteArray(32) { 9 })
        val MINT_ACCOUNT = MintAccount(MINT, decimals = 6)
        val TOKEN_2022_MINT = FakeRpcRouter.TOKEN_2022_MINT
        const val TRANSFER_AMOUNT = 1_000_047L
        const val SOL_AMOUNT = 1_000_000L
    }
}
