package io.horizontalsystems.solanakit.database.transaction

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import io.horizontalsystems.solanakit.PlatformContext
import io.horizontalsystems.solanakit.database.requireValidDatabaseKey
import io.horizontalsystems.solanakit.database.requireValidDatabaseName
import io.horizontalsystems.solanakit.database.transactionDatabaseBuilder
import io.horizontalsystems.solanakit.database.transaction.dao.MintAccountDao
import io.horizontalsystems.solanakit.database.transaction.dao.TokenAccountDao
import io.horizontalsystems.solanakit.database.transaction.dao.TransactionSyncerStateDao
import io.horizontalsystems.solanakit.database.transaction.dao.TransactionsDao
import io.horizontalsystems.solanakit.models.*

internal val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `Transaction` ADD COLUMN `external` INTEGER NOT NULL DEFAULT 0")
    }
}

/** Schema handling shared by production and tests: pre-10 databases are dropped, downgrades are kept. */
internal fun RoomDatabase.Builder<TransactionDatabase>.transactionSchemaPolicy(): RoomDatabase.Builder<TransactionDatabase> =
    addMigrations(MIGRATION_10_11)
        .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = false)

@Database(
    entities = [
        LastSyncedTransaction::class,
        MintAccount::class,
        TokenTransfer::class,
        Transaction::class,
        TokenAccount::class
    ],
    version = 11,
    exportSchema = true
)
@TypeConverters(RoomTypeConverters::class)
abstract class TransactionDatabase : RoomDatabase() {

    abstract fun transactionSyncerStateDao(): TransactionSyncerStateDao
    abstract fun transactionsDao(): TransactionsDao
    abstract fun mintAccountDao(): MintAccountDao
    abstract fun tokenAccountsDao(): TokenAccountDao

    companion object {

        /** Same database contract as [io.horizontalsystems.solanakit.database.main.MainDatabase.getInstance]. */
        fun getInstance(
            context: PlatformContext,
            databaseName: String,
            databaseKey: ByteArray,
        ): TransactionDatabase {
            requireValidDatabaseName(databaseName)
            requireValidDatabaseKey(databaseKey)
            return transactionDatabaseBuilder(context, databaseName, databaseKey)
                .transactionSchemaPolicy()
                .build()
        }

    }

}
