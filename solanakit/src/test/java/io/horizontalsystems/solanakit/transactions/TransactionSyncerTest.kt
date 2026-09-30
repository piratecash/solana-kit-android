package io.horizontalsystems.solanakit.transactions

import com.solana.core.PublicKey
import io.horizontalsystems.solanakit.SolanaKit
import io.horizontalsystems.solanakit.database.transaction.TransactionStorage
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Test

class TransactionSyncerTest {

    private val storage = mockk<TransactionStorage>()
    private val pendingTransactionSyncer = mockk<PendingTransactionSyncer>(relaxed = true)

    private val syncer = TransactionSyncer(
        publicKey = PublicKey("11111111111111111111111111111111"),
        rpcClient = mockk(),
        storage = storage,
        transactionManager = mockk(),
        pendingTransactionSyncer = pendingTransactionSyncer,
        limitFirstTimeTransactionCount = -1,
        limitTimeTransactionCount = -1
    )

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

    private suspend fun syncIgnoringCancellation() {
        try {
            syncer.sync()
        } catch (_: CancellationException) {
        }
    }
}
