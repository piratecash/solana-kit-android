package io.horizontalsystems.solanakit.database.transaction

import androidx.room.RoomRawQuery
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import io.horizontalsystems.solanakit.database.transaction.dao.TransactionsDao.TransactionKey
import io.horizontalsystems.solanakit.models.FullTokenAccount
import io.horizontalsystems.solanakit.models.FullTransaction
import io.horizontalsystems.solanakit.models.LastSyncedTransaction
import io.horizontalsystems.solanakit.models.MintAccount
import io.horizontalsystems.solanakit.models.TokenAccount
import io.horizontalsystems.solanakit.models.Transaction

class TransactionStorage(
    private val database: TransactionDatabase,
    private val address: String
) {
    private val syncerStateDao = database.transactionSyncerStateDao()
    private val transactionsDao = database.transactionsDao()
    private val mintAccountDao = database.mintAccountDao()
    private val tokenAccountDao = database.tokenAccountsDao()

    suspend fun getSyncedBlockTime(syncerId: String): LastSyncedTransaction? =
        syncerStateDao.get(syncerId)

    suspend fun setSyncedBlockTime(syncBlockTime: LastSyncedTransaction) {
        syncerStateDao.save(syncBlockTime)
    }

    suspend fun tokenTransferRepairCursor(): String? =
        syncerStateDao.get(TOKEN_TRANSFER_REPAIR_SOURCE)?.hash

    suspend fun saveTokenTransferRepairCursor(cursor: String) {
        syncerStateDao.save(LastSyncedTransaction(TOKEN_TRANSFER_REPAIR_SOURCE, cursor))
    }

    /** Confirmed transactions with token transfers strictly after the given position, newest first; -1 = no limit. */
    suspend fun tokenTransferTransactionHashes(
        beforeTimestamp: Long?,
        beforeHash: String?,
        limit: Int,
    ): List<TransactionKey> = transactionsDao.tokenTransferTransactionKeys(beforeTimestamp, beforeHash, limit)

    suspend fun lastNonPendingTransaction(): Transaction? =
        transactionsDao.lastNonPendingTransaction()

    suspend fun pendingTransactions(): List<Transaction> =
        transactionsDao.pendingTransactions()

    suspend fun updateTransactions(transactions: List<Transaction>) =
        transactionsDao.updateTransactions(transactions)

    suspend fun addTransactions(transactions: List<FullTransaction>) {
        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                transactionsDao.insertTransactions(transactions.map { it.transaction })

                val fullTokenTransfers = transactions.map { it.tokenTransfers }.flatten()
                transactionsDao.insertTokenTransfers(fullTokenTransfers.map { it.tokenTransfer })
                mintAccountDao.insert(fullTokenTransfers.map { it.mintAccount }.toSet().toList())
            }
        }
    }

    suspend fun saveExternalTransaction(transaction: Transaction) {
        database.useWriterConnection { transactor ->
            transactor.immediateTransaction<Unit> {
                val existing = transactionsDao.get(transaction.hash)
                if (existing?.external == false) return@immediateTransaction

                if (existing == null) {
                    transactionsDao.insertTransaction(transaction)
                } else {
                    transactionsDao.updateTransactions(
                        listOf(transaction.copy(retryCount = existing.retryCount))
                    )
                }
            }
        }
    }

    suspend fun deleteExternalTransaction(transactionHash: String) =
        transactionsDao.deleteExternalTransaction(transactionHash)

    suspend fun deleteExternalTransactions(transactionHashes: List<String>) {
        if (transactionHashes.isNotEmpty()) {
            transactionsDao.deleteExternalTransactions(transactionHashes)
        }
    }

    suspend fun getTransactions(
        incoming: Boolean?,
        fromHash: String?,
        limit: Int?
    ): List<FullTransaction> {
        val condition = incoming?.let {
            if (incoming) "((tx.amount IS NOT NULL AND tx.`to` = '$address') OR tt.incoming)"
            else "((tx.amount IS NOT NULL AND tx.`from` = '$address') OR NOT(tt.incoming))"
        }

        return getTransactions(condition, incoming != null, fromHash, limit)
    }

    suspend fun getSolTransactions(
        incoming: Boolean?,
        fromHash: String?,
        limit: Int?
    ): List<FullTransaction> {
        val condition = incoming?.let {
            if (incoming) "(tx.amount IS NOT NULL AND tx.`to` = '$address')"
            else "(tx.amount IS NOT NULL AND tx.`from` = '$address')"
        } ?: "tx.amount IS NOT NULL"

        return getTransactions(condition, false, fromHash, limit)
    }

    suspend fun getSplTransactions(
        mintAddress: String,
        incoming: Boolean?,
        fromHash: String?,
        limit: Int?
    ): List<FullTransaction> {
        val condition = incoming?.let {
            val incomingCondition = if (incoming) "tt.incoming" else "NOT(tt.incoming)"
            "(tt.mintAddress = '$mintAddress' AND $incomingCondition)"
        } ?: "tt.mintAddress = '$mintAddress'"

        return getTransactions(condition, true, fromHash, limit)
    }

    private suspend fun getTransactions(
        typeCondition: String?,
        joinTokenTransfers: Boolean,
        fromHash: String?,
        limit: Int?
    ): List<FullTransaction> {
        val whereConditions = mutableListOf("NOT tx.external")
        typeCondition?.let { whereConditions.add(it) }

        fromHash?.let { transactionsDao.get(it) }?.let { fromTransaction ->
            val fromCondition = """
                           (
                                tx.timestamp < ${fromTransaction.timestamp} OR
                                (
                                    tx.timestamp = ${fromTransaction.timestamp} AND
                                    HEX(tx.hash) < "${fromTransaction.hash}"
                                )
                           )
                           """

            whereConditions.add(fromCondition)
        }

        val whereClause = "WHERE ${whereConditions.joinToString(" AND ")}"
        val orderClause = "ORDER BY tx.timestamp DESC, HEX(tx.hash) DESC"
        val limitClause = limit?.let { "LIMIT $it" } ?: ""

        val sqlQuery = """
                      SELECT DISTINCT tx.*
                      FROM `Transaction` AS tx
                      ${if (joinTokenTransfers) "LEFT JOIN TokenTransfer AS tt ON tx.hash = tt.transactionHash" else ""}
                      $whereClause
                      $orderClause
                      $limitClause
                      """

        return transactionsDao.getTransactions(RoomRawQuery(sqlQuery))
            .map { it.fullTransaction }
    }

    suspend fun getMintAccount(address: String): MintAccount? =
        mintAccountDao.get(address)

    suspend fun getFullTransactions(hashes: List<String>): List<FullTransaction> {
        if (hashes.isEmpty()) return emptyList()

        val sqlQuery = """
                      SELECT tx.*
                      FROM `Transaction` AS tx
                      WHERE tx.hash IN (${hashes.joinToString(", ") { "'$it'" }})
                      AND NOT tx.external
                      """

        return transactionsDao.getTransactions(RoomRawQuery(sqlQuery))
            .map { it.fullTransaction }
    }

    suspend fun saveTokenAccounts(tokenAccounts: List<TokenAccount>) {
        tokenAccountDao.insert(tokenAccounts)
    }

    suspend fun saveTokenAccounts(
        tokenAccounts: List<TokenAccount>,
        mintAccounts: List<MintAccount>
    ) {
        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                tokenAccountDao.insert(tokenAccounts)
                mintAccountDao.insert(mintAccounts)
            }
        }
    }

    suspend fun getTokenAccounts(mintAddresses: List<String>? = null): List<TokenAccount> =
        if (mintAddresses == null) tokenAccountDao.getAll()
        else tokenAccountDao.get(mintAddresses)

    suspend fun getFullTokenAccount(mintAddress: String): FullTokenAccount? =
        tokenAccountDao.get(mintAddress)?.fullTokenAccount

    suspend fun getFullTokenAccounts(): List<FullTokenAccount> =
        tokenAccountDao.getAllFullAccounts().map { it.fullTokenAccount }

    suspend fun addTokenAccountIfMissing(tokenAccount: TokenAccount, mintAccount: MintAccount) {
        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                if (tokenAccountDao.getByMintAddress(tokenAccount.mintAddress) != null) {
                    return@immediateTransaction
                }

                tokenAccountDao.insert(tokenAccount)
                mintAccountDao.insert(mintAccount)
            }
        }
    }

    private companion object {
        // Stored as a key-value row in LastSyncedTransaction, so no schema change is needed.
        const val TOKEN_TRANSFER_REPAIR_SOURCE = "token-transfer-repair-v1"
    }
}
