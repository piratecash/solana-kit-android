package io.horizontalsystems.solanakit.database.transaction.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.horizontalsystems.solanakit.models.LastSyncedTransaction

@Dao
interface TransactionSyncerStateDao {

    @Query("SELECT * FROM LastSyncedTransaction WHERE syncSourceName = :syncSourceName LIMIT 1")
    suspend fun get(syncSourceName: String) : LastSyncedTransaction?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(transactionSyncerState: LastSyncedTransaction)

    @Query("SELECT syncSourceName FROM LastSyncedTransaction WHERE substr(syncSourceName, 1, length(:prefix)) = :prefix")
    suspend fun namesWithPrefix(prefix: String): List<String>

}
