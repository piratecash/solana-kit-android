package io.horizontalsystems.solanakit.core

import com.solana.api.Api
import com.solana.api.TokenAmountInfoResponse
import com.solana.models.buffer.AccountInfoData
import getTokenAccountBalanceWithRepeat
import io.horizontalsystems.solanakit.SolanaKit
import io.horizontalsystems.solanakit.database.main.MainStorage
import io.horizontalsystems.solanakit.database.transaction.TransactionStorage
import io.horizontalsystems.solanakit.models.AccountInfoFixed
import io.horizontalsystems.solanakit.models.FullTokenAccount
import io.horizontalsystems.solanakit.models.MintAccount
import io.horizontalsystems.solanakit.models.TokenAccount
import io.horizontalsystems.solanakit.transactions.getMultipleAccountsFixed
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.reactivex.Single
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

class TokenAccountManagerMirrorTest {

    private val rpcClient = mockk<Api>()
    private val storage = mockk<TransactionStorage>(relaxed = true)
    private val mainStorage = mockk<MainStorage>(relaxed = true)

    private val mintAccount = MintAccount(mintAddress, decimals)
    private val tokenAccount = TokenAccount(tokenAccountAddress, mintAddress, BigDecimal.ZERO, decimals)

    // Cancellation tests need a scope the test body can outlive, so the cancelled work keeps running.
    private val scope = CoroutineScope(Dispatchers.Default)

    @Before
    fun setUp() {
        mockkStatic("GetTokenAccountBalanceKt")
        mockkStatic("io.horizontalsystems.solanakit.transactions.ExtensionsKt")
        coEvery { mainStorage.isInitialSync() } returns false
    }

    @After
    fun tearDown() {
        scope.cancel()
        unmockkStatic("GetTokenAccountBalanceKt")
        unmockkStatic("io.horizontalsystems.solanakit.transactions.ExtensionsKt")
    }

    @Test
    fun fullTokenAccount_afterReload_returnsStoredAccountWithoutNetwork() = runBlocking {
        coEvery { storage.getFullTokenAccounts() } returns listOf(FullTokenAccount(tokenAccount, mintAccount))
        val manager = tokenAccountManager()

        manager.reloadFullTokenAccounts()

        assertEquals(tokenAccountAddress, manager.fullTokenAccount(mintAddress)?.tokenAccount?.address)
        assertNull(manager.fullTokenAccount(unknownMintAddress))
    }

    @Test
    fun addTokenAccount_newMint_isVisibleInMirrorAfterReturn() = runBlocking {
        coEvery { storage.getFullTokenAccounts() } returns emptyList()
        val manager = tokenAccountManager()
        manager.reloadFullTokenAccounts()
        coEvery { storage.getFullTokenAccounts() } returns listOf(FullTokenAccount(tokenAccount, mintAccount))

        manager.addTokenAccount(walletAddress, mintAddress, decimals)

        assertEquals(mintAddress, manager.fullTokenAccount(mintAddress)?.mintAccount?.address)
    }

    @Test
    fun sync_newBalance_mirrorReturnsNewBalance() = runBlocking {
        coEvery { storage.getTokenAccounts() } returns listOf(tokenAccount)
        every {
            rpcClient.getMultipleAccountsFixed<AccountInfoData>(any(), any())
        } returns Single.just(listOf(mockk<AccountInfoFixed<AccountInfoData>>()))
        coEvery { rpcClient.getTokenAccountBalanceWithRepeat(any()) } returns Result.success(
            mockk<TokenAmountInfoResponse> { every { amount } returns syncedBalance }
        )
        coEvery { storage.getFullTokenAccounts() } returns listOf(
            FullTokenAccount(
                TokenAccount(tokenAccountAddress, mintAddress, BigDecimal(syncedBalance), decimals),
                mintAccount
            )
        )
        val manager = tokenAccountManager()

        manager.sync()

        assertEquals(
            BigDecimal(syncedBalance),
            manager.fullTokenAccount(mintAddress)?.tokenAccount?.balance
        )
    }

    @Test
    fun sync_cancelledDuringMirrorReload_publishesPersistedBalanceAnyway() = runBlocking {
        stubBalanceSync()
        val reloadStarted = CompletableDeferred<Unit>()
        val storedAccounts = CompletableDeferred<List<FullTokenAccount>>()
        stubSuspendingReload(reloadStarted, storedAccounts)
        val manager = tokenAccountManager()

        val job = scope.launch { manager.sync() }
        reloadStarted.await()
        job.cancel()
        storedAccounts.complete(persistedAccounts())
        job.join()

        coVerify { storage.saveTokenAccounts(any()) }
        assertEquals(
            BigDecimal(syncedBalance),
            manager.fullTokenAccount(mintAddress)?.tokenAccount?.balance
        )
        assertEquals(
            BigDecimal(syncedBalance),
            manager.tokenAccountsFlow.value.single().tokenAccount.balance
        )
    }

    @Test
    fun sync_stoppedDuringMirrorReload_keepsNotSyncedAndPersistedBalance() = runBlocking {
        stubBalanceSync()
        val reloadStarted = CompletableDeferred<Unit>()
        val storedAccounts = CompletableDeferred<List<FullTokenAccount>>()
        stubSuspendingReload(reloadStarted, storedAccounts)
        val manager = tokenAccountManager()

        val job = scope.launch { manager.sync() }
        reloadStarted.await()
        // What kit.stop() does: report the stopped state, then cancel the scope running the sync.
        manager.stop()
        job.cancel()
        storedAccounts.complete(persistedAccounts())
        job.join()

        assertTrue("syncState: ${manager.syncState}", manager.syncState is SolanaKit.SyncState.NotSynced)
        assertEquals(
            BigDecimal(syncedBalance),
            manager.fullTokenAccount(mintAddress)?.tokenAccount?.balance
        )
    }

    @Test
    fun addTokenAccount_cancelledDuringMirrorReload_publishesPersistedAccountAnyway() = runBlocking {
        val reloadStarted = CompletableDeferred<Unit>()
        val storedAccounts = CompletableDeferred<List<FullTokenAccount>>()
        coEvery { storage.getFullTokenAccounts() } coAnswers {
            reloadStarted.complete(Unit)
            storedAccounts.await()
        }
        val manager = tokenAccountManager()

        val job = scope.launch { manager.addTokenAccount(walletAddress, mintAddress, decimals) }
        reloadStarted.await()
        job.cancel()
        storedAccounts.complete(listOf(FullTokenAccount(tokenAccount, mintAccount)))
        job.join()

        coVerify { storage.addTokenAccountIfMissing(any(), any()) }
        assertEquals(mintAddress, manager.fullTokenAccount(mintAddress)?.mintAccount?.address)
    }

    @Test
    fun tokenAccountsFlow_reloadWithoutBalanceUpdate_staysEmpty() = runBlocking {
        coEvery { storage.getFullTokenAccounts() } returns listOf(FullTokenAccount(tokenAccount, mintAccount))
        val manager = tokenAccountManager()

        manager.reloadFullTokenAccounts()

        assertTrue(manager.tokenAccountsFlow.value.isEmpty())
    }

    private fun stubBalanceSync() {
        coEvery { storage.getTokenAccounts() } returns listOf(tokenAccount)
        every {
            rpcClient.getMultipleAccountsFixed<AccountInfoData>(any(), any())
        } returns Single.just(listOf(mockk<AccountInfoFixed<AccountInfoData>>()))
        coEvery { rpcClient.getTokenAccountBalanceWithRepeat(any()) } returns Result.success(
            mockk<TokenAmountInfoResponse> { every { amount } returns syncedBalance }
        )
    }

    // Lets a test cancel the calling coroutine exactly while the post-write mirror read is suspended.
    private fun stubSuspendingReload(
        started: CompletableDeferred<Unit>,
        result: CompletableDeferred<List<FullTokenAccount>>
    ) {
        coEvery { storage.getFullTokenAccounts() } coAnswers {
            started.complete(Unit)
            result.await()
        }
    }

    private fun persistedAccounts() = listOf(
        FullTokenAccount(
            TokenAccount(tokenAccountAddress, mintAddress, BigDecimal(syncedBalance), decimals),
            mintAccount
        )
    )

    private fun tokenAccountManager() = TokenAccountManager(
        walletAddress = walletAddress,
        rpcClient = rpcClient,
        storage = storage,
        mainStorage = mainStorage
    )

    companion object {
        private const val walletAddress = "5sRHUTn6ShZBpDyHMxfgCHvMVHLoeZWoeaYsqMVV3ssc"
        private const val tokenAccountAddress = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        private const val mintAddress = "So11111111111111111111111111111111111111112"
        private const val unknownMintAddress = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"
        private const val syncedBalance = "42"
        private const val decimals = 6
    }
}
