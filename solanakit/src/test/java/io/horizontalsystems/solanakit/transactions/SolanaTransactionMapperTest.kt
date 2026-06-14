package io.horizontalsystems.solanakit.transactions

import com.solana.api.Header
import com.solana.api.Instruction
import com.solana.api.Message
import com.solana.api.Meta
import com.solana.api.Status
import com.solana.api.TokenAmountInfo
import com.solana.api.TokenBalance
import com.solana.api.Transaction
import io.horizontalsystems.solanakit.models.MintAccount
import org.junit.Assert.assertEquals
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
            preTokenBalances = listOf(tokenBalance(mint, amount = "100")),
            postTokenBalances = listOf(tokenBalance(mint, amount = "40")),
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
    }

    private fun transactionResult(
        signature: String,
        accountKeys: List<String>,
        instructions: List<Instruction>,
        fee: Long,
        preBalances: List<Long>,
        postBalances: List<Long>,
        preTokenBalances: List<TokenBalance> = emptyList(),
        postTokenBalances: List<TokenBalance> = emptyList(),
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
        )
        return TransactionResult(
            blockTime = 1_700_000_000L,
            meta = meta,
            slot = 1L,
            transaction = Transaction(message = message, signatures = listOf(signature)),
        )
    }

    private fun tokenBalance(mint: String, amount: String): TokenBalance =
        TokenBalance(
            accountIndex = 1.0,
            mint = mint,
            uiTokenAmount = TokenAmountInfo(amount = amount, decimals = 6, uiAmount = null, uiAmountString = ""),
        )
}
