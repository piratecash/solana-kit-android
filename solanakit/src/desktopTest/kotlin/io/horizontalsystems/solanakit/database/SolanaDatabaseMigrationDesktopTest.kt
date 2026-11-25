package io.horizontalsystems.solanakit.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.horizontalsystems.solanakit.PlatformContext
import io.horizontalsystems.solanakit.SolanaKit
import io.horizontalsystems.solanakit.SolanaV5V11Fixture
import io.horizontalsystems.solanakit.database.main.MainDatabase
import io.horizontalsystems.solanakit.database.transaction.TransactionDatabase
import io.horizontalsystems.solanakit.hasPlaintextSqliteHeader
import io.horizontalsystems.solanakit.models.RpcSource
import io.horizontalsystems.sqlcipher.SqlCipherDriver
import io.horizontalsystems.sqlcipher.SqlCipherMigration
import io.horizontalsystems.sqlcipher.room.DatabaseKeyMismatchException
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationRequiredException
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** How the kit wires sqlcipher-room; engine scenarios with a single database are covered by the module itself. */
class SolanaDatabaseMigrationDesktopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val key = ByteArray(32) { it.toByte() }
    private val otherKey = ByteArray(32) { (it + 1).toByte() }

    private val directory: File get() = tmp.root
    private val context: PlatformContext get() = PlatformContext(directory)
    private val mainDatabase: File get() = File(directory, mainDatabaseName(WALLET_ID))
    private val txsDatabase: File get() = File(directory, transactionDatabaseName(WALLET_ID))

    @Test
    fun migrateDatabase_bothPlaintextDatabases_encryptsGroupAndKeepsData() = runBlocking {
        createSampleDatabase(mainDatabase, "main")
        createSampleDatabase(txsDatabase, "txs")

        val result = migrate(key)

        assertEquals(DatabaseMigrationResult(2, 0), result)
        assertFalse(hasPlaintextSqliteHeader(mainDatabase))
        assertFalse(hasPlaintextSqliteHeader(txsDatabase))
        assertEquals("main", readEncryptedValue(mainDatabase, key))
        assertEquals("txs", readEncryptedValue(txsDatabase, key))
        assertNoMigrationArtifacts()
    }

    @Test
    fun migrateDatabase_groupAlreadyEncrypted_reportsBothAndKeepsFileBytes() = runBlocking {
        createSampleDatabase(mainDatabase, "main")
        createSampleDatabase(txsDatabase, "txs")
        migrate(key)
        val snapshot = listOf(mainDatabase, txsDatabase).associateWith(File::readBytes)

        val result = migrate(key)

        assertEquals(DatabaseMigrationResult(0, 2), result)
        snapshot.forEach { (file, bytes) -> assertArrayEquals(file.name, bytes, file.readBytes()) }
    }

    @Test
    fun migrateDatabase_onlyMainDatabaseExists_migratesItAndGetInstanceCreatesEncryptedTransactions() = runBlocking {
        SolanaV5V11Fixture.copyMainDbTo(mainDatabase)

        val result = migrate(key)

        assertEquals(DatabaseMigrationResult(1, 0), result)
        assertFalse(hasPlaintextSqliteHeader(mainDatabase))
        assertFalse(txsDatabase.exists())

        val database = TransactionDatabase.getInstance(context, txsDatabase.name, key)
        try {
            assertTrue(database.transactionsDao().pendingTransactions().isEmpty())
        } finally {
            database.close()
        }
        assertFalse(hasPlaintextSqliteHeader(txsDatabase))
    }

    @Test
    fun migrateDatabase_groupEncryptedWithOtherKey_throwsKeyMismatchWithoutChangingFiles() = runBlocking {
        createSampleDatabase(mainDatabase, "main")
        createSampleDatabase(txsDatabase, "txs")
        migrate(key)
        val snapshot = listOf(mainDatabase, txsDatabase).associateWith(File::readBytes)

        assertThrows(DatabaseKeyMismatchException::class.java) {
            runBlocking { migrate(otherKey) }
        }

        snapshot.forEach { (file, bytes) -> assertArrayEquals(file.name, bytes, file.readBytes()) }
        assertEquals("main", readEncryptedValue(mainDatabase, key))
    }

    @Test
    fun migrateDatabase_stagedMigrationWasInterrupted_recoversAndMigratesBoth() = runBlocking {
        createSampleDatabase(mainDatabase, "main")
        createSampleDatabase(txsDatabase, "txs")
        interruptMigration(ManifestPhase.STAGED, key, File(directory, "$MANIFEST_PREFIX-interrupted.json"))

        val result = migrate(key)

        assertEquals(DatabaseMigrationResult(2, 0), result)
        assertEquals("main", readEncryptedValue(mainDatabase, key))
        assertEquals("txs", readEncryptedValue(txsDatabase, key))
        assertNoMigrationArtifacts()
    }

    @Test
    fun migrateDatabase_committedMigrationWasInterrupted_finishesCleanupAndReportsEncrypted() = runBlocking {
        createSampleDatabase(mainDatabase, "main")
        createSampleDatabase(txsDatabase, "txs")
        interruptMigration(ManifestPhase.COMMITTED, key, File(directory, "$MANIFEST_PREFIX-interrupted.json"))

        val result = migrate(key)

        assertEquals(DatabaseMigrationResult(0, 2), result)
        assertEquals("main", readEncryptedValue(mainDatabase, key))
        assertEquals("txs", readEncryptedValue(txsDatabase, key))
        assertNoMigrationArtifacts()
    }

    @Test
    fun getInstance_plaintextWithoutMigration_throwsMigrationRequiredWithoutChangingFile() {
        SolanaV5V11Fixture.copyMainDbTo(mainDatabase)
        val plaintextBytes = mainDatabase.readBytes()

        assertThrows(DatabaseMigrationRequiredException::class.java) {
            MainDatabase.getInstance(context, mainDatabase.name, key)
        }

        assertArrayEquals(plaintextBytes, mainDatabase.readBytes())
    }

    @Test
    fun clear_interruptedMigration_removesBothDatabaseFamiliesAndMigrationLeftovers() {
        createSampleDatabase(mainDatabase, "main")
        createSampleDatabase(txsDatabase, "txs")
        val entries = stageDatabases(key)
        entries.forEach(::installStagedDatabase)
        listOf(mainDatabase, txsDatabase).forEach { database ->
            listOf("-wal", "-shm", "-journal").forEach { suffix -> File("${database.path}$suffix").writeText("x") }
        }
        // Written under the id the kit itself uses, so only a clear with the same id removes it.
        writeManifest(ManifestPhase.STAGED, entries, manifestFileFor(migrationId(WALLET_ID)))

        SolanaKit.clear(context, WALLET_ID)

        assertEquals(listOf(LOCK_FILE_NAME), directory.list()?.toList())
    }

    @Test
    fun migrateAndClear_otherKitFilesInSameDirectory_areIgnoredAndUntouched() = runBlocking {
        val foreignFiles = listOf("bitcoin-kit", "tron-kit", "stellar-kit").flatMap { namespace ->
            val foreignDatabase = File(directory, "$namespace.db")
            createSampleDatabase(backupOf(foreignDatabase), namespace)
            val manifest = File(directory, ".$namespace-sqlcipher-0123456789abcdef.json")
            manifest.writeText(
                manifestJson(
                    ManifestPhase.STAGED,
                    listOf(StagedEntry(foreignDatabase.path, "${foreignDatabase.path}$STAGING_SUFFIX")),
                )
            )
            val lock = File(directory, ".$namespace-sqlcipher.lock").apply { writeText("") }
            listOf(backupOf(foreignDatabase), manifest, lock)
        }
        val snapshot = foreignFiles.associateWith(File::readBytes)
        createSampleDatabase(mainDatabase, "main")
        createSampleDatabase(txsDatabase, "txs")

        val result = migrate(key)
        SolanaKit.clear(context, WALLET_ID)

        assertEquals(DatabaseMigrationResult(2, 0), result)
        assertFalse(mainDatabase.exists())
        assertFalse(txsDatabase.exists())
        snapshot.forEach { (file, bytes) -> assertArrayEquals(file.name, bytes, file.readBytes()) }
        assertEquals((foreignFiles.map(File::getName) + LOCK_FILE_NAME).sorted(), directory.list()?.sorted())
    }

    @Test
    fun migrateDatabase_invalidArguments_throwBeforeAnyFileIsCreated() {
        invalidGroupArguments().forEach { (walletId, databaseKey) ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { SolanaKit.migrateDatabase(emptyContext(), walletId, databaseKey) }
            }
            assertDirectoryEmpty(walletId)
        }
    }

    @Test
    fun getInstance_invalidArguments_throwBeforeAnyFileIsCreated() {
        invalidGroupArguments().forEach { (walletId, databaseKey) ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    SolanaKit.getInstance(
                        context = emptyContext(),
                        addressString = WALLET_ADDRESS,
                        rpcSource = RpcSource.Custom("mock", RPC_URL, RPC_URL, 30),
                        walletId = walletId,
                        databaseKey = databaseKey,
                    )
                }
            }
            assertDirectoryEmpty(walletId)
        }
    }

    @Test
    fun clear_invalidWalletId_throwsBeforeAnyFileIsCreated() {
        invalidGroupArguments().filter { (_, databaseKey) -> databaseKey.size == 32 }.forEach { (walletId, _) ->
            assertThrows(IllegalArgumentException::class.java) {
                SolanaKit.clear(emptyContext(), walletId)
            }
            assertDirectoryEmpty(walletId)
        }
    }

    @Test
    fun getInstance_reservedDatabaseName_throwsBeforeAnyFileIsCreated() {
        invalidDatabaseNames().forEach { name ->
            assertThrows(IllegalArgumentException::class.java) {
                MainDatabase.getInstance(emptyContext(), name, key)
            }
            assertDirectoryEmpty(name)
        }
    }

    private suspend fun migrate(databaseKey: ByteArray): DatabaseMigrationResult =
        SolanaKit.migrateDatabase(context, WALLET_ID, databaseKey)

    // A directory that does not exist yet, so any file the call creates shows up as a leftover.
    private fun emptyContext(): PlatformContext = PlatformContext(File(directory, "data"))

    private fun invalidGroupArguments(): List<Pair<String, ByteArray>> = listOf(
        WALLET_ID to ByteArray(31),
        WALLET_ID to ByteArray(33),
        "" to key,
        " " to key,
        "wallet/../other" to key,
        "wallet\\other" to key,
    )

    private fun invalidDatabaseNames(): List<String> = listOf(
        "",
        " ",
        "sub/Solana-wallet-main",
        "$MANIFEST_PREFIX-wallet.json",
        LOCK_FILE_NAME,
        ".bitcoin-kit-sqlcipher-x.json",
        ".tron-kit-sqlcipher-x.json",
        ".stellar-kit-sqlcipher-x.json",
        "Solana-wallet-main$STAGING_SUFFIX",
        "Solana-wallet-main$BACKUP_SUFFIX",
    )

    private fun assertDirectoryEmpty(label: String) {
        assertEquals("files after '$label'", emptyList<String>(), directory.list()?.toList())
    }

    private fun createSampleDatabase(file: File, value: String) {
        BundledSQLiteDriver().open(file.path).use { connection ->
            connection.execSQL("CREATE TABLE sample(value TEXT NOT NULL)")
            connection.execSQL("INSERT INTO sample VALUES('$value')")
        }
    }

    private fun readEncryptedValue(file: File, databaseKey: ByteArray): String =
        SqlCipherDriver(databaseKey).use { driver ->
            driver.open(file.path).use { connection ->
                connection.prepare("SELECT value FROM sample").use { statement ->
                    check(statement.step()) { "Test database contains no sample row" }
                    statement.getText(0)
                }
            }
        }

    // Leaves the files a migration killed in [phase] leaves behind: ciphertext under [databaseKey] and its manifest.
    private fun interruptMigration(phase: ManifestPhase, databaseKey: ByteArray, manifest: File) {
        val entries = stageDatabases(databaseKey)
        entries.forEach(::installStagedDatabase)
        writeManifest(phase, entries, manifest)
    }

    private fun stageDatabases(databaseKey: ByteArray): List<StagedEntry> =
        listOf(mainDatabase, txsDatabase).map { file ->
            val staging = File("${file.path}$STAGING_SUFFIX")
            SqlCipherMigration.exportPlaintext(file.path, staging.path, databaseKey)
            StagedEntry(file.path, staging.path)
        }

    private fun installStagedDatabase(entry: StagedEntry) {
        val databaseFile = File(entry.databasePath)
        Files.move(databaseFile.toPath(), backupOf(databaseFile).toPath(), StandardCopyOption.ATOMIC_MOVE)
        Files.move(File(entry.stagingPath).toPath(), databaseFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun writeManifest(phase: ManifestPhase, entries: List<StagedEntry>, file: File) {
        file.writeText(manifestJson(phase, entries))
    }

    // The sqlcipher-room manifest format, version 1.
    private fun manifestJson(phase: ManifestPhase, entries: List<StagedEntry>): String {
        val encoded = entries.joinToString(",") { entry ->
            """{"databasePath":${jsonString(entry.databasePath)},"stagingPath":${jsonString(entry.stagingPath)}}"""
        }
        return """{"version":1,"phase":"${phase.name}","entries":[$encoded]}"""
    }

    private fun jsonString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // Same derivation as sqlcipher-room's manifest file name: the first 8 bytes of SHA-256(migrationId) in hex.
    private fun manifestFileFor(migrationId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(migrationId.encodeToByteArray())
        val id = digest.take(8).joinToString("") { "%02x".format(it) }
        return File(directory, "$MANIFEST_PREFIX-$id.json")
    }

    private fun backupOf(file: File): File = File("${file.path}$BACKUP_SUFFIX")

    private fun assertNoMigrationArtifacts() {
        val artifacts = directory.list()?.filter { name ->
            name.endsWith(".json") || name.endsWith(STAGING_SUFFIX) || name.endsWith(BACKUP_SUFFIX)
        }
        assertTrue("migration artifacts left: $artifacts", artifacts.isNullOrEmpty())
    }

    private data class StagedEntry(val databasePath: String, val stagingPath: String)

    private enum class ManifestPhase { STAGED, COMMITTED }

    private companion object {
        const val WALLET_ID = "migration-wallet"
        const val WALLET_ADDRESS = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
        const val MANIFEST_PREFIX = ".solana-kit-sqlcipher"
        const val LOCK_FILE_NAME = ".solana-kit-sqlcipher.lock"
        const val STAGING_SUFFIX = ".sqlcipher-migrating"
        const val BACKUP_SUFFIX = ".plaintext-backup"
        val RPC_URL: URL = URI("http://127.0.0.1:1/").toURL()
    }
}
