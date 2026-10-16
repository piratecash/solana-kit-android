package io.horizontalsystems.solanakit

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.horizontalsystems.solanakit.database.main.MainDatabase
import io.horizontalsystems.solanakit.database.main.mainSchemaPolicy
import io.horizontalsystems.solanakit.database.transaction.TransactionDatabase
import io.horizontalsystems.solanakit.database.transaction.transactionSchemaPolicy
import kotlinx.coroutines.Dispatchers
import java.io.File

/** Builds a pre-migration file with the schema of [userVersion] and no Room bookkeeping. */
internal fun createPlaintextDatabase(file: File, userVersion: Int, statements: List<String>) {
    BundledSQLiteDriver().open(file.path).use { connection ->
        statements.forEach(connection::execSQL)
        connection.execSQL("PRAGMA user_version = $userVersion")
    }
}

// The production schema policy on a plaintext file: the kit itself opens databases through SQLCipher only.
internal fun openPlaintextMainDatabase(file: File): MainDatabase =
    plaintextBuilder<MainDatabase>(file).mainSchemaPolicy().build()

internal fun openPlaintextTransactionDatabase(file: File): TransactionDatabase =
    plaintextBuilder<TransactionDatabase>(file).transactionSchemaPolicy().build()

private inline fun <reified T : RoomDatabase> plaintextBuilder(file: File): RoomDatabase.Builder<T> {
    file.absoluteFile.parentFile?.mkdirs()
    return Room.databaseBuilder<T>(file.path)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
}

internal fun userVersion(file: File): Int =
    BundledSQLiteDriver().open(file.path).use { connection ->
        connection.prepare("PRAGMA user_version").use { statement ->
            check(statement.step()) { "No user_version in ${file.name}" }
            statement.getInt(0)
        }
    }
