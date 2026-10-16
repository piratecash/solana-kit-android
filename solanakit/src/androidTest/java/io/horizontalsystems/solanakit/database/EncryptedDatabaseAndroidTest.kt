package io.horizontalsystems.solanakit.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.horizontalsystems.solanakit.SolanaKit
import io.horizontalsystems.solanakit.SolanaV5V11Fixture
import io.horizontalsystems.solanakit.database.main.MainDatabase
import io.horizontalsystems.solanakit.database.transaction.TransactionDatabase
import io.horizontalsystems.solanakit.hasPlaintextSqliteHeader
import io.horizontalsystems.sqlcipher.room.DatabaseKeyMismatchException
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationRequiredException
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** The SQLCipher path on a real Android runtime: native library, SupportOpenHelperFactory and getDatabasePath. */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseAndroidTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val key = ByteArray(32) { (it * 3).toByte() }
    private val otherKey = ByteArray(32) { (it * 5 + 1).toByte() }
    private val walletId = "encrypted-${UUID.randomUUID()}"
    private val mainFile = context.getDatabasePath(mainDatabaseName(walletId))
    private val txsFile = context.getDatabasePath(transactionDatabaseName(walletId))

    @After
    fun tearDown() {
        SolanaKit.clear(context, walletId)
    }

    @Test
    fun migrateDatabase_room272Fixtures_preservesStoredWalletState() = runBlocking {
        copyFixtures()

        val result = migrate(key)

        assertEquals(DatabaseMigrationResult(2, 0), result)
        assertFalse(hasPlaintextSqliteHeader(mainFile))
        assertFalse(hasPlaintextSqliteHeader(txsFile))
        assertFixturesOpenWith(key)
    }

    @Test
    fun migrateDatabase_alreadyEncrypted_reportsAlreadyEncrypted() = runBlocking {
        copyFixtures()
        migrate(key)

        val result = migrate(key)

        assertEquals(DatabaseMigrationResult(0, 2), result)
    }

    @Test
    fun getInstance_otherKey_throwsKeyMismatchWithoutChangingFile() = runBlocking {
        copyFixtures()
        migrate(key)
        val encryptedBytes = mainFile.readBytes()

        assertThrows(DatabaseKeyMismatchException::class.java) {
            MainDatabase.getInstance(context, mainFile.name, otherKey)
        }

        assertArrayEquals(encryptedBytes, mainFile.readBytes())
        assertFixturesOpenWith(key)
    }

    @Test
    fun getInstance_plaintextWithoutMigration_throwsMigrationRequired() {
        copyFixtures()
        val plaintextBytes = mainFile.readBytes()

        assertThrows(DatabaseMigrationRequiredException::class.java) {
            MainDatabase.getInstance(context, mainFile.name, key)
        }

        assertArrayEquals(plaintextBytes, mainFile.readBytes())
    }

    @Test
    fun migrateDatabase_stagedMigrationWasInterrupted_recoversAndMigratesBoth() = runBlocking {
        copyFixtures()
        interruptStagedMigration()

        val result = migrate(key)

        assertEquals(DatabaseMigrationResult(2, 0), result)
        assertFixturesOpenWith(key)
        assertNoMigrationArtifacts()
    }

    @Test
    fun clear_encryptedGroup_removesEveryFile() = runBlocking {
        copyFixtures()
        migrate(key)
        assertFixturesOpenWith(key)

        SolanaKit.clear(context, walletId)

        val leftovers = databaseDirectory()?.list()?.filter { name ->
            name.startsWith(mainFile.name) || name.startsWith(txsFile.name) || isOwnManifest(name)
        }
        assertTrue("leftovers: $leftovers", leftovers.isNullOrEmpty())
    }

    private suspend fun migrate(databaseKey: ByteArray): DatabaseMigrationResult =
        SolanaKit.migrateDatabase(context, walletId, databaseKey)

    private fun copyFixtures() {
        SolanaV5V11Fixture.copyMainDbTo(mainFile)
        SolanaV5V11Fixture.copyTxsDbTo(txsFile)
    }

    private suspend fun assertFixturesOpenWith(databaseKey: ByteArray) {
        val main = MainDatabase.getInstance(context, mainFile.name, databaseKey)
        val txs = TransactionDatabase.getInstance(context, txsFile.name, databaseKey)
        try {
            SolanaV5V11Fixture.assertMainContents(main)
            SolanaV5V11Fixture.assertTxsContents(txs)
        } finally {
            main.close()
            txs.close()
        }
    }

    /**
     * Leaves exactly what a migration killed in the STAGED phase leaves behind: the encrypted files
     * installed, the plaintext originals kept as backups and a STAGED manifest.
     */
    private suspend fun interruptStagedMigration() {
        migrate(key)
        SolanaV5V11Fixture.copyMainDbTo(File("${mainFile.path}$BACKUP_SUFFIX"))
        SolanaV5V11Fixture.copyTxsDbTo(File("${txsFile.path}$BACKUP_SUFFIX"))
        val entries = listOf(mainFile, txsFile).joinToString(",") { database ->
            """{"databasePath":"${database.path}","stagingPath":"${database.path}$STAGING_SUFFIX"}"""
        }
        File(mainFile.parentFile, "${MANIFEST_PREFIX}interrupted.json")
            .writeText("""{"version":1,"phase":"STAGED","entries":[$entries]}""")
    }

    private fun assertNoMigrationArtifacts() {
        val artifacts = databaseDirectory()?.list()?.filter { name ->
            name.endsWith(BACKUP_SUFFIX) || name.endsWith(STAGING_SUFFIX) || isOwnManifest(name)
        }
        assertTrue("migration artifacts left: $artifacts", artifacts.isNullOrEmpty())
    }

    private fun isOwnManifest(name: String): Boolean =
        name.startsWith(MANIFEST_PREFIX) && name.endsWith(".json")

    private fun databaseDirectory(): File? = mainFile.parentFile

    private companion object {
        const val MANIFEST_PREFIX = ".solana-kit-sqlcipher-"
        const val STAGING_SUFFIX = ".sqlcipher-migrating"
        const val BACKUP_SUFFIX = ".plaintext-backup"
    }
}
