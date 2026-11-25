package io.horizontalsystems.solanakit.database

import androidx.room.Room
import androidx.room.RoomDatabase
import io.horizontalsystems.databasenaming.DatabaseNameResolver
import io.horizontalsystems.solanakit.PlatformContext
import io.horizontalsystems.solanakit.database.main.MainDatabase
import io.horizontalsystems.solanakit.database.transaction.TransactionDatabase

internal fun mainDatabaseBuilder(
    context: PlatformContext,
    name: String,
    databaseKey: ByteArray,
    nameResolver: DatabaseNameResolver = DatabaseNameResolver.Identity,
): RoomDatabase.Builder<MainDatabase> =
    solanaKitDatabases(nameResolver).encrypted(
        Room.databaseBuilder(context, MainDatabase::class.java, name),
        databaseFile(context, name).path,
        databaseKey,
    )

internal fun transactionDatabaseBuilder(
    context: PlatformContext,
    name: String,
    databaseKey: ByteArray,
    nameResolver: DatabaseNameResolver = DatabaseNameResolver.Identity,
): RoomDatabase.Builder<TransactionDatabase> =
    solanaKitDatabases(nameResolver).encrypted(
        Room.databaseBuilder(context, TransactionDatabase::class.java, name),
        databaseFile(context, name).path,
        databaseKey,
    )
