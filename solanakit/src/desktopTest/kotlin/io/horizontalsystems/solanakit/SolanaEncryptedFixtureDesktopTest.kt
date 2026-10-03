package io.horizontalsystems.solanakit

import io.horizontalsystems.solanakit.database.main.MainDatabase
import io.horizontalsystems.solanakit.database.mainDatabaseName
import io.horizontalsystems.solanakit.database.transaction.TransactionDatabase
import io.horizontalsystems.solanakit.database.transactionDatabaseName
import io.horizontalsystems.sqlcipher.SqlCipherDriver
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The migrated wallet keeps every row of both fixtures, and the schema versions the engine carried over. */
class SolanaEncryptedFixtureDesktopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val key = ByteArray(32) { (it * 7 + 1).toByte() }

    @Test
    fun migrateDatabase_room272Fixtures_keepsEveryRowUnderTheKey() = runBlocking {
        val context = PlatformContext(tmp.root)
        val mainFile = SolanaV5V11Fixture.copyMainDbTo(File(tmp.root, mainDatabaseName(WALLET_ID)))
        val txsFile = SolanaV5V11Fixture.copyTxsDbTo(File(tmp.root, transactionDatabaseName(WALLET_ID)))

        val result = SolanaKit.migrateDatabase(context, WALLET_ID, key)

        assertEquals(DatabaseMigrationResult(2, 0), result)
        assertFalse(hasPlaintextSqliteHeader(mainFile))
        assertFalse(hasPlaintextSqliteHeader(txsFile))

        val main = MainDatabase.getInstance(context, mainFile.name, key)
        val txs = TransactionDatabase.getInstance(context, txsFile.name, key)
        try {
            SolanaV5V11Fixture.assertMainContents(main)
            SolanaV5V11Fixture.assertTxsContents(txs)
        } finally {
            main.close()
            txs.close()
        }

        // Unchanged versions prove neither a migration nor a destructive fallback ran after the transfer.
        assertEquals(5, encryptedUserVersion(mainFile))
        assertEquals(11, encryptedUserVersion(txsFile))
    }

    private fun encryptedUserVersion(file: File): Int = SqlCipherDriver(key).use { driver ->
        driver.open(file.path).use { connection ->
            connection.prepare("PRAGMA user_version").use { statement ->
                check(statement.step()) { "No user_version in ${file.name}" }
                statement.getInt(0)
            }
        }
    }

    private companion object {
        const val WALLET_ID = "encrypted-fixture"
    }
}
