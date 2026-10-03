package io.horizontalsystems.solanakit.transactions

import io.horizontalsystems.solanakit.models.Address
import io.horizontalsystems.solanakit.models.FullTokenTransfer
import io.horizontalsystems.solanakit.models.FullTransaction
import io.horizontalsystems.solanakit.models.MintAccount
import io.horizontalsystems.solanakit.models.TokenTransfer
import io.horizontalsystems.solanakit.models.Transaction
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class TransactionManagerTest {

    private val manager = TransactionManager(
        address = Address("11111111111111111111111111111111"),
        storage = mockk(),
        rpcAction = mockk(),
        tokenAccountManager = mockk(),
        rpcUrl = "http://localhost",
    )

    @Test
    fun splTransactionsFlow_transactionWithOtherMintFirst_emitsTransaction() = runBlocking {
        val swap = FullTransaction(
            Transaction(hash = "swapSig", timestamp = 1_700_000_000, pending = false),
            listOf(tokenTransfer("swapSig", USDC, incoming = false), tokenTransfer("swapSig", USDT, incoming = true)),
        )

        manager.notifyTransactionsUpdate(listOf(swap))
        val emitted = withTimeoutOrNull(1_000) { manager.splTransactionsFlow(USDT, incoming = true).first() }

        assertEquals(listOf(swap), emitted)
    }

    private fun tokenTransfer(hash: String, mint: String, incoming: Boolean) = FullTokenTransfer(
        TokenTransfer(hash, mint, incoming, BigDecimal.ONE),
        MintAccount(mint, decimals = 6),
    )

    private companion object {
        const val USDC = "USDC_MINT"
        const val USDT = "USDT_MINT"
    }
}
