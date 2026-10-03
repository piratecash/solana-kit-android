package io.horizontalsystems.solanakit.database.main

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import io.horizontalsystems.solanakit.PlatformContext
import io.horizontalsystems.solanakit.database.mainDatabaseBuilder
import io.horizontalsystems.solanakit.database.requireValidDatabaseKey
import io.horizontalsystems.solanakit.database.requireValidDatabaseName
import io.horizontalsystems.solanakit.database.main.dao.BalanceDao
import io.horizontalsystems.solanakit.database.main.dao.InitialSyncDao
import io.horizontalsystems.solanakit.database.main.dao.LastBlockHeightDao
import io.horizontalsystems.solanakit.database.transaction.RoomTypeConverters
import io.horizontalsystems.solanakit.models.BalanceEntity
import io.horizontalsystems.solanakit.models.InitialSyncEntity
import io.horizontalsystems.solanakit.models.LastBlockHeightEntity

/** Schema handling shared by production and tests: pre-5 databases are dropped, downgrades are kept. */
internal fun RoomDatabase.Builder<MainDatabase>.mainSchemaPolicy(): RoomDatabase.Builder<MainDatabase> =
    fallbackToDestructiveMigrationFrom(dropAllTables = true, 1, 2, 3, 4)
        .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = false)

@Database(
    entities = [
        BalanceEntity::class,
        LastBlockHeightEntity::class,
        InitialSyncEntity::class,
    ],
    version = 5, exportSchema = true
)
@TypeConverters(RoomTypeConverters::class)
abstract class MainDatabase : RoomDatabase() {

    abstract fun balanceDao(): BalanceDao
    abstract fun lastBlockHeightDao(): LastBlockHeightDao
    abstract fun initialSyncDao(): InitialSyncDao

    companion object {

        /**
         * Opens the encrypted database [databaseName] with [databaseKey] (exactly 32 bytes); both are
         * checked before any I/O, otherwise [IllegalArgumentException] is thrown.
         *
         * `SolanaKit.migrateDatabase` must have run for this wallet with the same key. Failures:
         * - `DatabaseMigrationRequiredException` or `DatabaseMigrationInProgressException`: run the migration;
         * - `DatabaseKeyMismatchException`: the file is kept; only `SolanaKit.clear` plus a new key recovers,
         *   losing the stored data.
         */
        fun getInstance(context: PlatformContext, databaseName: String, databaseKey: ByteArray): MainDatabase {
            requireValidDatabaseName(databaseName)
            requireValidDatabaseKey(databaseKey)
            return mainDatabaseBuilder(context, databaseName, databaseKey)
                .mainSchemaPolicy()
                .build()
        }

    }

}
