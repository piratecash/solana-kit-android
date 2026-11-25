package io.horizontalsystems.solanakit.core

import com.solana.api.Api
import com.solana.core.PublicKey
import getTokenAccountBalanceWithRepeat
import getParsedTokenAccountsByOwner
import io.horizontalsystems.solanakit.SolanaKit
import io.horizontalsystems.solanakit.database.main.MainStorage
import io.horizontalsystems.solanakit.database.transaction.TransactionStorage
import io.horizontalsystems.solanakit.models.FullTokenAccount
import io.horizontalsystems.solanakit.models.MintAccount
import io.horizontalsystems.solanakit.models.TokenAccount
import io.horizontalsystems.solanakit.transactions.WellKnownPrograms
import io.horizontalsystems.solanakit.transactions.associatedTokenAddress
import io.horizontalsystems.solanakit.transactions.getAccountOwners
import io.horizontalsystems.solanakit.transactions.isTokenAccount
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
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
        val owner = PublicKey.valueOf(walletAddress)
        val parsedAccounts = WellKnownPrograms.TOKEN_PROGRAMS.flatMap { programId ->
            rpcClient.getParsedTokenAccountsByOwner(owner, programId).getOrThrow()
        }
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

        try {
            handleBalance(liveTokenAccounts(tokenAccounts), initialSync)
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

    /**
     * Accounts that exist on chain. A Token-2022 mint enabled offline is stored under its legacy-derived ATA,
     * so that row also asks for the Token-2022 ATA in the same request and is moved there once it exists.
     */
    private suspend fun liveTokenAccounts(tokenAccounts: List<TokenAccount>): List<TokenAccount> {
        val candidates = tokenAccounts.filter { it.isEmptyLegacyAta() }.associateWith {
            associatedTokenAddress(walletAddress, it.mintAddress, WellKnownPrograms.TOKEN_2022_PROGRAM)
        }
        val addresses = (tokenAccounts.map { it.address } + candidates.values).distinct()
        val owners = rpcClient.getAccountOwners(addresses).getOrThrow()

        return tokenAccounts.mapNotNull { tokenAccount ->
            if (isTokenAccount(owners[tokenAccount.address])) return@mapNotNull tokenAccount
            val actualAddress = candidates[tokenAccount]
                ?.takeIf { owners[it] == WellKnownPrograms.TOKEN_2022_PROGRAM } ?: return@mapNotNull null
            val actual = TokenAccount(actualAddress, tokenAccount.mintAddress, tokenAccount.balance, tokenAccount.decimals)
            persistAndRefreshMirror(emitUpdate = false) { storage.replaceTokenAccount(tokenAccount.address, actual) }
            actual
        }.distinctBy { it.address }
    }

    private fun TokenAccount.isEmptyLegacyAta() = balance.signum() == 0 &&
        address == associatedTokenAddress(walletAddress, mintAddress, WellKnownPrograms.TOKEN_PROGRAM)

    private suspend fun handleBalance(tokenAccounts: List<TokenAccount>, initialSync: Boolean) {
        val updatedTokenAccounts = tokenAccounts.map { tokenAccount ->
            val balance = rpcClient.getTokenAccountBalanceWithRepeat(PublicKey.valueOf(tokenAccount.address))
                .getOrNull()?.amount ?: "0"
            TokenAccount(
                address = tokenAccount.address,
                mintAddress = tokenAccount.mintAddress,
                balance = balance.toBigDecimal(),
                decimals = tokenAccount.decimals
            )
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
        // Offline: a Token-2022 mint gets the legacy-derived address here and is moved by the next sync.
        val userTokenMintAddress = associatedTokenAddress(walletAddress, mintAddress, WellKnownPrograms.TOKEN_PROGRAM)
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
}
