package io.horizontalsystems.solanakit.transactions

import com.solana.actions.Action
import com.solana.api.sendRawTransaction
import com.solana.core.Account
import com.solana.core.PublicKey
import org.sol4k.Base58
import com.solana.core.TransactionInstruction
import io.horizontalsystems.solanakit.SolanaKit
import io.horizontalsystems.solanakit.core.TokenAccountManager
import io.horizontalsystems.solanakit.database.transaction.TransactionStorage
import io.horizontalsystems.solanakit.models.Address
import io.horizontalsystems.solanakit.models.FullTokenAccount
import io.horizontalsystems.solanakit.models.FullTokenTransfer
import io.horizontalsystems.solanakit.models.FullTransaction
import io.horizontalsystems.solanakit.models.RawTransactionBroadcastResult
import io.horizontalsystems.solanakit.models.RawTransactionBroadcastStatus
import io.horizontalsystems.solanakit.models.RawTransactionRetryMetadata
import io.horizontalsystems.solanakit.models.SignedRawSolanaTransaction
import io.horizontalsystems.solanakit.models.TokenTransfer
import io.horizontalsystems.solanakit.models.Transaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.rx2.await
import org.sol4k.Connection
import org.sol4k.api.Commitment
import java.math.BigDecimal
import java.time.Instant
import java.util.Base64
import java.util.logging.Logger

class TransactionManager(
    address: Address,
    private val storage: TransactionStorage,
    private val rpcAction: Action,
    private val tokenAccountManager: TokenAccountManager,
    rpcUrl: String,
) {

    private val addressString = Base58.encode(address.publicKey.pubkey)
    private val connection = Connection(rpcUrl)
    private val logger = Logger.getLogger("TransactionManager")
    private val _transactionsFlow = MutableStateFlow<List<FullTransaction>>(listOf())
    val transactionsFlow: StateFlow<List<FullTransaction>> = _transactionsFlow

    fun allTransactionsFlow(incoming: Boolean?): Flow<List<FullTransaction>> =
        _transactionsFlow.map { txList ->
            val visibleTxList = txList.visibleTransactions()
            val incoming = incoming ?: return@map visibleTxList

            visibleTxList.filter { fullTransaction ->
                hasSolTransfer(
                    fullTransaction,
                    incoming
                ) || fullTransaction.tokenTransfers.any { it.tokenTransfer.incoming == incoming }
            }
        }.filter { it.isNotEmpty() }

    fun solTransactionsFlow(incoming: Boolean?): Flow<List<FullTransaction>> =
        _transactionsFlow.map { txList ->
            txList.visibleTransactions().filter { hasSolTransfer(it, incoming) }
        }.filter { it.isNotEmpty() }

    fun splTransactionsFlow(mintAddress: String, incoming: Boolean?): Flow<List<FullTransaction>> =
        _transactionsFlow.map { txList ->
            txList.visibleTransactions().filter { fullTransaction ->
                hasSplTransfer(mintAddress, fullTransaction.tokenTransfers, incoming)
            }
        }.filter { it.isNotEmpty() }


    suspend fun getAllTransaction(
        incoming: Boolean?,
        fromHash: String?,
        limit: Int?
    ): List<FullTransaction> =
        storage.getTransactions(incoming, fromHash, limit)

    suspend fun getSolTransaction(
        incoming: Boolean?,
        fromHash: String?,
        limit: Int?
    ): List<FullTransaction> =
        storage.getSolTransactions(incoming, fromHash, limit)

    suspend fun getSplTransaction(
        mintAddress: String,
        incoming: Boolean?,
        fromHash: String?,
        limit: Int?
    ): List<FullTransaction> =
        storage.getSplTransactions(mintAddress, incoming, fromHash, limit)

    suspend fun handle(syncedTransactions: List<FullTransaction>) {
        val existingMintAddresses = mutableListOf<String>()

        if (syncedTransactions.isNotEmpty()) {
            val existingTransactionsMap =
                storage.getFullTransactions(syncedTransactions.map { it.transaction.hash })
                    .groupBy { it.transaction.hash }
            val transactions = syncedTransactions.map { syncedTx ->
                val existingTx = existingTransactionsMap[syncedTx.transaction.hash]?.firstOrNull()

                if (existingTx == null) syncedTx
                else {
                    val syncedTxHeader = syncedTx.transaction
                    val existingTxHeader = existingTx.transaction

                    FullTransaction(
                        transaction = Transaction(
                            hash = syncedTxHeader.hash,
                            timestamp = syncedTxHeader.timestamp,
                            fee = syncedTxHeader.fee,
                            from = syncedTxHeader.from ?: existingTxHeader.from,
                            to = syncedTxHeader.to ?: existingTxHeader.to,
                            amount = syncedTxHeader.amount ?: existingTxHeader.amount,
                            error = syncedTxHeader.error,
                            pending = syncedTxHeader.pending,
                        ),
                        tokenTransfers = syncedTx.tokenTransfers.ifEmpty {
                            for (tokenTransfer in existingTx.tokenTransfers) {
                                existingMintAddresses.add(tokenTransfer.mintAccount.address)
                            }

                            existingTx.tokenTransfers
                        }
                    )
                }
            }

            storage.addTransactions(transactions)
            notifyTransactionsUpdate(transactions)
        }
    }

    fun notifyTransactionsUpdate(transactions: List<FullTransaction>) {
        val visibleTransactions = transactions.visibleTransactions()
        if (visibleTransactions.isNotEmpty()) {
            _transactionsFlow.tryEmit(visibleTransactions)
        }
    }

    private fun hasSolTransfer(fullTransaction: FullTransaction, incoming: Boolean?): Boolean {
        val amount = fullTransaction.transaction.amount ?: return false
        val incoming = incoming ?: return true

        return amount > BigDecimal.ZERO &&
                ((incoming && fullTransaction.transaction.to == addressString) || (!incoming && fullTransaction.transaction.from == addressString))
    }

    private fun hasSplTransfer(
        mintAddress: String,
        tokenTransfers: List<FullTokenTransfer>,
        incoming: Boolean?
    ): Boolean =
        tokenTransfers.any { fullTokenTransfer ->
            if (fullTokenTransfer.mintAccount.address != mintAddress) return false
            val incoming = incoming ?: return@any true

            fullTokenTransfer.tokenTransfer.incoming == incoming
        }

    suspend fun signedSolTransaction(toAddress: Address, amount: Long, signerAccount: Account): SignedRawSolanaTransaction {
        val blockHash = connection.getLatestBlockhashExtended(Commitment.FINALIZED)
        val signedTransaction = rpcAction.signSOL(
            account = signerAccount,
            destination = toAddress.publicKey,
            amount = amount,
            instructions = if (signerAccount.supportsPriorityFees) priorityFeeInstructions() else emptyList(),
            recentBlockHash = blockHash.blockhash
        ).await()

        return signedTransaction.toRawSolanaTransaction(blockHash.blockhash, blockHash.lastValidBlockHeight)
    }

    suspend fun sendSol(toAddress: Address, amount: Long, signerAccount: Account): FullTransaction {
        val signedTransaction = signedSolTransaction(toAddress, amount, signerAccount)
        val transactionHash = sendSignedRawTransaction(signedTransaction)

        val fullTransaction = FullTransaction(
            Transaction(
                hash = transactionHash,
                timestamp = Instant.now().epochSecond,
                fee = SolanaKit.fee,
                from = addressString,
                to = Base58.encode(toAddress.publicKey.pubkey),
                amount = amount.toBigDecimal(),
                pending = true,
                blockHash = signedTransaction.blockHash,
                lastValidBlockHeight = signedTransaction.lastValidBlockHeight,
                base64Encoded = signedTransaction.base64Encoded
            ),
            listOf()
        )

        storage.addTransactions(listOf(fullTransaction))
        notifyTransactionsUpdate(listOf(fullTransaction))

        return fullTransaction
    }

    private fun priorityFeeInstructions(): List<TransactionInstruction> {
        val computeUnitLimit = ComputeBudgetProgram.setComputeUnitLimit(units = 300_000)
        val computeUnitPrice = ComputeBudgetProgram.setComputeUnitPrice(microLamports = 500_000)
        return listOf(computeUnitLimit, computeUnitPrice)
    }

    suspend fun signedSplTransaction(
        mintAddress: Address,
        toAddress: Address,
        amount: Long,
        signerAccount: Account
    ): SignedRawSolanaTransaction {
        val mintAddressString = Base58.encode(mintAddress.publicKey.pubkey)
        return signedSplTransaction(
            mintAddress = mintAddress,
            toAddress = toAddress,
            amount = amount,
            signerAccount = signerAccount,
            fullTokenAccount = fullTokenAccount(mintAddressString),
        )
    }

    suspend fun sendSpl(
        mintAddress: Address,
        toAddress: Address,
        amount: Long,
        signerAccount: Account
    ): FullTransaction {
        val mintAddressString = Base58.encode(mintAddress.publicKey.pubkey)
        val fullTokenAccount = fullTokenAccount(mintAddressString)
        val mintAccount = fullTokenAccount.mintAccount
        val signedTransaction = signedSplTransaction(
            mintAddress = mintAddress,
            toAddress = toAddress,
            amount = amount,
            signerAccount = signerAccount,
            fullTokenAccount = fullTokenAccount,
        )
        val transactionHash = sendSignedRawTransaction(signedTransaction)

        val fullTransaction = FullTransaction(
            Transaction(
                hash = transactionHash,
                timestamp = Instant.now().epochSecond,
                from = addressString,
                to = Base58.encode(toAddress.publicKey.pubkey),
                fee = SolanaKit.fee,
                pending = true,
                blockHash = signedTransaction.blockHash,
                lastValidBlockHeight = signedTransaction.lastValidBlockHeight,
                base64Encoded = signedTransaction.base64Encoded
            ),
            listOf(
                FullTokenTransfer(
                    TokenTransfer(
                        transactionHash = transactionHash,
                        mintAddress = mintAddressString,
                        incoming = false,
                        amount = -amount.toBigDecimal()
                    ),
                    mintAccount
                )
            )
        )

        storage.addTransactions(listOf(fullTransaction))
        notifyTransactionsUpdate(listOf(fullTransaction))

        return fullTransaction
    }

    private suspend fun signedSplTransaction(
        mintAddress: Address,
        toAddress: Address,
        amount: Long,
        signerAccount: Account,
        fullTokenAccount: FullTokenAccount,
    ): SignedRawSolanaTransaction {
        val tokenAccount = fullTokenAccount.tokenAccount
        val blockHash = connection.getLatestBlockhashExtended(Commitment.FINALIZED)

        val signedTransaction = rpcAction.signSPLTokens(
            mintAddress = mintAddress.publicKey,
            fromPublicKey = PublicKey(tokenAccount.address),
            destinationAddress = toAddress.publicKey,
            amount = amount,
            account = signerAccount,
            allowUnfundedRecipient = true,
            instructions = if (signerAccount.supportsPriorityFees) priorityFeeInstructions() else emptyList(),
            recentBlockHash = blockHash.blockhash
        ).await()

        return signedTransaction.toRawSolanaTransaction(blockHash.blockhash, blockHash.lastValidBlockHeight)
    }

    private fun fullTokenAccount(mintAddressString: String): FullTokenAccount =
        tokenAccountManager.getFullTokenAccountByMintAddress(mintAddressString)
            ?: throw Exception("TokenAccount not found for $mintAddressString")

    suspend fun broadcastRawTransaction(
        rawTransaction: ByteArray,
        retryMetadata: RawTransactionRetryMetadata?
    ): RawTransactionBroadcastResult {
        val signature = rawTransactionSignature(rawTransaction)

        return try {
            val rpcSignature = rpcAction.api.sendRawTransactionWithoutRetries(rawTransaction).getOrThrow()
            logSignatureMismatch(rpcSignature, signature)
            storage.deleteExternalTransaction(signature)
            RawTransactionBroadcastResult(signature, RawTransactionBroadcastStatus.Submitted)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            if (error.isKnownSubmittedTransactionError()) {
                storage.deleteExternalTransaction(signature)
                RawTransactionBroadcastResult(signature, RawTransactionBroadcastStatus.AlreadyKnown)
            } else if (error.isExpiredBlockhashError()) {
                throw error
            } else if (retryMetadata == null) {
                throw error
            } else {
                storage.saveExternalTransaction(
                    Transaction(
                        hash = signature,
                        timestamp = Instant.now().epochSecond,
                        fee = null,
                        pending = true,
                        blockHash = retryMetadata.blockHash,
                        lastValidBlockHeight = retryMetadata.lastValidBlockHeight,
                        base64Encoded = Base64.getEncoder().encodeToString(rawTransaction),
                        external = true,
                    )
                )
                RawTransactionBroadcastResult(signature, RawTransactionBroadcastStatus.Queued)
            }
        }
    }

    private suspend fun sendSignedRawTransaction(signedTransaction: SignedRawSolanaTransaction): String {
        val transactionHash = rpcAction.api.sendRawTransaction(signedTransaction.raw).getOrThrow()
        logSignatureMismatch(transactionHash, signedTransaction.signature)
        return signedTransaction.signature
    }

    private fun logSignatureMismatch(actual: String, expected: String) {
        if (actual != expected) {
            logger.warning("RPC returned transaction signature $actual but expected $expected")
        }
    }

    private fun SignedSolanaTransactionData.toRawSolanaTransaction(
        blockHash: String,
        lastValidBlockHeight: Long,
    ) = SignedRawSolanaTransaction(
        raw = raw,
        base64Encoded = base64Encoded,
        signature = signature,
        fee = SolanaKit.fee,
        blockHash = blockHash,
        lastValidBlockHeight = lastValidBlockHeight,
    )

    private fun Throwable.isKnownSubmittedTransactionError(): Boolean {
        val message = message?.lowercase() ?: return false
        return knownSubmittedTransactionMessages.any { message.contains(it) }
    }

    private fun Throwable.isExpiredBlockhashError(): Boolean {
        val message = message?.lowercase() ?: return false
        return expiredBlockhashMessages.any { message.contains(it) }
    }

    private fun List<FullTransaction>.visibleTransactions(): List<FullTransaction> =
        filterNot { it.transaction.external }

    companion object {
        private val knownSubmittedTransactionMessages = listOf(
            "already processed",
            "already been processed",
            "duplicate signature",
        )
        private val expiredBlockhashMessages = listOf(
            "blockhash not found",
            "block height exceeded",
            "transaction has expired",
        )
    }
}
