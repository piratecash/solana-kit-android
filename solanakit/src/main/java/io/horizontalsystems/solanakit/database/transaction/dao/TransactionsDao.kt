package io.horizontalsystems.solanakit.database.transaction.dao

import androidx.room.*
import io.horizontalsystems.solanakit.models.*
import io.horizontalsystems.solanakit.models.Transaction

@Dao
interface TransactionsDao {

    @Query("SELECT * FROM `Transaction` WHERE hash = :transactionHash LIMIT 1")
    suspend fun get(transactionHash: String) : Transaction?

    @Query("SELECT * FROM `Transaction` WHERE NOT pending AND NOT external ORDER BY timestamp DESC LIMIT 1")
    suspend fun lastNonPendingTransaction() : Transaction?

    @Query("SELECT * FROM `Transaction` WHERE pending ORDER BY timestamp")
    suspend fun pendingTransactions() : List<Transaction>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransactions(transactions: List<Transaction>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransaction(transaction: Transaction): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTokenTransfers(tokenTransfers: List<TokenTransfer>)

    @Update
    suspend fun updateTransactions(transactions: List<Transaction>)

    @Query("DELETE FROM `Transaction` WHERE external AND hash = :transactionHash")
    suspend fun deleteExternalTransaction(transactionHash: String)

    @Query("DELETE FROM `Transaction` WHERE external AND hash IN (:transactionHashes)")
    suspend fun deleteExternalTransactions(transactionHashes: List<String>)

    @RawQuery
    suspend fun getTransactions(query: RoomRawQuery): List<FullTransactionWrapper>

    data class FullTransactionWrapper(
        @Embedded
        val transaction: Transaction,

        @Relation(
            entity = TokenTransfer::class,
            parentColumn = "hash",
            entityColumn = "transactionHash"
        )
        val tokenTransfersWithMintAccounts: List<TokenTransferAndMintAccount>
    ) {

        val fullTransaction: FullTransaction
            get() = FullTransaction(transaction, tokenTransfersWithMintAccounts.map { it.fullTokenTransfer })

    }

    data class TokenTransferAndMintAccount(
        @Embedded
        val tokenTransfer: TokenTransfer,

        @Relation(
            parentColumn = "mintAddress",
            entityColumn = "address"
        )
        val mintAccount: MintAccount
    ) {

        val fullTokenTransfer: FullTokenTransfer
            get() = FullTokenTransfer(tokenTransfer, mintAccount)

    }

}
