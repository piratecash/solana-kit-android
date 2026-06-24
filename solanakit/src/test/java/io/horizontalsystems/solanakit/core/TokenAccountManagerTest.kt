package io.horizontalsystems.solanakit.core

import com.solana.api.Api
import getParsedTokenAccountsByOwner
import io.horizontalsystems.solanakit.SolanaKit
import io.horizontalsystems.solanakit.database.main.MainStorage
import io.horizontalsystems.solanakit.database.transaction.TransactionStorage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TokenAccountManagerTest {

    private val rpcClient = mockk<Api>()
    private val storage = mockk<TransactionStorage>(relaxed = true)
    private val mainStorage = mockk<MainStorage>(relaxed = true)

    @Before
    fun setUp() {
        mockkStatic("GetTokenAccountsByOwnerKt")
    }

    @After
    fun tearDown() {
        unmockkStatic("GetTokenAccountsByOwnerKt")
    }

    @Test
    fun sync_initialDiscoveryReturnsEmpty_savesInitialSync() = runBlocking {
        every { mainStorage.isInitialSync() } returns true
        every { storage.getTokenAccounts() } returns emptyList()
        coEvery { rpcClient.getParsedTokenAccountsByOwner(any()) } returns Result.success(emptyList())

        val manager = TokenAccountManager(
            walletAddress = "5sRHUTn6ShZBpDyHMxfgCHvMVHLoeZWoeaYsqMVV3ssc",
            rpcClient = rpcClient,
            storage = storage,
            mainStorage = mainStorage
        )

        manager.sync()

        verify(exactly = 1) { mainStorage.saveInitialSync() }
        assertTrue(manager.syncState is SolanaKit.SyncState.Synced)
    }
}
