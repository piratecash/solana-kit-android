package io.horizontalsystems.solanakit.core

import com.solana.api.Api
import com.solana.api.getBalance
import com.solana.core.PublicKey
import io.horizontalsystems.solanakit.database.main.MainStorage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.Runs
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class BalanceManagerTest {

    private val publicKey = PublicKey("5sRHUTn6ShZBpDyHMxfgCHvMVHLoeZWoeaYsqMVV3ssc")
    private val rpcClient = mockk<Api>()
    private val storage = mockk<MainStorage>(relaxed = true)
    private val listener = mockk<IBalanceListener>(relaxed = true)

    @Before
    fun setUp() {
        mockkStatic("com.solana.api.GetBalanceKt")
        coEvery { rpcClient.getBalance(publicKey) } returns Result.success(newBalance)
    }

    @After
    fun tearDown() {
        unmockkStatic("com.solana.api.GetBalanceKt")
    }

    @Test
    fun sync_saveBalanceCancelled_keepsBalanceAndSkipsListener() = runBlocking {
        coEvery { storage.saveBalance(newBalance) } throws CancellationException()
        val manager = balanceManager()

        manager.sync()

        assertEquals(storedBalance, manager.balance)
        verify(exactly = 0) { listener.onUpdateBalance(any()) }
    }

    @Test
    fun sync_repeatedAfterCancelledSave_savesAndNotifiesSameBalance() = runBlocking {
        coEvery { storage.saveBalance(newBalance) } throws CancellationException()
        val manager = balanceManager()
        manager.sync()

        coEvery { storage.saveBalance(newBalance) } just Runs
        manager.sync()

        assertEquals(newBalance, manager.balance)
        coVerify(exactly = 2) { storage.saveBalance(newBalance) }
        verify(exactly = 1) { listener.onUpdateBalance(newBalance) }
    }

    private fun balanceManager() = BalanceManager(
        publicKey = publicKey,
        rpcClient = rpcClient,
        storage = storage,
        initialBalance = storedBalance
    ).apply { listener = this@BalanceManagerTest.listener }

    companion object {
        private const val storedBalance = 100L
        private const val newBalance = 200L
    }
}
