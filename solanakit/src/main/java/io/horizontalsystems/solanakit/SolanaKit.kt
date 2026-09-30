package io.horizontalsystems.solanakit

import co.touchlab.kermit.Logger
import com.solana.actions.Action
import com.solana.api.Api
import com.solana.networking.HttpNetworkingRouter
import com.solana.networking.Network
import io.horizontalsystems.solanakit.core.BalanceManager
import io.horizontalsystems.solanakit.core.ISyncListener
import io.horizontalsystems.solanakit.core.SolanaDatabaseManager
import io.horizontalsystems.solanakit.core.SyncManager
import io.horizontalsystems.solanakit.core.TokenAccountManager
import io.horizontalsystems.solanakit.database.clearDatabaseGroup
import io.horizontalsystems.solanakit.database.main.MainStorage
import io.horizontalsystems.solanakit.database.migrateDatabaseGroup
import io.horizontalsystems.solanakit.database.requireValidDatabaseGroup
import io.horizontalsystems.solanakit.database.transaction.TransactionStorage
import io.horizontalsystems.solanakit.models.Address
import io.horizontalsystems.solanakit.models.FullTokenAccount
import io.horizontalsystems.solanakit.models.FullTransaction
import io.horizontalsystems.solanakit.models.RawTransactionBroadcastResult
import io.horizontalsystems.solanakit.models.RawTransactionRetryMetadata
import io.horizontalsystems.solanakit.models.RpcSource
import io.horizontalsystems.solanakit.models.SignedRawSolanaTransaction
import io.horizontalsystems.solanakit.models.Transaction
import io.horizontalsystems.solanakit.network.ConnectionManager
import io.horizontalsystems.solanakit.network.SolanaNetworkErrorListener
import io.horizontalsystems.solanakit.network.emitSafely
import io.horizontalsystems.solanakit.network.toSolanaNetworkError
import io.horizontalsystems.solanakit.noderpc.ApiSyncer
import io.horizontalsystems.solanakit.transactions.PendingTransactionSyncer
import io.horizontalsystems.solanakit.transactions.TransactionManager
import io.horizontalsystems.solanakit.transactions.TransactionSyncer
import io.horizontalsystems.sqlcipher.room.DatabaseKeyMismatchException
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationConflictException
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationInProgressException
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationRequiredException
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationResult
import io.horizontalsystems.sqlcipher.room.InsufficientDatabaseMigrationSpaceException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.sol4k.Base58
import org.sol4k.Connection
import org.sol4k.VersionedTransaction
import org.sol4k.api.Commitment
import java.math.BigDecimal
import java.time.Instant
import java.util.Base64
import java.util.Objects

class SolanaKit(
    private val apiSyncer: ApiSyncer,
    private val balanceManager: BalanceManager,
    private val tokenAccountManager: TokenAccountManager,
    private val transactionManager: TransactionManager,
    private val syncManager: SyncManager,
    private val rpcSource: RpcSource,
    private val address: Address,
) : ISyncListener {

    private var scope: CoroutineScope? = null
    private val logger = Logger.withTag("SolanaKit")

    private val _balanceSyncStateFlow = MutableStateFlow(syncState)
    private val _tokenBalanceSyncStateFlow = MutableStateFlow(tokenBalanceSyncState)
    private val _transactionsSyncStateFlow = MutableStateFlow(transactionsSyncState)

    private val _lastBlockHeightFlow = MutableStateFlow(lastBlockHeight)
    private val _balanceFlow = MutableStateFlow(balance)

    val isMainnet: Boolean = rpcSource.endpoint.network == Network.mainnetBeta
    val receiveAddress = Base58.encode(address.publicKey.pubkey)

    val lastBlockHeight: Long?
        get() = apiSyncer.lastBlockHeight
    val lastBlockHeightFlow: StateFlow<Long?> = _lastBlockHeightFlow

    // Balance API
    val syncState: SyncState
        get() = syncManager.balanceSyncState
    val balanceSyncStateFlow: StateFlow<SyncState> = _balanceSyncStateFlow
    val balance: Long?
        get() = balanceManager.balance
    val balanceFlow: StateFlow<Long?> = _balanceFlow

    // Token accounts API
    val tokenBalanceSyncState: SyncState
        get() = syncManager.tokenBalanceSyncState
    val tokenBalanceSyncStateFlow: StateFlow<SyncState> = _tokenBalanceSyncStateFlow
    val fungibleTokenAccountsFlow: Flow<List<FullTokenAccount>> =
        tokenAccountManager.newTokenAccountsFlow.map { tokenAccounts ->
            tokenAccounts.filter { !it.mintAccount.isNft }
        }
    val nonFungibleTokenAccountsFlow: Flow<List<FullTokenAccount>> =
        tokenAccountManager.tokenAccountsFlow.map { tokenAccounts ->
            tokenAccounts.filter { it.mintAccount.isNft }
        }

    fun estimateFee(hexEncoded: ByteArray): BigDecimal {
        val base64Encoded = Base64.getEncoder().encodeToString(hexEncoded)
        val versionedTx = VersionedTransaction.from(base64Encoded)
        return versionedTx.calculateFee(baseFeeLamports)
    }

    fun sendRawTransaction(hexEncoded: ByteArray, signer: Signer): FullTransaction {
        val base64Encoded = Base64.getEncoder().encodeToString(hexEncoded)
        val versionedTx = VersionedTransaction.from(base64Encoded)
        val signature = Base58.encode(signer.account.sign(versionedTx.message.serialize()))
        versionedTx.addSignature(signature)
        val base64WithSignature = Base64.getEncoder().encodeToString(versionedTx.serialize())
        val connection = Connection(rpcSource.url.toString())
        val blockHash = connection.getLatestBlockhashExtended(Commitment.FINALIZED)
        val transactionHash = connection.sendTransaction(versionedTx)
        val fullTransaction = FullTransaction(
            transaction = Transaction(
                hash = transactionHash,
                timestamp = Instant.now().epochSecond,
                fee = versionedTx.calculateFee(baseFeeLamports),
                from = Base58.encode(address.publicKey.pubkey),
                to = null,
                amount = null,
                pending = true,
                blockHash = blockHash.blockhash,
                lastValidBlockHeight = blockHash.lastValidBlockHeight,
                base64Encoded = base64WithSignature
            ),
            listOf()
        )
        return fullTransaction
    }

    fun tokenAccount(mintAddress: String): FullTokenAccount? =
        tokenAccountManager.fullTokenAccount(mintAddress)

    fun tokenAccountFlow(mintAddress: String): Flow<FullTokenAccount> =
        tokenAccountManager.tokenBalanceFlow(mintAddress)

    // Transactions API
    val transactionsSyncState: SyncState
        get() = syncManager.transactionsSyncState
    val transactionsSyncStateFlow: StateFlow<SyncState> = _transactionsSyncStateFlow

    fun allTransactionsFlow(incoming: Boolean?): Flow<List<FullTransaction>> =
        transactionManager.allTransactionsFlow(incoming)

    fun solTransactionsFlow(incoming: Boolean?): Flow<List<FullTransaction>> =
        transactionManager.solTransactionsFlow(incoming)

    fun splTransactionsFlow(mintAddress: String, incoming: Boolean?): Flow<List<FullTransaction>> =
        transactionManager.splTransactionsFlow(mintAddress, incoming)

    fun start() {
        scope = CoroutineScope(Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            logger.d { "Coroutine error: ${throwable.message}" }
        })
        scope?.launch {
            syncManager.start(this)
        }
    }

    fun stop() {
        syncManager.stop()
        scope?.cancel()
    }

    fun pause() {
        syncManager.pause()
    }

    fun resume() {
        syncManager.resume()
    }

    fun refresh(): Boolean {
        if (scope?.isActive != true) return false

        scope?.launch {
            syncManager.refresh(this)
        }
        return true
    }

    fun debugInfo(): String {
        val lines = mutableListOf<String>()
        lines.add("ADDRESS: $address")
        return lines.joinToString { "\n" }
    }

    fun statusInfo(): Map<String, Any> = buildMap {
        put("RPC Source", apiSyncer.source)
        put("Last Block Height", lastBlockHeight ?: 0L)
        put("Balance Sync State", syncState)
        put("Token Sync State", tokenBalanceSyncState)
        put("Transaction Sync State", transactionsSyncState)
    }

    suspend fun addTokenAccount(mintAddress: String, decimals: Int) {
        tokenAccountManager.addTokenAccount(receiveAddress, mintAddress, decimals)

        refresh()
    }

    override fun onUpdateLastBlockHeight(lastBlockHeight: Long) {
        _lastBlockHeightFlow.tryEmit(lastBlockHeight)
    }

    override fun onUpdateBalanceSyncState(syncState: SyncState) {
        _balanceSyncStateFlow.tryEmit(syncState)
    }

    override fun onUpdateBalance(balance: Long) {
        _balanceFlow.tryEmit(balance)
    }

    override fun onUpdateTokenSyncState(syncState: SyncState) {
        _tokenBalanceSyncStateFlow.tryEmit(syncState)
    }

    override fun onUpdateTransactionSyncState(syncState: SyncState) {
        _transactionsSyncStateFlow.tryEmit(syncState)
    }

    suspend fun getAllTransactions(
        incoming: Boolean? = null,
        fromHash: String? = null,
        limit: Int? = null
    ): List<FullTransaction> =
        transactionManager.getAllTransaction(incoming, fromHash, limit)

    suspend fun getSolTransactions(
        incoming: Boolean? = null,
        fromHash: String? = null,
        limit: Int? = null
    ): List<FullTransaction> =
        transactionManager.getSolTransaction(incoming, fromHash, limit)

    suspend fun getSplTransactions(
        mintAddress: String,
        incoming: Boolean? = null,
        fromHash: String? = null,
        limit: Int? = null
    ): List<FullTransaction> =
        transactionManager.getSplTransaction(mintAddress, incoming, fromHash, limit)

    suspend fun signedSolTransaction(
        toAddress: Address,
        amount: Long,
        signer: Signer
    ): SignedRawSolanaTransaction =
        transactionManager.signedSolTransaction(toAddress, amount, signer.account)

    suspend fun signedSplTransaction(
        mintAddress: Address,
        toAddress: Address,
        amount: Long,
        signer: Signer
    ): SignedRawSolanaTransaction =
        transactionManager.signedSplTransaction(mintAddress, toAddress, amount, signer.account)

    suspend fun broadcastRawTransaction(
        rawTransaction: ByteArray,
        retryMetadata: RawTransactionRetryMetadata? = null
    ): RawTransactionBroadcastResult =
        transactionManager.broadcastRawTransaction(rawTransaction, retryMetadata)

    suspend fun sendSol(toAddress: Address, amount: Long, signer: Signer): FullTransaction =
        transactionManager.sendSol(toAddress, amount, signer.account)

    suspend fun sendSpl(
        mintAddress: Address,
        toAddress: Address,
        amount: Long,
        signer: Signer
    ): FullTransaction =
        transactionManager.sendSpl(mintAddress, toAddress, amount, signer.account)

    fun fungibleTokenAccounts(): List<FullTokenAccount> =
        tokenAccountManager.tokenAccounts().filter { !it.mintAccount.isNft }

    fun nonFungibleTokenAccounts(): List<FullTokenAccount> =
        tokenAccountManager.tokenAccounts().filter { it.mintAccount.isNft }

    sealed class SyncState {
        class Synced : SyncState()
        class NotSynced(val error: Throwable) : SyncState()
        class Syncing(val progress: Double? = null) : SyncState()

        override fun toString(): String = when (this) {
            is Syncing -> "Syncing ${progress?.let { "${it * 100}" } ?: ""}"
            is NotSynced -> "NotSynced ${error.javaClass.simpleName} - message: ${error.message}"
            is Synced -> "Synced"
        }

        override fun equals(other: Any?): Boolean {
            if (other !is SyncState)
                return false

            if (other.javaClass != this.javaClass)
                return false

            if (other is Syncing && this is Syncing) {
                return other.progress == this.progress
            }

            return true
        }

        override fun hashCode(): Int {
            if (this is Syncing) {
                return Objects.hashCode(this.progress)
            }
            return Objects.hashCode(this.javaClass.name)
        }
    }

    open class SyncError : Exception() {
        class NotStarted : SyncError()
        class NoNetworkConnection : SyncError()
    }

    companion object {
        val baseFeeLamports = 5000
        val fee = BigDecimal(0.000155)

        // Solana network will not store a SOL account with less than ~0.001 SOL.
        // Which means you can't have a SOL account with 0 SOL stored on the network.
        val accountRentAmount = BigDecimal(0.001)


        /**
         * Opens the wallet's two databases, which [migrateDatabase] must have encrypted with the same
         * [databaseKey] first. [databaseKey] must be exactly 32 bytes and [walletId] must be non-blank and
         * free of path separators, otherwise [IllegalArgumentException] is thrown before any I/O.
         *
         * Recovery: [DatabaseMigrationRequiredException] or [DatabaseMigrationInProgressException] mean
         * [migrateDatabase] has to run; [DatabaseKeyMismatchException] keeps the databases and is only
         * recoverable through [clear] plus a new key, which loses the stored wallet data.
         */
        suspend fun getInstance(
            context: PlatformContext,
            addressString: String,
            rpcSource: RpcSource,
            walletId: String,
            databaseKey: ByteArray,
            limitFirstTimeTransactionCount: Int = -1,
            limitTimeTransactionCount: Int = -1,
            networkErrorListener: SolanaNetworkErrorListener? = null
        ): SolanaKit {
            requireValidDatabaseGroup(walletId, databaseKey)

            val router = HttpNetworkingRouter(rpcSource.endpoint) { requestError ->
                networkErrorListener.emitSafely {
                    requestError.toSolanaNetworkError(source = "solana-rpc")
                }
            }
            val connectionManager = ConnectionManager(context)

            val mainDatabase = SolanaDatabaseManager.getMainDatabase(context, walletId, databaseKey)
            val mainStorage = MainStorage(mainDatabase)

            val rpcApiClient = Api(router)
            val rpcAction = Action(rpcApiClient, listOf())
            val apiSyncer = ApiSyncer(
                rpcApiClient,
                rpcSource.syncInterval,
                connectionManager,
                mainStorage,
                mainStorage.getLastBlockHeight()
            )
            val address = Address(addressString)

            val balanceManager = BalanceManager(
                address.publicKey,
                rpcApiClient,
                mainStorage,
                mainStorage.getBalance()
            )

            val transactionDatabase =
                SolanaDatabaseManager.getTransactionDatabase(context, walletId, databaseKey)
            val transactionStorage = TransactionStorage(transactionDatabase, addressString)
            val tokenAccountManager = TokenAccountManager(
                walletAddress = addressString,
                rpcClient = rpcApiClient,
                storage = transactionStorage,
                mainStorage = mainStorage
            )
            val transactionManager =
                TransactionManager(
                    address = address,
                    storage = transactionStorage,
                    rpcAction = rpcAction,
                    tokenAccountManager = tokenAccountManager,
                    rpcUrl = rpcSource.url.toString()
                )
            val pendingTransactionSyncer =
                PendingTransactionSyncer(
                    rpcApiClient,
                    transactionStorage,
                    transactionManager,
                    rpcSource.endpoint.url,
                    networkErrorListener
                )
            val transactionSyncer = TransactionSyncer(
                publicKey = address.publicKey,
                rpcClient = rpcApiClient,
                storage = transactionStorage,
                transactionManager = transactionManager,
                pendingTransactionSyncer = pendingTransactionSyncer,
                limitFirstTimeTransactionCount = limitFirstTimeTransactionCount,
                limitTimeTransactionCount = limitTimeTransactionCount
            )

            val syncManager = SyncManager(
                apiSyncer,
                balanceManager,
                tokenAccountManager,
                transactionSyncer,
                transactionManager
            )

            val kit = SolanaKit(
                apiSyncer,
                balanceManager,
                tokenAccountManager,
                transactionManager,
                syncManager,
                rpcSource,
                address
            )
            syncManager.listener = kit

            tokenAccountManager.reloadFullTokenAccounts()

            return kit
        }

        /**
         * Encrypts the wallet's existing plaintext databases with [databaseKey] (exactly 32 bytes), keeping
         * their data, and recovers an interrupted migration. Call it before [getInstance] for this
         * [walletId], with the same key; it is idempotent and accepts the same arguments, checked the same
         * way before any I/O.
         *
         * Failures:
         * - [DatabaseKeyMismatchException]: the databases were encrypted with another key and are kept
         *   unchanged; only [clear] plus a new key recovers, losing the stored wallet data;
         * - [DatabaseMigrationConflictException]: another migration or clear is running, so retry later; if
         *   the group is mixed (one plaintext and one encrypted database), call [clear] instead — the
         *   wallet data is lost and resynced;
         * - [InsufficientDatabaseMigrationSpaceException]: free some space and retry, the plaintext
         *   databases are kept unchanged.
         */
        suspend fun migrateDatabase(
            context: PlatformContext,
            walletId: String,
            databaseKey: ByteArray,
        ): DatabaseMigrationResult = migrateDatabaseGroup(context, walletId, databaseKey)

        /**
         * Deletes both database files of [walletId] together with any leftovers of an interrupted
         * migration. Throws [IllegalArgumentException] for a blank [walletId] or one containing a path
         * separator, and [DatabaseMigrationConflictException] while another migration or clear runs in the
         * same directory; retry later. Stop the kit first.
         */
        fun clear(context: PlatformContext, walletId: String) {
            clearDatabaseGroup(context, walletId)
        }
    }

}
