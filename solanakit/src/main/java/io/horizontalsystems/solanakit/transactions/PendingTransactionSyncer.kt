package io.horizontalsystems.solanakit.transactions

import com.solana.api.Api
import com.solana.api.getBlockHeight
import io.horizontalsystems.solanakit.database.transaction.TransactionStorage
import io.horizontalsystems.solanakit.models.Transaction
import io.horizontalsystems.solanakit.network.SolanaNetworkErrorListener
import io.horizontalsystems.solanakit.network.toSolanaNetworkError
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.logging.Logger

class PendingTransactionSyncer(
    private val rpcClient: Api,
    private val storage: TransactionStorage,
    private val transactionManager: TransactionManager,
    private val rpcUrl: URL,
    private val networkErrorListener: SolanaNetworkErrorListener?
) {
    private val logger = Logger.getLogger("PendingTransactionSyncer")

    suspend fun sync() {
        val updatedTransactions = mutableListOf<Transaction>()
        val externalTransactionsToDelete = mutableListOf<String>()

        val pendingTransactions = storage.pendingTransactions()
        val currentBlockHeight = try {
            rpcClient.getBlockHeight().getOrThrow()
        } catch (error: Throwable) {
            return
        }

        pendingTransactions.forEach { pendingTx ->
            try {
                val confirmedTransaction = withTimeout(20000) {
                    rpcClient.getTransaction(pendingTx.hash)
                }

                confirmedTransaction.onSuccess { transaction ->
                    if (pendingTx.external) {
                        externalTransactionsToDelete.add(pendingTx.hash)
                    } else {
                        updatedTransactions.add(
                            pendingTx.copy(pending = false, error = transaction.meta?.err?.toString())
                        )
                    }
                }

            } catch (error: Throwable) {
                if (currentBlockHeight <= pendingTx.lastValidBlockHeight) {
                    sendTransaction(pendingTx.base64Encoded)

                    updatedTransactions.add(
                        pendingTx.copy(retryCount = pendingTx.retryCount + 1)
                    )
                } else if (pendingTx.external) {
                    externalTransactionsToDelete.add(pendingTx.hash)
                } else {
                    updatedTransactions.add(
                        pendingTx.copy(pending = false, error = "BlockHash expired")
                    )
                }

                logger.info("getConfirmedTx exception ${error.message ?: error.javaClass.simpleName}")
            }
        }

        storage.deleteExternalTransactions(externalTransactionsToDelete)
        storage.updateTransactions(updatedTransactions)

        val visibleTransactionHashes = updatedTransactions.filterNot { it.external }.map { it.hash }
        if (visibleTransactionHashes.isNotEmpty()) {
            transactionManager.notifyTransactionsUpdate(storage.getFullTransactions(visibleTransactionHashes))
        }
    }

    private fun sendTransaction(encodedTransaction: String) {
        try {
            val connection = rpcUrl.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use {

                val body = "{" +
                        "\"method\": \"sendTransaction\", " +
                        "\"jsonrpc\": \"2.0\", " +
                        "\"id\": ${System.currentTimeMillis()}, " +
                        "\"params\": [" +
                        "\"$encodedTransaction\", " +
                        "{" +
                        "\"encoding\": \"base64\"," +
                        "\"skipPreflight\": false," +
                        "\"preflightCommitment\": \"confirmed\"," +
                        "\"maxRetries\": 0" +
                        "}" +
                        "]" +
                        "}"

                it.write(body.toByteArray())
            }
            connection.inputStream.use {
                BufferedReader(InputStreamReader(it)).use { reader ->
                    reader.readText()
                }
            }
            connection.disconnect()
        } catch (e: Throwable) {
            networkErrorListener?.onNetworkError(
                rpcUrl.toSolanaNetworkError(
                    source = "solana-rpc-pending",
                    method = "sendTransaction",
                    throwable = e
                )
            )
        }
    }

}
