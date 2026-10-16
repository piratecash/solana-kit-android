package io.horizontalsystems.solanakit.core

import com.solana.api.Api
import com.solana.core.PublicKey
import org.sol4k.Base58
import com.solana.models.buffer.AccountInfoData
import getTokenAccountBalanceWithRepeat
import getParsedTokenAccountsByOwner
import io.horizontalsystems.solanakit.SolanaKit
import io.horizontalsystems.solanakit.database.main.MainStorage
import io.horizontalsystems.solanakit.database.transaction.TransactionStorage
import io.horizontalsystems.solanakit.models.AccountInfoFixed
import io.horizontalsystems.solanakit.models.FullTokenAccount
import io.horizontalsystems.solanakit.models.MintAccount
import io.horizontalsystems.solanakit.models.TokenAccount
import io.horizontalsystems.solanakit.transactions.getMultipleAccountsFixed
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import kotlin.concurrent.Volatile

interface ITokenAccountListener {
    fun onUpdateTokenSyncState(value: SolanaKit.SyncState)
}

class TokenAccountManager(
    private val walletAddress: String,
    private val rpcClient: Api,
    private val storage: TransactionStorage,
    private val mainStorage: MainStorage
) {

    var syncState: SolanaKit.SyncState =
        SolanaKit.SyncState.NotSynced(SolanaKit.SyncError.NotStarted())
        private set(value) {
            if (value != field) {
                field = value
                listener?.onUpdateTokenSyncState(value)
            }
        }

    var listener: ITokenAccountListener? = null

    private val _newTokenAccountsFlow = MutableStateFlow<List<FullTokenAccount>>(listOf())
    val newTokenAccountsFlow: StateFlow<List<FullTokenAccount>> = _newTokenAccountsFlow

    private val _tokenAccountsUpdatedFlow = MutableStateFlow<List<FullTokenAccount>>(listOf())
    val tokenAccountsFlow: StateFlow<List<FullTokenAccount>> = _tokenAccountsUpdatedFlow

    // mirror of the stored token accounts, kept for the synchronous kit API
    @Volatile
    private var fullTokenAccounts: List<FullTokenAccount> = listOf()
    private val mirrorMutex = Mutex()

    fun tokenBalanceFlow(mintAddress: String): Flow<FullTokenAccount> = _tokenAccountsUpdatedFlow
        .map { tokenAccounts ->
            tokenAccounts.firstOrNull {
                it.mintAccount.address == mintAddress
            }
        }
        .filterNotNull()

    suspend fun reloadFullTokenAccounts() = mirrorMutex.withLock {
        fullTokenAccounts = storage.getFullTokenAccounts()
    }

    // Non-cancellable: a cancel after the commit would leave the mirror serving a stale balance until the next sync.
    private suspend fun persistAndRefreshMirror(emitUpdate: Boolean, write: suspend () -> Unit) {
        withContext(NonCancellable) {
            write()
            reloadFullTokenAccounts()
            if (emitUpdate) {
                _tokenAccountsUpdatedFlow.tryEmit(fullTokenAccounts)
            }
        }
        // Without a dispatcher switch the block above returns normally even after stop(), so callers
        // would run on and overwrite the state the stopped kit already reported.
        currentCoroutineContext().ensureActive()
    }

    fun fullTokenAccount(mintAddress: String): FullTokenAccount? =
        fullTokenAccounts.firstOrNull { it.mintAccount.address == mintAddress }

    fun stop(error: Throwable? = null) {
        syncState = SolanaKit.SyncState.NotSynced(error ?: SolanaKit.SyncError.NotStarted())
    }

    @Throws(Exception::class)
    private suspend fun fetchTokenAccounts(walletAddress: String) {
        val parsedAccounts = rpcClient.getParsedTokenAccountsByOwner(PublicKey.valueOf(walletAddress))
            .getOrThrow()
        val tokenAccounts = parsedAccounts.map { it.toTokenAccount() }
        val mintAccounts = parsedAccounts.map { it.toMintAccount() }

        persistAndRefreshMirror(emitUpdate = false) { storage.saveTokenAccounts(tokenAccounts, mintAccounts) }
    }

    suspend fun sync(tokenAccounts: List<TokenAccount>? = null) {
        syncState = SolanaKit.SyncState.Syncing()

        var initialSync = mainStorage.isInitialSync()
        if (initialSync) {
            try {
                fetchTokenAccounts(walletAddress)
            } catch (_: Throwable) {
                initialSync = false
            }
        }

        val tokenAccounts = tokenAccounts ?: storage.getTokenAccounts()
        if (tokenAccounts.isEmpty()) {
            syncState = SolanaKit.SyncState.Synced()
            if (initialSync) {
                mainStorage.saveInitialSync()
            }
            return
        }

        val publicKeys = tokenAccounts.map { PublicKey.valueOf(it.address) }
        try {
            val result = rpcClient.getMultipleAccountsFixed(
                serializer = AccountInfoData.serializer(),
                accounts = publicKeys
            ).await()
            handleBalance(tokenAccounts, result, initialSync)
        } catch (error: Throwable) {
            syncState = SolanaKit.SyncState.NotSynced(error)
        }

        if (initialSync) {
            mainStorage.saveInitialSync()
        }
    }

    suspend fun addAccount(
        receivedTokenAccounts: List<TokenAccount>,
        existingMintAddresses: List<String>
    ) {
        persistAndRefreshMirror(emitUpdate = false) { storage.saveTokenAccounts(receivedTokenAccounts) }

        val tokenAccountUpdated: List<TokenAccount> =
            storage.getTokenAccounts(existingMintAddresses) + receivedTokenAccounts
        sync(tokenAccountUpdated.toSet().toList())
        handleNewTokenAccounts(receivedTokenAccounts)
    }

    suspend fun getFullTokenAccountByMintAddress(mintAddress: String): FullTokenAccount? =
        storage.getFullTokenAccount(mintAddress)

    fun tokenAccounts(): List<FullTokenAccount> = fullTokenAccounts

    private suspend fun handleBalance(
        tokenAccounts: List<TokenAccount>,
        tokenAccountsBufferInfo: List<AccountInfoFixed<AccountInfoData>?>,
        initialSync: Boolean
    ) {
        val updatedTokenAccounts = mutableListOf<TokenAccount>()

        for ((index, tokenAccount) in tokenAccounts.withIndex()) {
            tokenAccountsBufferInfo[index]?.let { account ->
                val balance = rpcClient.getTokenAccountBalanceWithRepeat(PublicKey.valueOf(tokenAccount.address))
                    .getOrNull()?.amount ?: "0"
                updatedTokenAccounts.add(
                    TokenAccount(
                        address = tokenAccount.address,
                        mintAddress = tokenAccount.mintAddress,
                        balance = balance.toBigDecimal(),
                        decimals = tokenAccount.decimals
                    )
                )
            }
        }

        persistAndRefreshMirror(emitUpdate = true) { storage.saveTokenAccounts(updatedTokenAccounts) }
        syncState = SolanaKit.SyncState.Synced()
        if (initialSync) {
            handleNewTokenAccounts(updatedTokenAccounts)
        }
    }

    private suspend fun handleNewTokenAccounts(tokenAccounts: List<TokenAccount>) {
        val newFullTokenAccounts = mutableListOf<FullTokenAccount>()
        tokenAccounts.forEach { tokenAccount ->
            storage.getFullTokenAccount(tokenAccount.mintAddress)?.let {
                newFullTokenAccounts.add(it)
            }
        }

        _newTokenAccountsFlow.tryEmit(newFullTokenAccounts)
    }

    suspend fun addTokenAccount(walletAddress: String, mintAddress: String, decimals: Int) {
        val userTokenMintAddress = associatedTokenAddress(walletAddress, mintAddress)
        val tokenAccount = TokenAccount(
            address = userTokenMintAddress,
            mintAddress = mintAddress,
            balance = BigDecimal.ZERO,
            decimals = decimals
        )

        persistAndRefreshMirror(emitUpdate = false) {
            storage.addTokenAccountIfMissing(tokenAccount, MintAccount(mintAddress, decimals))
        }
    }

    private fun associatedTokenAddress(
        walletAddress: String,
        tokenMintAddress: String
    ): String {
        return PublicKey.associatedTokenAddress(
            walletAddress = PublicKey(walletAddress),
            tokenMintAddress = PublicKey(tokenMintAddress)
        ).address.pubkey.let { Base58.encode(it) }
    }

}
