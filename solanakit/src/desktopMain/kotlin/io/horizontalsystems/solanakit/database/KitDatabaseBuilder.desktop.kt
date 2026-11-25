package io.horizontalsystems.solanakit.database

import androidx.room.Room
import androidx.room.RoomDatabase
import io.horizontalsystems.databasenaming.DatabaseNameResolver
import io.horizontalsystems.solanakit.PlatformContext
import io.horizontalsystems.solanakit.database.main.MainDatabase
import io.horizontalsystems.solanakit.database.transaction.TransactionDatabase
import kotlinx.coroutines.Dispatchers

internal fun mainDatabaseBuilder(
    context: PlatformContext,
    name: String,
    databaseKey: ByteArray,
    nameResolver: DatabaseNameResolver = DatabaseNameResolver.Identity,
): RoomDatabase.Builder<MainDatabase> = desktopDatabaseBuilder(context, name, databaseKey, nameResolver)

internal fun transactionDatabaseBuilder(
    context: PlatformContext,
    name: String,
    databaseKey: ByteArray,
    nameResolver: DatabaseNameResolver = DatabaseNameResolver.Identity,
): RoomDatabase.Builder<TransactionDatabase> = desktopDatabaseBuilder(context, name, databaseKey, nameResolver)

private inline fun <reified T : RoomDatabase> desktopDatabaseBuilder(
    context: PlatformContext,
    name: String,
    databaseKey: ByteArray,
    nameResolver: DatabaseNameResolver = DatabaseNameResolver.Identity,
): RoomDatabase.Builder<T> {
    val file = databaseFile(context, name)
    // Verifies the file against the key before any directory is created.
    val builder = solanaKitDatabases(nameResolver).encrypted(Room.databaseBuilder<T>(file.path), file.path, databaseKey)
    // Unlike Android's getDatabasePath, a JVM driver does not create the parent directory.
    file.absoluteFile.parentFile?.mkdirs()
    return builder.setQueryCoroutineContext(Dispatchers.IO)
}
