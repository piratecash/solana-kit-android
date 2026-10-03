package io.horizontalsystems.solanakit.transactions

import SplTokenAccountWithPublicKey
import co.touchlab.kermit.Logger
import com.solana.api.Api
import com.solana.api.SignatureInformation
import com.solana.core.PublicKey
import org.sol4k.Base58
import com.solana.programs.TokenProgram
import getTokenAccountsByOwner
import io.horizontalsystems.solanakit.SolanaKit
import io.horizontalsystems.solanakit.database.transaction.TransactionStorage
import io.horizontalsystems.solanakit.database.transaction.dao.TransactionsDao.TransactionKey
import io.horizontalsystems.solanakit.models.FullTransaction
import io.horizontalsystems.solanakit.models.MintAccount
import io.horizontalsystems.solanakit.noderpc.endpoints.getSignaturesForAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface ITransactionListener {
    fun onUpdateTransactionSyncState(syncState: SolanaKit.SyncState)
}

/***
 * @param limitFirstTimeTransactionCount - limit for the first sync when there are no transactions in the local storage
 * @param limitTimeTransactionCount - limit for the next syncs when there are already transactions in the local storage
 *
 * -1 means no limit
 */
class TransactionSyncer(
    private val publicKey: PublicKey,
    private val rpcClient: Api,
    private val storage: TransactionStorage,
    private val transactionManager: TransactionManager,
    private val pendingTransactionSyncer: PendingTransactionSyncer,
    private val limitFirstTimeTransactionCount: Int,
    private val limitTimeTransactionCount: Int
) {
    private val _syncState = MutableStateFlow<SolanaKit.SyncState>(
        SolanaKit.SyncState.NotSynced(SolanaKit.SyncError.NotStarted())
    )
    val syncState: StateFlow<SolanaKit.SyncState> = _syncState.asStateFlow()

    var listener: ITransactionListener? = null

    private val logger = Logger.withTag("SolanaKit")
    private val userAddress = publicKey.toBase58()
    private var cachedTokenAccounts: List<SplTokenAccountWithPublicKey>? = null
    private var tokenAccountsCacheTime: Long = 0

    private fun updateSyncState(newState: SolanaKit.SyncState) {
        _syncState.value = newState
        listener?.onUpdateTransactionSyncState(newState)
    }

    suspend fun sync() {
        if (_syncState.value is SolanaKit.SyncState.Syncing) return

        updateSyncState(SolanaKit.SyncState.Syncing())

        try {
            pendingTransactionSyncer.sync()

            val lastTransactionHash = storage.lastNonPendingTransaction()?.hash

            val rpcTransactions = getSignaturesFromRpcNode(
                pKey = publicKey,
                lastTransactionHash = lastTransactionHash
            ).apply { logger.d { "rpcTransactions: ${this.size}" } }
                .mapNotNull { it.signature }
                .mapNotNull { signature ->
                    getTransactionInfo(signature)
                }
            val splTransfers = getTokenAccountsByOwner().map {
                SplTokenAccountWithPublicKey(it.publicKey)
            }.map {
                getSignaturesFromRpcNode(
                    pKey = PublicKey.valueOf(it.publicKey),
                    lastTransactionHash = lastTransactionHash
                )
            }.flatten().apply { logger.d { "token transactions: ${this.size}" } }
                .mapNotNull { it.signature }
                .mapNotNull { signature ->
                    getTransactionInfo(signature)
                }
            val results = rpcTransactions + splTransfers
            val mintAccounts = getMintAccounts(results).getOrElse {
                logger.w(it) { "Failed to fetch mint accounts" }
                emptyMap()
            }

            transactionManager.handle(toFullTransactions(results, mintAccounts))
            repairStoredTokenTransfers()
            updateSyncState(SolanaKit.SyncState.Synced())
        } catch (exception: Throwable) {
            logger.w(exception) { "Transaction sync failed" }
            updateSyncState(SolanaKit.SyncState.NotSynced(exception))
        }
    }

    private fun toFullTransactions(
        results: List<TransactionResult>,
        mintAccounts: Map<String, MintAccount>
    ): List<FullTransaction> = SolanaTransactionMapper.map(
        userAddress = userAddress,
        rpcTransactions = results,
        mintAccounts = mintAccounts,
    )

    // Rows stored before token transfers were parsed by owner are re-fetched once, a batch per sync.
    private suspend fun repairStoredTokenTransfers() {
        try {
            repairNextTokenTransferBatch()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            logger.w(error) { "Token transfer repair failed" }
        }
    }

    private suspend fun repairNextTokenTransferBatch() {
        val cursor = storage.tokenTransferRepairCursor()
        if (cursor == REPAIR_DONE) return
        val position = parseRepairCursor(cursor)
        val batch = storage.tokenTransferTransactionHashes(position?.timestamp, position?.hash, limitTimeTransactionCount)
        if (batch.isEmpty()) {
            storage.saveTokenTransferRepairCursor(REPAIR_DONE)
            return
        }

        val fetched = fetchUntilFailure(batch)
        val lastProcessed = fetched.lastOrNull()?.first ?: return
        val results = fetched.mapNotNull { it.second }
        val mintAccounts = getMintAccounts(results).getOrElse {
            currentCoroutineContext().ensureActive()
            logger.w(it) { "Token transfer repair postponed" }
            return
        }
        transactionManager.handle(
            syncedTransactions = toFullTransactions(results, mintAccounts),
            replaceTokenTransfersOf = SolanaTransactionMapper.hashesWithoutUserTokenChanges(userAddress, results),
        )
        storage.saveTokenTransferRepairCursor("${lastProcessed.timestamp}:${lastProcessed.hash}")
    }

    // A transaction the node does not know, or returns without meta, cannot be re-mapped reliably:
    // it counts as processed with no result, so its stored row stays untouched. Any other failure
    // ends the batch to be retried on the next sync.
    private suspend fun fetchUntilFailure(batch: List<TransactionKey>): List<Pair<TransactionKey, TransactionResult?>> {
        val fetched = mutableListOf<Pair<TransactionKey, TransactionResult?>>()
        for (key in batch) {
            val result = rpcClient.getTransaction(key.hash)
            val error = result.exceptionOrNull()
            if (error != null && error !is TransactionNotFoundException) {
                // The RPC helper turns a cancellation during the request into a plain failure.
                currentCoroutineContext().ensureActive()
                break
            }
            fetched.add(key to result.getOrNull()?.takeIf { it.meta != null })
        }
        return fetched
    }

    private fun parseRepairCursor(cursor: String?): TransactionKey? {
        val timestamp = cursor?.substringBefore(':')?.toLongOrNull() ?: return null
        val hash = cursor.substringAfter(':', missingDelimiterValue = "")
        return if (hash.isEmpty()) null else TransactionKey(timestamp, hash)
    }

    private suspend fun getSignaturesFromRpcNode(
        pKey: PublicKey,
        lastTransactionHash: String?
    ): List<SignatureInformation> {
        val signatureObjects = mutableListOf<SignatureInformation>()
        var signatureObjectsChunk = listOf<SignatureInformation>()

        do {
            val lastSignature = signatureObjectsChunk.lastOrNull()?.signature
            signatureObjectsChunk = getSignaturesChunk(
                lastTransactionHash = lastTransactionHash,
                pKey = pKey,
                before = lastSignature
            )
            signatureObjects.addAll(signatureObjectsChunk)

        } while (signatureObjectsChunk.size == rpcSignaturesCount)

        var takFirst = if (lastTransactionHash == null) limitFirstTimeTransactionCount else limitTimeTransactionCount
        if (takFirst == -1) { // no limit
            takFirst = signatureObjects.size
        }
        return signatureObjects.take(takFirst)
    }

    private suspend fun getTokenAccountsByOwner(): List<SplTokenAccountWithPublicKey> {
        val now = System.currentTimeMillis()
        val cacheValidDuration = 5 * 60 * 1000 // 5 minutes

        cachedTokenAccounts?.let {
            if (now - tokenAccountsCacheTime < cacheValidDuration) {
                logger.d { "Using cached token accounts: ${it.size}" }
                return it
            }
        }

        val accounts = rpcClient.getTokenAccountsByOwner(publicKey).getOrNull() ?: listOf()
        if(accounts.isNotEmpty()) {
            cachedTokenAccounts = accounts
            tokenAccountsCacheTime = now
        }

        return accounts
    }

    private suspend fun getTransactionInfo(signature: String): TransactionResult? =
        rpcClient.getTransaction(signature).getOrNull()

    private suspend fun getSignaturesChunk(
        lastTransactionHash: String?,
        pKey: PublicKey,
        before: String? = null
    ): List<SignatureInformation> {
        return rpcClient.getSignaturesForAddress(
            account = pKey,
            until = lastTransactionHash,
            before = before,
            limit = rpcSignaturesCount
        ).getOrNull() ?: listOf()
    }

    private suspend fun getMintAccounts(results: List<TransactionResult>): Result<Map<String, MintAccount>> {
        val addresses = SolanaTransactionMapper.userMints(userAddress, results).toList()
        if (addresses.isEmpty()) {
            return Result.success(emptyMap())
        }

        val publicKeys = addresses.map { PublicKey.valueOf(it) }

        val mintAccounts = mutableMapOf<String, MintAccount>()

        try {
            rpcClient.getMultipleMintAccountsInfo(
                accounts = publicKeys
            ).getOrThrow()?.forEachIndexed { index, account ->
                val owner = account.owner
                val mint = account.data
                if (owner != tokenProgramId || mint == null) return@forEachIndexed
                val mintAddress = addresses.getOrNull(index) ?: return@forEachIndexed

                val isNft = when {
                    mint.parsed.info.decimals != 0 -> false
                    mint.parsed.info.supply == "1" && mint.parsed.info.mintAuthority == null -> true
                    else -> false
                }
                mintAccounts[mintAddress] = MintAccount(
                    address = mintAddress,
                    decimals = mint.parsed.info.decimals,
                    supply = mint.parsed.info.supply.toLongOrNull(),
                    isNft = isNft,
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Throwable) {
            return Result.failure(e)
        }
        return Result.success(mintAccounts)
    }

    companion object {
        val tokenProgramId = Base58.encode(TokenProgram.PROGRAM_ID.pubkey)
        const val rpcSignaturesCount = 1000
        private const val REPAIR_DONE = "done"
    }

}
