package io.horizontalsystems.solanakit.noderpc

import com.solana.api.Api
import com.solana.api.getBlockHeight
import com.solana.networking.HttpNetworkingRouter
import io.horizontalsystems.solanakit.database.main.MainStorage
import io.horizontalsystems.solanakit.models.RpcSource
import io.horizontalsystems.solanakit.network.ConnectionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ApiSyncerTest {

    private val api = Api(HttpNetworkingRouter(RpcSource.TritonOne.endpoint))
    private val connectionManager = mockk<ConnectionManager>(relaxed = true)
    private val storage = mockk<MainStorage>(relaxed = true)
    private val listener = mockk<IApiSyncerListener>(relaxed = true)
    private val scope = CoroutineScope(Dispatchers.Default)

    @Before
    fun setUp() {
        mockkStatic("com.solana.api.GetBlockHeightKt")
        coEvery { api.getBlockHeight() } returns Result.success(newBlockHeight)
        every { connectionManager.isConnected } returns true
    }

    @After
    fun tearDown() {
        scope.cancel()
        unmockkStatic("com.solana.api.GetBlockHeightKt")
    }

    @Test
    fun sync_saveLastBlockHeightCancelled_keepsHeightAndSkipsListener() = runBlocking {
        val saveAttempted = CountDownLatch(1)
        coEvery { storage.saveLastBlockHeight(newBlockHeight) } answers {
            saveAttempted.countDown()
            throw CancellationException()
        }
        val syncer = apiSyncer()

        syncer.start(scope)

        assertTrue(saveAttempted.await(awaitSeconds, TimeUnit.SECONDS))
        assertEquals(storedBlockHeight, syncer.lastBlockHeight)
        verify(exactly = 0) { listener.didUpdateLastBlockHeight(any()) }
    }

    @Test
    fun sync_repeatedAfterCancelledSave_savesAndNotifiesSameHeight() = runBlocking {
        val saveAttempted = CountDownLatch(1)
        coEvery { storage.saveLastBlockHeight(newBlockHeight) } answers {
            saveAttempted.countDown()
            throw CancellationException()
        }
        val syncer = apiSyncer()
        syncer.start(scope)
        assertTrue(saveAttempted.await(awaitSeconds, TimeUnit.SECONDS))
        syncer.stop()

        val notified = CountDownLatch(1)
        coEvery { storage.saveLastBlockHeight(newBlockHeight) } returns Unit
        every { listener.didUpdateLastBlockHeight(newBlockHeight) } answers { notified.countDown() }
        syncer.start(scope)

        assertTrue(notified.await(awaitSeconds, TimeUnit.SECONDS))
        assertEquals(newBlockHeight, syncer.lastBlockHeight)
        coVerify(exactly = 2) { storage.saveLastBlockHeight(newBlockHeight) }
    }

    private fun apiSyncer() = ApiSyncer(
        api = api,
        syncInterval = syncIntervalSeconds,
        connectionManager = connectionManager,
        storage = storage,
        initialLastBlockHeight = storedBlockHeight
    ).apply { listener = this@ApiSyncerTest.listener }

    companion object {
        private const val storedBlockHeight = 100L
        private const val newBlockHeight = 200L
        private const val syncIntervalSeconds = 600L
        private const val awaitSeconds = 5L
    }
}
