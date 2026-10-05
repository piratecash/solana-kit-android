package io.horizontalsystems.solanakit.transactions

import SplTokenAccountWithPublicKey
import co.touchlab.kermit.Logger
import com.solana.api.Api
import com.solana.core.PublicKey
import org.sol4k.Base58
import com.solana.programs.TokenProgram
import getTokenAccountsByOwner
import io.horizontalsystems.solanakit.SolanaKit
import io.horizontalsystems.solanakit.database.transaction.TransactionStorage
import io.horizontalsystems.solanakit.database.transaction.dao.TransactionsDao.TransactionKey
import io.horizontalsystems.solanakit.models.FullTransaction
import io.horizontalsystems.solanakit.models.LastSyncedTransaction
import io.horizontalsystems.solanakit.models.MintAccount
import io.horizontalsystems.solanakit.noderpc.endpoints.RpcSignatureInformation
import io.horizontalsystems.solanakit.noderpc.endpoints.getSignaturesForAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

interface ITransactionListener {
    fun onUpdateTransactionSyncState(syncState: SolanaKit.SyncState)
}

/**
 * Lossless history sync: a forward cursor per stream (the wallet and each token account) covers
 * everything newer than the initial point, one global backward cursor walks the older history.
 * A cursor only moves past slot groups that are fully stored.
 */
class TransactionSyncer(
    private val publicKey: PublicKey,
    private val rpcClient: Api,
    private val storage: TransactionStorage,
    private val transactionManager: TransactionManager,
    private val pendingTransactionSyncer: PendingTransactionSyncer,
    private val fetcher: TransactionFetcher,
) {
    private class StreamListing(val entries: List<RpcSignatureInformation>, val truncated: Boolean)

    private class SlotGroup(val slot: Long, val entries: List<RpcSignatureInformation>) {
        fun missing(stored: Set<String>) = entries.filterNot { it.signature in stored }
    }

    private val _syncState = MutableStateFlow<SolanaKit.SyncState>(
        SolanaKit.SyncState.NotSynced(SolanaKit.SyncError.NotStarted())
    )
    val syncState: StateFlow<SolanaKit.SyncState> = _syncState.asStateFlow()

    var listener: ITransactionListener? = null

    private val logger = Logger.withTag("SolanaKit")
    private val userAddress = publicKey.toBase58()
    private val passLock = Mutex()
    private val storedInPass = mutableListOf<FullTransaction>()
    private var cachedTokenAccounts: List<SplTokenAccountWithPublicKey>? = null
    private var tokenAccountsCacheTime: Long = 0

    private fun updateSyncState(newState: SolanaKit.SyncState) {
        _syncState.value = newState
        listener?.onUpdateTransactionSyncState(newState)
    }

    /** Returns at once while another pass runs, so two passes never move the same cursor. */
    suspend fun sync() {
        if (!passLock.tryLock()) return
        try {
            runPass()
        } finally {
            publishStoredInPass()
            passLock.unlock()
        }
    }

    // One update per pass: the transactions flow keeps only its latest value, so per-slot updates would be lost.
    private fun publishStoredInPass() {
        transactionManager.notifyTransactionsUpdate(storedInPass.toList())
        storedInPass.clear()
    }

    private suspend fun runPass() {
        updateSyncState(SolanaKit.SyncState.Syncing())
        try {
            pendingTransactionSyncer.sync()
            syncHistory()
            updateSyncState(SolanaKit.SyncState.Synced())
        } catch (cancellation: CancellationException) {
            updateSyncState(SolanaKit.SyncState.NotSynced(cancellation))
            throw cancellation
        } catch (exception: Throwable) {
            logger.w(exception) { "Transaction sync failed" }
            updateSyncState(SolanaKit.SyncState.NotSynced(exception))
        }
    }

    // A listing failure throws and leaves every cursor in place; a fetch that stops ends the pass quietly.
    private suspend fun syncHistory() {
        fetcher.startPass()
        val streams = listOf(userAddress) + getTokenAccountsByOwner().map { it.publicKey }
        if (syncForward(streams) && syncBackward(streams) && fetcher.hasBudget()) {
            repairStoredTokenTransfers()
        }
    }

    private suspend fun syncForward(streams: List<String>): Boolean {
        // Until the backward walk stores its first group, the newest history is its job (see seedForwardCursors).
        if (backwardCursor() == TOP) return true
        val listings = streams.associateWith { stream ->
            listStream(stream, before = null, until = cursor(forwardKey(stream))) { true }.entries
        }
        val newestBySlot = listings.mapValues { (_, listed) ->
            listed.distinctBy { it.slot }.associate { it.slot to it.signature }
        }
        return storeGroups(slotGroups(listings.values.flatten(), newestFirst = false), backfill = false) { group ->
            newestBySlot.forEach { (stream, bySlot) ->
                bySlot[group.slot]?.let { saveCursor(forwardKey(stream), it) }
            }
        }
    }

    // Existing installs start here too: gaps left by the previous syncer are filled by the backward walk.
    private suspend fun syncBackward(discovered: List<String>): Boolean {
        val before = backwardCursor()
        if (before == DONE) return true
        val listings = backwardStreams(discovered).associateWith { stream ->
            // A full page within one slot leaves no slot eligible, so that stream is paged further.
            listStream(stream, before.takeUnless { it == TOP }, until = null) { it.first().slot == it.last().slot }
        }
        // The oldest slot of a truncated page may still hold signatures of that stream not listed yet.
        val boundary = listings.values.filter { it.truncated }.maxOfOrNull { it.entries.last().slot }
        val eligible = listings.values.flatMap { it.entries }.filter { boundary == null || it.slot > boundary }
        var seeded = before != TOP
        val complete = storeGroups(slotGroups(eligible, newestFirst = true), backfill = true) { group ->
            if (!seeded) seedForwardCursors(listings)
            seeded = true
            saveCursor(BACKWARD_KEY, group.entries.last().signature)
        }
        if (complete && boundary == null) saveCursor(BACKWARD_KEY, DONE)
        return complete
    }

    /**
     * Forward cursors come from the listing the backward walk starts storing, so the two walks meet
     * even when another node, lagging behind, served an earlier listing. The backward row leaving
     * [TOP] commits them, so it is written after them.
     */
    private suspend fun seedForwardCursors(listings: Map<String, StreamListing>) {
        listings.forEach { (stream, listing) ->
            listing.entries.firstOrNull()?.let { saveCursor(forwardKey(stream), it.signature) }
        }
    }

    private suspend fun backwardCursor() = cursor(BACKWARD_KEY) ?: TOP

    // A token account closed during the walk is no longer discovered, but its older history still belongs to it.
    private suspend fun backwardStreams(discovered: List<String>): List<String> =
        (discovered + storage.syncSourceNames(FORWARD_PREFIX).map { it.removePrefix(FORWARD_PREFIX) }).distinct()

    /** Stores [groups] in order, each atomically; false once a group cannot be completed in this pass. */
    private suspend fun storeGroups(
        groups: List<SlotGroup>,
        backfill: Boolean,
        onStored: suspend (SlotGroup) -> Unit,
    ): Boolean {
        val stored = storage.storedHashes(groups.flatMap { group -> group.entries.map { it.signature } })
        for (window in groups.chunked(WINDOW_GROUPS)) {
            val fetched = fetcher.fetch(window.flatMap { it.missing(stored) }.map { it.signature }, backfill)
            val complete = window.takeWhile { group -> group.missing(stored).all { it.signature in fetched } }
            val mintAccounts = mintAccountsOrNull(complete.flatMap { it.results(stored, fetched) }) ?: return false
            complete.forEach { group ->
                storedInPass += transactionManager.store(toFullTransactions(group.results(stored, fetched), mintAccounts))
                fetcher.release(group.entries.map { it.signature })
                onStored(group)
            }
            if (complete.size < window.size) return false
        }
        return true
    }

    // A null fetched value is a skipped signature: processed, with no row.
    private fun SlotGroup.results(stored: Set<String>, fetched: Map<String, TransactionResult?>) =
        missing(stored).mapNotNull { entry -> fetched[entry.signature]?.withFallbackBlockTime(entry.blockTime) }

    private fun slotGroups(listed: List<RpcSignatureInformation>, newestFirst: Boolean): List<SlotGroup> {
        val bySlot = listed.distinctBy { it.signature }.groupBy { it.slot }
        val slots = if (newestFirst) bySlot.keys.sortedDescending() else bySlot.keys.sorted()
        return slots.map { SlotGroup(it, bySlot.getValue(it)) }
    }

    private suspend fun listStream(
        stream: String,
        before: String?,
        until: String?,
        pageAgain: (List<RpcSignatureInformation>) -> Boolean,
    ): StreamListing {
        val listed = mutableListOf<RpcSignatureInformation>()
        while (true) {
            val page = listPage(stream, listed.lastOrNull()?.signature ?: before, until)
            listed += page
            val truncated = page.size == rpcSignaturesCount
            if (!truncated || !pageAgain(listed)) return StreamListing(listed, truncated)
        }
    }

    private suspend fun listPage(stream: String, before: String?, until: String?) =
        rpcClient.getSignaturesForAddress(PublicKey(stream), rpcSignaturesCount, before, until).getOrAbort()

    private suspend fun cursor(name: String): String? = storage.getSyncedBlockTime(name)?.hash

    private suspend fun saveCursor(name: String, value: String) =
        storage.setSyncedBlockTime(LastSyncedTransaction(name, value))

    private fun forwardKey(stream: String) = FORWARD_PREFIX + stream

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
        if (cursor == DONE) return
        val position = parseRepairCursor(cursor)
        val batch = storage.tokenTransferTransactionHashes(position?.timestamp, position?.hash, REPAIR_BATCH)
        if (batch.isEmpty()) {
            storage.saveTokenTransferRepairCursor(DONE)
            return
        }

        val fetched = fetcher.fetch(batch.map { it.hash }, backfill = true)
        val processed = batch.take(fetched.size)
        val lastProcessed = processed.lastOrNull() ?: return
        // Returned without meta, a transaction cannot be re-mapped reliably: its stored row stays untouched.
        val results = processed.mapNotNull { key ->
            fetched[key.hash]?.takeIf { it.meta != null }?.withFallbackBlockTime(key.timestamp)
        }
        val mintAccounts = mintAccountsOrNull(results) ?: return
        storedInPass += transactionManager.store(
            syncedTransactions = toFullTransactions(results, mintAccounts),
            replaceTokenTransfersOf = SolanaTransactionMapper.hashesWithoutUserTokenChanges(userAddress, results),
        )
        fetcher.release(processed.map { it.hash })
        storage.saveTokenTransferRepairCursor("${lastProcessed.timestamp}:${lastProcessed.hash}")
    }

    private fun TransactionResult.withFallbackBlockTime(fallback: Long?) =
        if (blockTime != null) this else copy(blockTime = fallback)

    private fun parseRepairCursor(cursor: String?): TransactionKey? {
        val timestamp = cursor?.substringBefore(':')?.toLongOrNull() ?: return null
        val hash = cursor.substringAfter(':', missingDelimiterValue = "")
        return if (hash.isEmpty()) null else TransactionKey(timestamp, hash)
    }

    // A discovery failure fails the pass: an empty list would hide every token account's history.
    private suspend fun getTokenAccountsByOwner(): List<SplTokenAccountWithPublicKey> {
        val now = System.currentTimeMillis()
        val cacheValidDuration = 5 * 60 * 1000 // 5 minutes

        cachedTokenAccounts?.let {
            if (now - tokenAccountsCacheTime < cacheValidDuration) {
                logger.d { "Using cached token accounts: ${it.size}" }
                return it
            }
        }

        val accounts = rpcClient.getTokenAccountsByOwner(publicKey).getOrAbort()
        if(accounts.isNotEmpty()) {
            cachedTokenAccounts = accounts
            tokenAccountsCacheTime = now
        }

        return accounts
    }

    // The RPC helper turns a cancellation during the request into a plain failure.
    private suspend fun <T> Result<T>.getOrAbort(): T = getOrElse {
        currentCoroutineContext().ensureActive()
        throw it
    }

    // Without mint accounts the token transfers would be dropped, so the store waits for a later pass.
    private suspend fun mintAccountsOrNull(results: List<TransactionResult>): Map<String, MintAccount>? =
        getMintAccounts(results).getOrElse {
            currentCoroutineContext().ensureActive()
            logger.w(it) { "Mint accounts unavailable, store postponed" }
            null
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
                val owner = account?.owner
                val mint = account?.data
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
        private const val REPAIR_BATCH = 20
        private const val WINDOW_GROUPS = 100
        private const val DONE = "done"
        private const val TOP = "top"

        // Cursor rows in the LastSyncedTransaction key-value table, so no schema change is needed.
        private const val FORWARD_PREFIX = "history-forward-v1:"
        private const val BACKWARD_KEY = "history-backward-v1"
    }

}
