package io.horizontalsystems.solanakit.database.main

import io.horizontalsystems.solanakit.models.BalanceEntity
import io.horizontalsystems.solanakit.models.InitialSyncEntity
import io.horizontalsystems.solanakit.models.LastBlockHeightEntity

class MainStorage(
    private val database: MainDatabase
) {

    suspend fun getLastBlockHeight(): Long? {
        return database.lastBlockHeightDao().getLastBlockHeight()?.height
    }

    suspend fun saveLastBlockHeight(lastBlockHeight: Long) {
        database.lastBlockHeightDao().insert(LastBlockHeightEntity(lastBlockHeight))
    }

    suspend fun saveBalance(balance: Long) {
        database.balanceDao().insert(BalanceEntity(balance))
    }

    suspend fun getBalance(): Long? {
        return database.balanceDao().getBalance()?.lamports
    }

    suspend fun saveInitialSync() {
        database.initialSyncDao().insert(InitialSyncEntity(initial = true))
    }

    suspend fun isInitialSync(): Boolean {
        return database.initialSyncDao().getAllEntities().isEmpty()
    }

}
