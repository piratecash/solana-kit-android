package io.horizontalsystems.solanakit.transactions

import SplTokenAccountWithPublicKey
import android.util.Log
import com.solana.api.Api
import com.solana.api.SignatureInformation
import com.solana.core.PublicKey
import org.sol4k.Base58
import com.solana.programs.TokenProgram
import getTokenAccountsByOwner
import io.horizontalsystems.solanakit.SolanaKit
import io.horizontalsystems.solanakit.database.transaction.TransactionStorage
import io.horizontalsystems.solanakit.models.FullTransaction
import io.horizontalsystems.solanakit.models.MintAccount
import io.horizontalsystems.solanakit.noderpc.endpoints.getSignaturesForAddress
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

    private var cachedTokenAccounts: List<SplTokenAccountWithPublicKey>? = null
    private var tokenAccountsCacheTime: Long = 0

    private fun updateSyncState(newState: SolanaKit.SyncState) {
        _syncState.value = newState
        listener?.onUpdateTransactionSyncState(newState)
    }

    suspend fun sync() {
        if (_syncState.value is SolanaKit.SyncState.Syncing) return

        updateSyncState(SolanaKit.SyncState.Syncing())

        pendingTransactionSyncer.sync()

        val lastTransactionHash = storage.lastNonPendingTransaction()?.hash

        try {
            val rpcTransactions = getSignaturesFromRpcNode(
                pKey = publicKey,
                lastTransactionHash = lastTransactionHash
            ).apply { Log.d("TransactionSyncer", "rpcTransactions: ${this.size}") }
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
            }.flatten().apply { Log.d("TransactionSyncer", "token transactions: ${this.size}") }
                .mapNotNull { it.signature }
                .mapNotNull { signature ->
                    getTransactionInfo(signature)
                }
            val mintAddresses =
                splTransfers.mapNotNull { it.meta?.preTokenBalances?.firstOrNull()?.mint }.toSet()
                    .toList()
            val mintAccounts = getMintAccounts(mintAddresses)
            val transactions = merge(
                rpcTransactions = rpcTransactions + splTransfers,
                mintAccounts = mintAccounts
            )

            transactionManager.handle(transactions)
            updateSyncState(SolanaKit.SyncState.Synced())
        } catch (exception: Throwable) {
            exception.printStackTrace()
            updateSyncState(SolanaKit.SyncState.NotSynced(exception))
        }
    }

    private fun merge(
        rpcTransactions: List<TransactionResult>,
        mintAccounts: Map<String, MintAccount>
    ): List<FullTransaction> = SolanaTransactionMapper.map(
        userAddress = publicKey.toBase58(),
        rpcTransactions = rpcTransactions,
        mintAccounts = mintAccounts,
    )

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
                println("Using cached token accounts for owner: $publicKey: ${it.size} accounts")
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

    private suspend fun getMintAccounts(mintAddresses: List<String>): Map<String, MintAccount> {
        if (mintAddresses.isEmpty()) {
            return mutableMapOf()
        }

        val publicKeys = mintAddresses.map { PublicKey.valueOf(it) }

        val mintAccounts = mutableMapOf<String, MintAccount>()

        try {
            rpcClient.getMultipleMintAccountsInfo(
                accounts = publicKeys
            ).getOrThrow()?.forEachIndexed { index, account ->
                val owner = account.owner
                val mint = account.data
                if (owner != tokenProgramId || mint == null) return@forEachIndexed
                val mintAddress = mintAddresses.getOrNull(index) ?: return@forEachIndexed

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
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        return mintAccounts
    }

    companion object {
        val tokenProgramId = Base58.encode(TokenProgram.PROGRAM_ID.pubkey)
        const val rpcSignaturesCount = 1000
    }

}
