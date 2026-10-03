package io.horizontalsystems.solanakit.transactions

import com.solana.api.Header
import com.solana.api.Instruction
import com.solana.api.LoadedAddresses
import com.solana.api.Message
import com.solana.api.Meta
import com.solana.api.Status
import com.solana.api.TokenAmountInfo
import com.solana.api.TokenBalance
import com.solana.api.Transaction
import io.horizontalsystems.solanakit.models.FullTransaction
import io.horizontalsystems.solanakit.models.MintAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Integration tests for the RPC-result -> FullTransaction mapping: they verify that the SOL
 * resolution is wired into the mapping (instructions are parsed and the owner's transfer is
 * attributed) and that the SOL and token branches stay separated.
 */
class SolanaTransactionMapperTest {

    @Test
    fun map_dustSpamSolTransaction_attributesUserDustAndKeepsTokenTransfersEmpty() {
        val accountKeys = listOf("FUND", "BIG", "USER", "R3", WellKnownPrograms.SYSTEM_PROGRAM)
        val systemProgramIndex = 4L
        val result = transactionResult(
            signature = "dustSig",
            accountKeys = accountKeys,
            instructions = listOf(
                Instruction(accounts = listOf(0L, 1L), data = systemTransferData(440_000_000L), programIdIndex = systemProgramIndex),
                Instruction(accounts = listOf(0L, 2L), data = systemTransferData(1L), programIdIndex = systemProgramIndex),
                Instruction(accounts = listOf(0L, 3L), data = systemTransferData(1L), programIdIndex = systemProgramIndex),
            ),
            fee = 5_000L,
            preBalances = listOf(1_000_000_000L, 500L, 4_463_687L, 200L, 1L),
            postBalances = listOf(559_994_998L, 440_000_500L, 4_463_688L, 201L, 1L),
        )

        val mapped = SolanaTransactionMapper.map(
            userAddress = "USER",
            rpcTransactions = listOf(result),
            mintAccounts = emptyMap(),
        )

        assertEquals(1, mapped.size)
        val transaction = mapped.first().transaction
        assertEquals("dustSig", transaction.hash)
        assertEquals("FUND", transaction.from)
        assertEquals("USER", transaction.to)
        assertEquals(0, BigDecimal(1).compareTo(transaction.amount))
        assertTrue(mapped.first().tokenTransfers.isEmpty())
    }

    @Test
    fun map_tokenTransfer_setsSolAmountNullAndRecordsTokenTransfer() {
        val accountKeys = listOf("USER", "TOKEN_ACCOUNT", WellKnownPrograms.SYSTEM_PROGRAM)
        val mint = "MINT"
        val result = transactionResult(
            signature = "tokenSig",
            accountKeys = accountKeys,
            instructions = emptyList(),
            fee = 5_000L,
            preBalances = listOf(1_000_000_000L, 2_039_280L, 1L),
            postBalances = listOf(999_995_000L, 2_039_280L, 1L),
            preTokenBalances = listOf(tokenBalance(mint, amount = "100", owner = "USER", accountIndex = 1)),
            postTokenBalances = listOf(tokenBalance(mint, amount = "40", owner = "USER", accountIndex = 1)),
        )

        val mapped = SolanaTransactionMapper.map(
            userAddress = "USER",
            rpcTransactions = listOf(result),
            mintAccounts = mapOf(mint to MintAccount(address = mint, decimals = 6)),
        )

        assertEquals(1, mapped.size)
        val full = mapped.first()
        // The SOL branch must be skipped for a token transfer: the SOL amount stays null while a
        // token transfer is recorded instead.
        assertNull(full.transaction.amount)
        assertEquals(1, full.tokenTransfers.size)
        val tokenTransfer = full.tokenTransfers.first().tokenTransfer
        assertEquals(mint, tokenTransfer.mintAddress)
        assertEquals(0, BigDecimal(60).compareTo(tokenTransfer.amount))
        assertFalse(tokenTransfer.incoming)
    }

    @Test
    fun map_incomingTransferWithRecipientEntryFirst_recordsIncomingFromSenderWallet() {
        val result = tokenTransactionResult(
            accountKeys = listOf("SENDER", "USER_ATA", "SENDER_ATA", USDC, WellKnownPrograms.TOKEN_PROGRAM),
            preTokenBalances = listOf(
                tokenBalance(USDC, amount = "1493856", owner = "USER", accountIndex = 1),
                tokenBalance(USDC, amount = "1463422", owner = "SENDER", accountIndex = 2),
            ),
            postTokenBalances = listOf(
                tokenBalance(USDC, amount = "2493903", owner = "USER", accountIndex = 1),
                tokenBalance(USDC, amount = "463375", owner = "SENDER", accountIndex = 2),
            ),
        )

        val full = mapSingle(result)

        assertSingleTokenTransfer(full, USDC, incoming = true, amount = "1000047")
        assertEquals("SENDER", full.transaction.from)
        assertEquals("USER", full.transaction.to)
    }

    @Test
    fun map_outgoingTransferWithSenderEntryFirst_recordsOutgoingToRecipientWallet() {
        val result = tokenTransactionResult(
            accountKeys = listOf("USER", "USER_ATA", "RECIPIENT_ATA", USDC, WellKnownPrograms.TOKEN_PROGRAM),
            preTokenBalances = listOf(
                tokenBalance(USDC, amount = "4269287", owner = "USER", accountIndex = 1),
                tokenBalance(USDC, amount = "463375", owner = "RECIPIENT", accountIndex = 2),
            ),
            postTokenBalances = listOf(
                tokenBalance(USDC, amount = "2269226", owner = "USER", accountIndex = 1),
                tokenBalance(USDC, amount = "2463436", owner = "RECIPIENT", accountIndex = 2),
            ),
        )

        val full = mapSingle(result)

        assertSingleTokenTransfer(full, USDC, incoming = false, amount = "2000061")
        assertEquals("USER", full.transaction.from)
        assertEquals("RECIPIENT", full.transaction.to)
    }

    @Test
    fun map_incomingTransferCreatingRecipientAccount_recordsPostBalanceAsIncoming() {
        val result = tokenTransactionResult(
            accountKeys = listOf("SENDER", "SENDER_ATA", "USER_ATA", USDC, WellKnownPrograms.TOKEN_PROGRAM),
            preTokenBalances = listOf(
                tokenBalance(USDC, amount = "1493856", owner = "SENDER", accountIndex = 1),
            ),
            postTokenBalances = listOf(
                tokenBalance(USDC, amount = "0", owner = "SENDER", accountIndex = 1),
                tokenBalance(USDC, amount = "1493856", owner = "USER", accountIndex = 2),
            ),
        )

        val full = mapSingle(result)

        assertSingleTokenTransfer(full, USDC, incoming = true, amount = "1493856")
        assertEquals("SENDER", full.transaction.from)
        assertEquals("USER", full.transaction.to)
    }

    @Test
    fun map_relayerPayoutWithThirdPartyFeePayer_attributesSenderToDecreasedAccountOwner() {
        val accountKeys = listOf("RELAYER", "RELAYER_AUX", "K2", "K3", "K4", "K5", "K6", "USER_ATA", "POOL_ATA", USDC)
        val result = tokenTransactionResult(
            accountKeys = accountKeys,
            preTokenBalances = listOf(
                tokenBalance(USDC, amount = "2493903", owner = "USER", accountIndex = 7),
                tokenBalance(USDC, amount = "3655165122426", owner = "POOL", accountIndex = 8),
            ),
            postTokenBalances = listOf(
                tokenBalance(USDC, amount = "4269287", owner = "USER", accountIndex = 7),
                tokenBalance(USDC, amount = "3655163347042", owner = "POOL", accountIndex = 8),
            ),
        )

        val full = mapSingle(result)

        assertSingleTokenTransfer(full, USDC, incoming = true, amount = "1775384")
        assertEquals("POOL", full.transaction.from)
        assertEquals("USER", full.transaction.to)
    }

    @Test
    fun map_swapBetweenTwoMints_recordsBothLegsWithCounterpartyPerDirection() {
        val result = tokenTransactionResult(
            accountKeys = listOf("USER", "USER_USDC", "POOL_A_USDC", "USER_USDT", "POOL_B_USDT"),
            preTokenBalances = listOf(
                tokenBalance(USDC, amount = "100", owner = "USER", accountIndex = 1),
                tokenBalance(USDC, amount = "1000", owner = "POOL_A", accountIndex = 2),
                tokenBalance(USDT, amount = "500", owner = "POOL_B", accountIndex = 4),
            ),
            postTokenBalances = listOf(
                tokenBalance(USDC, amount = "40", owner = "USER", accountIndex = 1),
                tokenBalance(USDC, amount = "1060", owner = "POOL_A", accountIndex = 2),
                tokenBalance(USDT, amount = "55", owner = "USER", accountIndex = 3),
                tokenBalance(USDT, amount = "445", owner = "POOL_B", accountIndex = 4),
            ),
        )

        val full = mapSingle(result)

        val transfers = full.tokenTransfers.associate { it.tokenTransfer.mintAddress to it.tokenTransfer }
        assertEquals(setOf(USDC, USDT), transfers.keys)
        assertFalse(transfers.getValue(USDC).incoming)
        assertEquals(0, BigDecimal(60).compareTo(transfers.getValue(USDC).amount))
        assertTrue(transfers.getValue(USDT).incoming)
        assertEquals(0, BigDecimal(55).compareTo(transfers.getValue(USDT).amount))
        assertEquals("POOL_B", full.transaction.from)
        assertEquals("POOL_A", full.transaction.to)
    }

    @Test
    fun map_tokenBalancesWithoutOwner_parsesSolTransferInstead() {
        val result = solTransferToUser(
            preTokenBalances = listOf(tokenBalance(USDC, amount = "100", owner = null, accountIndex = 2)),
            postTokenBalances = listOf(tokenBalance(USDC, amount = "40", owner = null, accountIndex = 2)),
        )

        val full = mapSingle(result)

        assertTrue(full.tokenTransfers.isEmpty())
        assertEquals(0, BigDecimal(SOL_TRANSFER_LAMPORTS).compareTo(full.transaction.amount))
        assertEquals("USER", full.transaction.to)
    }

    @Test
    fun map_tokenBalancesOfForeignAccountsOnly_keepsSolTransferToUser() {
        val result = solTransferToUser(
            preTokenBalances = listOf(tokenBalance(USDC, amount = "100", owner = "OTHER", accountIndex = 2)),
            postTokenBalances = listOf(tokenBalance(USDC, amount = "40", owner = "OTHER", accountIndex = 2)),
        )

        val full = mapSingle(result)

        assertTrue(full.tokenTransfers.isEmpty())
        assertEquals(0, BigDecimal(SOL_TRANSFER_LAMPORTS).compareTo(full.transaction.amount))
        assertEquals("SENDER", full.transaction.from)
        assertEquals("USER", full.transaction.to)
    }

    @Test
    fun userMints_userAndForeignEntries_returnsMintsOfUserEntriesFromPreAndPost() {
        val closedAccount = tokenTransactionResult(
            accountKeys = listOf("USER", "USER_ATA"),
            preTokenBalances = listOf(tokenBalance(USDC, amount = "5", owner = "USER", accountIndex = 1)),
            postTokenBalances = emptyList(),
        )
        val createdAccount = tokenTransactionResult(
            accountKeys = listOf("USER", "USER_ATA", "OTHER_ATA"),
            preTokenBalances = listOf(tokenBalance(FOREIGN_MINT, amount = "9", owner = "OTHER", accountIndex = 2)),
            postTokenBalances = listOf(
                tokenBalance(USDT, amount = "7", owner = "USER", accountIndex = 1),
                tokenBalance(FOREIGN_MINT, amount = "2", owner = "OTHER", accountIndex = 2),
            ),
        )

        val mints = SolanaTransactionMapper.userMints("USER", listOf(closedAccount, createdAccount))

        assertEquals(setOf(USDC, USDT), mints)
    }

    @Test
    fun hashesWithoutUserTokenChanges_zeroNetSelfTransferAndOwnedChange_returnsOnlySelfTransfer() {
        val selfTransfer = tokenTransfer(
            signature = "selfSig",
            pre = listOf(tokenBalance(USDC, "10", "USER", 1), tokenBalance(USDC, "0", "USER", 2)),
            post = listOf(tokenBalance(USDC, "0", "USER", 1), tokenBalance(USDC, "10", "USER", 2)),
        )
        val ownedChange = tokenTransfer(
            signature = "ownedSig",
            pre = listOf(tokenBalance(USDC, "10", "USER", 1)),
            post = listOf(tokenBalance(USDC, "4", "USER", 1)),
        )

        val hashes = SolanaTransactionMapper.hashesWithoutUserTokenChanges("USER", listOf(selfTransfer, ownedChange))

        assertEquals(setOf("selfSig"), hashes)
    }

    @Test
    fun hashesWithoutUserTokenChanges_resultWithoutMeta_returnsEmpty() {
        val withoutMeta = tokenTransfer(signature = "noMetaSig", pre = emptyList(), post = emptyList()).copy(meta = null)

        val hashes = SolanaTransactionMapper.hashesWithoutUserTokenChanges("USER", listOf(withoutMeta))

        assertTrue(hashes.isEmpty())
    }

    private fun tokenTransfer(signature: String, pre: List<TokenBalance>, post: List<TokenBalance>) = transactionResult(
        signature = signature,
        accountKeys = listOf("USER", "USER_ATA_1", "USER_ATA_2"),
        instructions = emptyList(),
        fee = 5_000L,
        preBalances = listOf(1L, 1L, 1L),
        postBalances = listOf(1L, 1L, 1L),
        preTokenBalances = pre,
        postTokenBalances = post,
    )

    @Test
    fun map_zeroAmountTokenTransferWithUserAbsentFromAccountKeys_keepsSolAmountNull() {
        val accountKeys = listOf("FEE_PAYER", "SENDER_ATA", "USER_ATA", USDC, WellKnownPrograms.TOKEN_PROGRAM)
        val result = transactionResult(
            signature = "spamSig",
            accountKeys = accountKeys,
            instructions = emptyList(),
            fee = 5_000L,
            preBalances = listOf(1_000_000_000L, 2_039_280L, 2_039_280L, 1L, 1L),
            postBalances = listOf(999_995_000L, 2_039_280L, 2_039_280L, 1L, 1L),
            preTokenBalances = listOf(tokenBalance(USDC, "10", "SENDER", 1), tokenBalance(USDC, "5", "USER", 2)),
            postTokenBalances = listOf(tokenBalance(USDC, "10", "SENDER", 1), tokenBalance(USDC, "5", "USER", 2)),
        )

        val full = mapSingle(result)

        assertNull(full.transaction.amount)
        assertTrue(full.tokenTransfers.isEmpty())
    }

    @Test
    fun map_v0SolReceiptToLoadedUserAddress_recordsIncomingAmount() {
        val result = transactionResult(
            signature = "v0Sig",
            accountKeys = listOf("SENDER", WellKnownPrograms.SYSTEM_PROGRAM),
            instructions = listOf(
                Instruction(accounts = listOf(0L, 2L), data = systemTransferData(SOL_TRANSFER_LAMPORTS), programIdIndex = 1L),
            ),
            fee = 5_000L,
            preBalances = listOf(1_000_000_000L, 1L, 0L),
            postBalances = listOf(1_000_000_000L - SOL_TRANSFER_LAMPORTS - 5_000L, 1L, SOL_TRANSFER_LAMPORTS),
            loadedAddresses = LoadedAddresses(writable = listOf("USER")),
        )

        val full = mapSingle(result)

        assertEquals(0, BigDecimal(SOL_TRANSFER_LAMPORTS).compareTo(full.transaction.amount))
        assertEquals("SENDER", full.transaction.from)
        assertEquals("USER", full.transaction.to)
    }

    @Test
    fun map_v0DustTransferToLoadedUserAddress_attributesTransferByLoadedIndex() {
        val result = transactionResult(
            signature = "v0DustSig",
            accountKeys = listOf("FUND", "BIG", WellKnownPrograms.SYSTEM_PROGRAM),
            instructions = listOf(
                Instruction(accounts = listOf(0L, 1L), data = systemTransferData(440_000_000L), programIdIndex = 2L),
                Instruction(accounts = listOf(0L, 4L), data = systemTransferData(1L), programIdIndex = 2L),
            ),
            fee = 5_000L,
            preBalances = listOf(1_000_000_000L, 500L, 1L, 7L, 4_463_687L),
            postBalances = listOf(559_994_999L, 440_000_500L, 1L, 7L, 4_463_688L),
            loadedAddresses = LoadedAddresses(writable = listOf("OTHER_LOADED"), readonly = listOf("USER")),
        )

        val full = mapSingle(result)

        assertEquals(0, BigDecimal.ONE.compareTo(full.transaction.amount))
        assertEquals("FUND", full.transaction.from)
        assertEquals("USER", full.transaction.to)
    }

    private fun mapSingle(result: TransactionResult): FullTransaction =
        SolanaTransactionMapper.map(
            userAddress = "USER",
            rpcTransactions = listOf(result),
            mintAccounts = mapOf(USDC to MintAccount(USDC, decimals = 6), USDT to MintAccount(USDT, decimals = 6)),
        ).single()

    private fun assertSingleTokenTransfer(full: FullTransaction, mint: String, incoming: Boolean, amount: String) {
        assertNull(full.transaction.amount)
        val tokenTransfer = full.tokenTransfers.single().tokenTransfer
        assertEquals(mint, tokenTransfer.mintAddress)
        assertEquals(incoming, tokenTransfer.incoming)
        assertEquals(0, BigDecimal(amount).compareTo(tokenTransfer.amount))
    }

    private fun tokenTransactionResult(
        accountKeys: List<String>,
        preTokenBalances: List<TokenBalance>,
        postTokenBalances: List<TokenBalance>,
    ): TransactionResult = transactionResult(
        signature = "tokenSig",
        accountKeys = accountKeys,
        instructions = emptyList(),
        fee = 5_000L,
        preBalances = accountKeys.map { 1_000_000_000L },
        postBalances = accountKeys.map { 1_000_000_000L },
        preTokenBalances = preTokenBalances,
        postTokenBalances = postTokenBalances,
    )

    private fun solTransferToUser(
        preTokenBalances: List<TokenBalance>,
        postTokenBalances: List<TokenBalance>,
    ): TransactionResult = transactionResult(
        signature = "solSig",
        accountKeys = listOf("SENDER", "USER", "FOREIGN_ATA", WellKnownPrograms.SYSTEM_PROGRAM),
        instructions = listOf(
            Instruction(accounts = listOf(0L, 1L), data = systemTransferData(SOL_TRANSFER_LAMPORTS), programIdIndex = 3L),
        ),
        fee = 5_000L,
        preBalances = listOf(1_000_000_000L, 0L, 2_039_280L, 1L),
        postBalances = listOf(1_000_000_000L - SOL_TRANSFER_LAMPORTS - 5_000L, SOL_TRANSFER_LAMPORTS, 2_039_280L, 1L),
        preTokenBalances = preTokenBalances,
        postTokenBalances = postTokenBalances,
    )

    private companion object {
        const val USDC = "USDC_MINT"
        const val USDT = "USDT_MINT"
        const val FOREIGN_MINT = "FOREIGN_MINT"
        const val SOL_TRANSFER_LAMPORTS = 1_000_000L
    }
}

internal fun transactionResult(
    signature: String,
    accountKeys: List<String>,
    instructions: List<Instruction>,
    fee: Long,
    preBalances: List<Long>,
    postBalances: List<Long>,
    preTokenBalances: List<TokenBalance> = emptyList(),
    postTokenBalances: List<TokenBalance> = emptyList(),
    loadedAddresses: LoadedAddresses? = null,
): TransactionResult {
    val message = Message(
        accountKeys = accountKeys,
        header = Header(numReadonlySignedAccounts = 0, numReadonlyUnsignedAccounts = 1, numRequiredSignatures = 1),
        instructions = instructions,
        recentBlockhash = "blockhash",
    )
    val meta = Meta(
        err = null,
        fee = fee,
        innerInstructions = emptyList(),
        preTokenBalances = preTokenBalances,
        postTokenBalances = postTokenBalances,
        postBalances = postBalances,
        preBalances = preBalances,
        status = Status(null),
        loadedAddresses = loadedAddresses,
    )
    return TransactionResult(
        blockTime = 1_700_000_000L,
        meta = meta,
        slot = 1L,
        transaction = Transaction(message = message, signatures = listOf(signature)),
    )
}

internal fun tokenBalance(mint: String, amount: String, owner: String?, accountIndex: Int): TokenBalance =
    TokenBalance(
        accountIndex = accountIndex.toDouble(),
        mint = mint,
        uiTokenAmount = TokenAmountInfo(amount = amount, decimals = 6, uiAmount = null, uiAmountString = ""),
        owner = owner,
    )
