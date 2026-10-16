package io.horizontalsystems.solanakit.database

import io.horizontalsystems.solanakit.createPlaintextDatabase
import io.horizontalsystems.solanakit.database.main.MainDatabase
import io.horizontalsystems.solanakit.database.main.MainStorage
import io.horizontalsystems.solanakit.database.transaction.TransactionDatabase
import io.horizontalsystems.solanakit.models.Transaction
import io.horizontalsystems.solanakit.openPlaintextMainDatabase
import io.horizontalsystems.solanakit.openPlaintextTransactionDatabase
import io.horizontalsystems.solanakit.userVersion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The production schema policy (migrations plus the destructive fallbacks) against real files. */
class TransactionDatabaseMigrationDesktopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val txsFile: File get() = File(tmp.root, TXS_DB_NAME)
    private val mainFile: File get() = File(tmp.root, MAIN_DB_NAME)

    @Test
    fun openTransactionDatabase_version10File_migratesRowWithExternalFalse() = runBlocking {
        createPlaintextDatabase(txsFile, 10, txsSchema(withExternal = false) + INSERT_TRANSACTION)

        val transaction = openTransactions { it.transactionsDao().get(TRANSACTION_HASH) }

        assertEquals(TRANSACTION_HASH, transaction?.hash)
        assertEquals(false, transaction?.external)
        assertEquals(11, userVersion(txsFile))
    }

    @Test
    fun openTransactionDatabase_version9File_dropsAllTables() = runBlocking {
        createPlaintextDatabase(txsFile, 9, txsSchema(withExternal = false) + INSERT_TRANSACTION)

        val transactions = openTransactions { it.transactionsDao().pendingTransactions() }

        assertEquals(emptyList<Transaction>(), transactions)
        assertEquals(11, userVersion(txsFile))
    }

    @Test
    fun openTransactionDatabase_version12File_fallsBackOnDowngrade() = runBlocking {
        createPlaintextDatabase(txsFile, 12, txsSchema(withExternal = true) + INSERT_TRANSACTION)

        val transaction = openTransactions { it.transactionsDao().get(TRANSACTION_HASH) }

        assertNull(transaction)
        assertEquals(11, userVersion(txsFile))
    }

    @Test
    fun openMainDatabase_version4File_dropsAllTables() = runBlocking {
        createPlaintextDatabase(mainFile, 4, MAIN_SCHEMA + INSERT_BALANCE)

        val balance = openMain { MainStorage(it).getBalance() }

        assertNull(balance)
        assertEquals(5, userVersion(mainFile))
    }

    @Test
    fun openMainDatabase_version5File_keepsRows() = runBlocking {
        openMain { MainStorage(it).saveBalance(BALANCE_LAMPORTS) }

        val balance = openMain { MainStorage(it).getBalance() }

        assertEquals(BALANCE_LAMPORTS, balance)
        assertEquals(5, userVersion(mainFile))
    }

    private suspend fun <T> openTransactions(block: suspend (TransactionDatabase) -> T): T {
        val database = openPlaintextTransactionDatabase(txsFile)
        try {
            return block(database)
        } finally {
            database.close()
        }
    }

    private suspend fun <T> openMain(block: suspend (MainDatabase) -> T): T {
        val database = openPlaintextMainDatabase(mainFile)
        try {
            return block(database)
        } finally {
            database.close()
        }
    }

    // Taken from schemas/…TransactionDatabase/11.json; version 10 is the same without `external`.
    private fun txsSchema(withExternal: Boolean) = listOf(
        "CREATE TABLE IF NOT EXISTS `LastSyncedTransaction` (`syncSourceName` TEXT NOT NULL, `hash` TEXT NOT NULL, PRIMARY KEY(`syncSourceName`))",
        "CREATE TABLE IF NOT EXISTS `MintAccount` (`address` TEXT NOT NULL, `decimals` INTEGER NOT NULL, `supply` INTEGER, `isNft` INTEGER NOT NULL, `name` TEXT, `symbol` TEXT, `uri` TEXT, `collectionAddress` TEXT, PRIMARY KEY(`address`))",
        "CREATE TABLE IF NOT EXISTS `TokenTransfer` (`transactionHash` TEXT NOT NULL, `mintAddress` TEXT NOT NULL, `incoming` INTEGER NOT NULL, `amount` TEXT NOT NULL, `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, FOREIGN KEY(`transactionHash`) REFERENCES `Transaction`(`hash`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_TokenTransfer_transactionHash` ON `TokenTransfer` (`transactionHash`)",
        "CREATE TABLE IF NOT EXISTS `Transaction` (`hash` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `fee` TEXT, `from` TEXT, `to` TEXT, `amount` TEXT, `error` TEXT, `pending` INTEGER NOT NULL, `blockHash` TEXT NOT NULL, `lastValidBlockHeight` INTEGER NOT NULL, `base64Encoded` TEXT NOT NULL, `retryCount` INTEGER NOT NULL" +
            (if (withExternal) ", `external` INTEGER NOT NULL DEFAULT 0" else "") +
            ", PRIMARY KEY(`hash`))",
        "CREATE TABLE IF NOT EXISTS `TokenAccount` (`address` TEXT NOT NULL, `mintAddress` TEXT NOT NULL, `balance` TEXT NOT NULL, `decimals` INTEGER NOT NULL, PRIMARY KEY(`address`))",
    )

    private companion object {
        const val MAIN_DB_NAME = "Solana-policy-main"
        const val TXS_DB_NAME = "Solana-policy-txs"
        const val TRANSACTION_HASH = "policyTransactionHash11111111111111111111111"
        const val BALANCE_LAMPORTS = 123L

        val INSERT_TRANSACTION = listOf(
            "INSERT INTO `Transaction` (`hash`, `timestamp`, `pending`, `blockHash`, `lastValidBlockHeight`, `base64Encoded`, `retryCount`) " +
                "VALUES ('$TRANSACTION_HASH', 1700000000, 1, 'blockHash', 5, 'base64', 0)"
        )

        // Taken from schemas/…MainDatabase/5.json.
        val MAIN_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `BalanceEntity` (`lamports` INTEGER NOT NULL, `id` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `LastBlockHeightEntity` (`height` INTEGER NOT NULL, `id` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `InitialSyncEntity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `initial` INTEGER NOT NULL)",
        )

        val INSERT_BALANCE = listOf(
            "INSERT INTO `BalanceEntity` (`lamports`, `id`) VALUES ($BALANCE_LAMPORTS, 'balance')"
        )
    }
}
