package io.horizontalsystems.solanakit.transactions

import com.solana.actions.Action
import com.solana.api.Api
import io.horizontalsystems.solanakit.models.Address
import io.horizontalsystems.solanakit.models.FullTokenAccount
import io.horizontalsystems.solanakit.models.FullTokenTransfer
import io.horizontalsystems.solanakit.models.FullTransaction
import io.horizontalsystems.solanakit.models.MintAccount
import io.horizontalsystems.solanakit.models.TokenAccount
import io.horizontalsystems.solanakit.models.TokenTransfer
import io.horizontalsystems.solanakit.models.Transaction
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.sol4k.Base58
import java.math.BigDecimal

class TransactionManagerTest {

    private val api = mockk<Api>()
    private val owners = mutableMapOf<String, String?>()
    private val ownerRequests = mutableListOf<List<String>>()

    private val manager = TransactionManager(
        address = Address("11111111111111111111111111111111"),
        storage = mockk(),
        rpcAction = mockk<Action> { every { api } returns this@TransactionManagerTest.api },
        tokenAccountManager = mockk(),
        router = mockk(),
    )

    @Before
    fun setUp() {
        mockkStatic("io.horizontalsystems.solanakit.transactions.ExtensionsKt")
        coEvery { api.getAccountOwners(any()) } answers {
            val addresses = secondArg<List<String>>()
            ownerRequests += addresses
            Result.success(addresses.associateWith { owners[it] })
        }
    }

    @After
    fun tearDown() {
        unmockkStatic("io.horizontalsystems.solanakit.transactions.ExtensionsKt")
    }

    @Test
    fun splTransfer_token2022SourceAndMissingRecipientAccount_createsRecipientAccountUnderToken2022() = runBlocking {
        owners[SOURCE] = WellKnownPrograms.TOKEN_2022_PROGRAM

        val transfer = manager.splTransfer(sourceAccount(), RECIPIENT)

        assertEquals(WellKnownPrograms.TOKEN_2022_PROGRAM, transfer.programId.toBase58())
        assertEquals(recipientAta(WellKnownPrograms.TOKEN_2022_PROGRAM), transfer.destination.toBase58())
        assertTrue(transfer.createDestination)
        assertEquals(1, ownerRequests.size)
    }

    @Test
    fun splTransfer_legacySourceAndExistingRecipientAccount_transfersWithoutCreate() = runBlocking {
        owners[SOURCE] = WellKnownPrograms.TOKEN_PROGRAM
        owners[recipientAta(WellKnownPrograms.TOKEN_PROGRAM)] = WellKnownPrograms.TOKEN_PROGRAM

        val transfer = manager.splTransfer(sourceAccount(), RECIPIENT)

        assertEquals(WellKnownPrograms.TOKEN_PROGRAM, transfer.programId.toBase58())
        assertEquals(recipientAta(WellKnownPrograms.TOKEN_PROGRAM), transfer.destination.toBase58())
        assertFalse(transfer.createDestination)
    }

    @Test
    fun splTransfer_recipientAccountFundedWithSol_createsRecipientAccount() = runBlocking {
        owners[SOURCE] = WellKnownPrograms.TOKEN_2022_PROGRAM
        owners[recipientAta(WellKnownPrograms.TOKEN_2022_PROGRAM)] = WellKnownPrograms.SYSTEM_PROGRAM

        assertTrue(manager.splTransfer(sourceAccount(), RECIPIENT).createDestination)
    }

    @Test
    fun splTransfer_sourceAccountMissing_throws() {
        assertThrows(Exception::class.java) { runBlocking { manager.splTransfer(sourceAccount(), RECIPIENT) } }
    }

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

    private fun sourceAccount() = FullTokenAccount(
        TokenAccount(SOURCE, TOKEN_MINT, BigDecimal.TEN, decimals = 6),
        MintAccount(TOKEN_MINT, decimals = 6),
    )

    private fun recipientAta(programId: String) = associatedTokenAddress(RECIPIENT, TOKEN_MINT, programId)

    private companion object {
        const val USDC = "USDC_MINT"
        const val USDT = "USDT_MINT"
        val TOKEN_MINT: String = Base58.encode(ByteArray(32) { 5 })
        val SOURCE: String = Base58.encode(ByteArray(32) { 6 })
        val RECIPIENT: String = Base58.encode(ByteArray(32) { 7 })
    }
}
