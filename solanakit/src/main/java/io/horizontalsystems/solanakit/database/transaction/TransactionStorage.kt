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

/** One-time re-fetches of stored rows, each with its own cursor row in LastSyncedTransaction. */
enum class TokenTransferRepair(internal val sourceName: String, internal val withoutAmount: Boolean) {
    // Rows stored before token transfers were attributed by owner.
    ByOwner("token-transfer-repair-v1", withoutAmount = false),

    // Rows stored before Token-2022 mints were accepted: a token change leaves the SOL amount empty.
    Token2022("token-2022-repair-v1", withoutAmount = true),
}

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

    suspend fun syncSourceNames(prefix: String): List<String> =
        syncerStateDao.namesWithPrefix(prefix)

    suspend fun tokenTransferRepairCursor(repair: TokenTransferRepair): String? =
        syncerStateDao.get(repair.sourceName)?.hash

    suspend fun saveTokenTransferRepairCursor(repair: TokenTransferRepair, cursor: String) {
        syncerStateDao.save(LastSyncedTransaction(repair.sourceName, cursor))
    }

    /** Confirmed transactions to repair strictly after the given position, newest first; -1 = no limit. */
    suspend fun tokenTransferTransactionHashes(
        repair: TokenTransferRepair,
        beforeTimestamp: Long?,
        beforeHash: String?,
        limit: Int,
    ): List<TransactionKey> =
        transactionsDao.tokenTransferTransactionKeys(repair.withoutAmount, beforeTimestamp, beforeHash, limit)

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
        limit: Int?,
        sinceTimestamp: Long? = null,
        untilTimestamp: Long? = null
    ): List<FullTransaction> =
        getTransactions(allFilter(incoming), fromHash, limit, sinceTimestamp, untilTimestamp)

    suspend fun getSolTransactions(
        incoming: Boolean?,
        fromHash: String?,
        limit: Int?,
        sinceTimestamp: Long? = null,
        untilTimestamp: Long? = null
    ): List<FullTransaction> =
        getTransactions(solFilter(incoming), fromHash, limit, sinceTimestamp, untilTimestamp)

    suspend fun getSplTransactions(
        mintAddress: String,
        incoming: Boolean?,
        fromHash: String?,
        limit: Int?,
        sinceTimestamp: Long? = null,
        untilTimestamp: Long? = null
    ): List<FullTransaction> =
        getTransactions(splFilter(mintAddress, incoming), fromHash, limit, sinceTimestamp, untilTimestamp)

    suspend fun oldestTimestamp(incoming: Boolean?): Long? =
        oldestTimestamp(allFilter(incoming))

    suspend fun oldestSolTimestamp(incoming: Boolean?): Long? =
        oldestTimestamp(solFilter(incoming))

    suspend fun oldestSplTimestamp(mintAddress: String, incoming: Boolean?): Long? =
        oldestTimestamp(splFilter(mintAddress, incoming))

    private class HistoryFilter(val typeCondition: String?, val joinTokenTransfers: Boolean)

    private fun allFilter(incoming: Boolean?): HistoryFilter {
        val condition = incoming?.let {
            if (incoming) "((tx.amount IS NOT NULL AND tx.`to` = '$address') OR tt.incoming)"
            else "((tx.amount IS NOT NULL AND tx.`from` = '$address') OR NOT(tt.incoming))"
        }

        return HistoryFilter(condition, incoming != null)
    }

    private fun solFilter(incoming: Boolean?): HistoryFilter {
        val condition = incoming?.let {
            if (incoming) "(tx.amount IS NOT NULL AND tx.`to` = '$address')"
            else "(tx.amount IS NOT NULL AND tx.`from` = '$address')"
        } ?: "tx.amount IS NOT NULL"

        return HistoryFilter(condition, false)
    }

    private fun splFilter(mintAddress: String, incoming: Boolean?): HistoryFilter {
        val condition = incoming?.let {
            val incomingCondition = if (incoming) "tt.incoming" else "NOT(tt.incoming)"
            "(tt.mintAddress = '$mintAddress' AND $incomingCondition)"
        } ?: "tt.mintAddress = '$mintAddress'"

        return HistoryFilter(condition, true)
    }

    private fun fromClause(filter: HistoryFilter, whereConditions: List<String>) = """
        FROM `Transaction` AS tx
        ${if (filter.joinTokenTransfers) "LEFT JOIN TokenTransfer AS tt ON tx.hash = tt.transactionHash" else ""}
        WHERE ${whereConditions.joinToString(" AND ")}
        """

    private suspend fun oldestTimestamp(filter: HistoryFilter): Long? {
        val whereConditions = listOfNotNull("NOT tx.external", DATED_CONDITION, filter.typeCondition)

        return transactionsDao.oldestTimestamp(
            RoomRawQuery("SELECT MIN(tx.timestamp) ${fromClause(filter, whereConditions)}")
        )
    }

    private suspend fun getTransactions(
        filter: HistoryFilter,
        fromHash: String?,
        limit: Int?,
        sinceTimestamp: Long?,
        untilTimestamp: Long?
    ): List<FullTransaction> {
        val whereConditions = listOfNotNull("NOT tx.external", filter.typeCondition).toMutableList()
        if (sinceTimestamp != null || untilTimestamp != null) whereConditions.add(DATED_CONDITION)
        sinceTimestamp?.let { whereConditions.add("tx.timestamp >= $it") }
        untilTimestamp?.let { whereConditions.add("tx.timestamp < $it") }

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

        val orderClause = "ORDER BY tx.timestamp DESC, HEX(tx.hash) DESC"
        val limitClause = limit?.let { "LIMIT $it" } ?: ""

        val sqlQuery = """
                      SELECT DISTINCT tx.*
                      ${fromClause(filter, whereConditions)}
                      $orderClause
                      $limitClause
                      """

        return transactionsDao.getTransactions(RoomRawQuery(sqlQuery))
            .map { it.fullTransaction }
    }

    suspend fun getMintAccount(address: String): MintAccount? =
        mintAccountDao.get(address)

    /**
     * Hashes among [hashes] stored as mapped history. A confirmed own send keeps its send-time
     * placeholder data (and its signed bytes) until history remaps it.
     */
    suspend fun storedHashes(hashes: List<String>): Set<String> =
        hashes.chunked(HASH_QUERY_CHUNK).flatMapTo(HashSet()) { chunk ->
            getFullTransactions(chunk).map { it.transaction }
                .filter { !it.pending && it.base64Encoded.isEmpty() }
                .map { it.hash }
        }

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

    /** Moves a row to the mint's actual account; a row the discovery already stored there is kept. */
    suspend fun replaceTokenAccount(staleAddress: String, tokenAccount: TokenAccount) {
        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                tokenAccountDao.delete(staleAddress)
                if (tokenAccountDao.getByAddress(tokenAccount.address) == null) {
                    tokenAccountDao.insert(tokenAccount)
                }
            }
        }
    }

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
        // Keeps the inlined IN list far below SQLite's statement length limit.
        const val HASH_QUERY_CHUNK = 500

        // Rows without a block time are stored with timestamp 0 and belong to no period.
        const val DATED_CONDITION = "tx.timestamp > 0"
    }
}
