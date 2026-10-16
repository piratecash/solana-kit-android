package io.horizontalsystems.solanakit.core

import io.horizontalsystems.solanakit.PlatformContext
import io.horizontalsystems.solanakit.database.mainDatabaseName
import io.horizontalsystems.solanakit.database.main.MainDatabase
import io.horizontalsystems.solanakit.database.transaction.TransactionDatabase
import io.horizontalsystems.solanakit.database.transactionDatabaseName

internal object SolanaDatabaseManager {

    fun getMainDatabase(context: PlatformContext, walletId: String, databaseKey: ByteArray): MainDatabase =
        MainDatabase.getInstance(context, mainDatabaseName(walletId), databaseKey)

    fun getTransactionDatabase(
        context: PlatformContext,
        walletId: String,
        databaseKey: ByteArray,
    ): TransactionDatabase =
        TransactionDatabase.getInstance(context, transactionDatabaseName(walletId), databaseKey)

}
