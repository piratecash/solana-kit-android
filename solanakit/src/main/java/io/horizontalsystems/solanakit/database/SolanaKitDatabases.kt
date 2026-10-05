package io.horizontalsystems.solanakit.database

import io.horizontalsystems.solanakit.PlatformContext
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationResult
import io.horizontalsystems.sqlcipher.room.SqlCipherDatabases

// The namespace names the on-disk manifest and lock files (`.solana-kit-sqlcipher*`); it must never change.
internal val solanaKitDatabases = SqlCipherDatabases("solana-kit")

internal fun mainDatabaseName(walletId: String) = "Solana-$walletId-main"

internal fun transactionDatabaseName(walletId: String) = "Solana-$walletId-txs"

// Both databases of a wallet form one group, so the engine encrypts them atomically.
internal fun databaseNames(walletId: String) = listOf(mainDatabaseName(walletId), transactionDatabaseName(walletId))

// Names the manifest of an interrupted migration; migrate and clear must always pass the same value.
internal fun migrationId(walletId: String) = "solana-$walletId"

internal suspend fun migrateDatabaseGroup(
    context: PlatformContext,
    walletId: String,
    databaseKey: ByteArray,
): DatabaseMigrationResult {
    requireValidDatabaseGroup(walletId, databaseKey)
    return solanaKitDatabases.migrateDatabases(
        dataDir = dataDirectoryPath(context, walletId),
        databaseNames = databaseNames(walletId),
        migrationId = migrationId(walletId),
        databaseKey = databaseKey,
    )
}

internal fun clearDatabaseGroup(context: PlatformContext, walletId: String) {
    requireValidWalletId(walletId)
    solanaKitDatabases.clearDatabases(
        dataDir = dataDirectoryPath(context, walletId),
        databaseNames = databaseNames(walletId),
        migrationId = migrationId(walletId),
    )
}

/** Rejects arguments the group cannot be built from, before the engine touches the file system. */
internal fun requireValidDatabaseGroup(walletId: String, databaseKey: ByteArray) {
    requireValidWalletId(walletId)
    requireValidDatabaseKey(databaseKey)
}

internal fun requireValidDatabaseKey(databaseKey: ByteArray) {
    require(databaseKey.size == DATABASE_KEY_SIZE) { "Database key must contain exactly $DATABASE_KEY_SIZE bytes" }
}

internal fun requireValidDatabaseName(name: String) {
    require(name.isNotBlank()) { "Database name must not be blank" }
    require(name.none { character -> character == '/' || character == '\\' }) {
        "Database name must not contain a path separator: $name"
    }
    require(RESERVED_PREFIXES.none(name::startsWith)) { "Database name uses a reserved migration prefix: $name" }
    require(RESERVED_SUFFIXES.none(name::endsWith)) { "Database name uses a reserved migration suffix: $name" }
}

private fun requireValidWalletId(walletId: String) {
    require(walletId.isNotBlank()) { "Wallet id must not be blank" }
    databaseNames(walletId).forEach(::requireValidDatabaseName)
}

private fun dataDirectoryPath(context: PlatformContext, walletId: String): String {
    val file = databaseFile(context, mainDatabaseName(walletId)).absoluteFile
    return checkNotNull(file.parent) { "Database path has no parent directory: ${file.path}" }
}

private const val DATABASE_KEY_SIZE = 32

// Mirror sqlcipher-room's private file names, so a wallet database never collides with migration files.
private val RESERVED_PREFIXES = listOf(
    ".solana-kit-sqlcipher",
    ".stellar-kit-sqlcipher",
    ".tron-kit-sqlcipher",
    ".bitcoin-kit-sqlcipher",
)
private val RESERVED_SUFFIXES = listOf(".sqlcipher-migrating", ".plaintext-backup")
