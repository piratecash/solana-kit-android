package io.horizontalsystems.solanakit.transactions

import com.solana.api.Meta
import com.solana.api.TokenBalance
import io.horizontalsystems.solanakit.models.FullTokenTransfer
import io.horizontalsystems.solanakit.models.FullTransaction
import io.horizontalsystems.solanakit.models.MintAccount
import io.horizontalsystems.solanakit.models.TokenTransfer
import io.horizontalsystems.solanakit.models.Transaction
import io.horizontalsystems.solanakit.transactions.SolanaUserTransferResolver.Transfer
import io.horizontalsystems.solanakit.transactions.SolanaUserTransferResolver.UserTransfer
import java.math.BigDecimal

/**
 * Maps raw RPC transaction results into [FullTransaction]s from the wallet owner's point of view.
 *
 * SOL transfers are resolved via [SolanaUserTransferResolver] so that multi-transfer (e.g.
 * dust-spam) transactions are attributed to the owner instead of the first unrelated transfer.
 * Token transfers keep the SOL amount NULL and record one [TokenTransfer] per mint whose balance
 * owned by the user changed; its direction is the sign of that change.
 */
internal object SolanaTransactionMapper {

    private const val LAMPORTS_DECIMALS = 9

    fun map(
        userAddress: String,
        rpcTransactions: List<TransactionResult>,
        mintAccounts: Map<String, MintAccount>,
    ): List<FullTransaction> {
        val transactions = mutableMapOf<String, FullTransaction>()
        for (signatureInfo in rpcTransactions) {
            val fullTransaction = toFullTransaction(userAddress, signatureInfo, mintAccounts)
            transactions[fullTransaction.transaction.hash] = fullTransaction
        }
        return transactions.values.toList()
    }

    fun userMints(userAddress: String, results: List<TransactionResult>): Set<String> =
        results.flatMap { tokenEntries(it.meta) }
            .filter { it.isOwnedBy(userAddress) }
            .mapTo(LinkedHashSet()) { it.mint }

    /**
     * Hashes of transactions proven not to touch the user's tokens: metadata present, succeeded,
     * every token entry has a known owner, and none of the user's balances changed.
     */
    fun hashesWithoutUserTokenChanges(userAddress: String, results: List<TransactionResult>): Set<String> =
        results.filter { provesNoUserTokenChange(userAddress, it.meta) }
            .mapTo(HashSet()) { it.hash() }

    private fun provesNoUserTokenChange(userAddress: String, meta: Meta?): Boolean {
        if (meta == null || meta.err != null) return false
        val entries = tokenEntries(meta)
        return entries.all { it.owner != null } && ownerTokenChanges(userAddress, entries).isEmpty()
    }

    private fun toFullTransaction(
        userAddress: String,
        signatureInfo: TransactionResult,
        mintAccounts: Map<String, MintAccount>,
    ): FullTransaction {
        val message = signatureInfo.transaction?.message
        val accountKeys = signatureInfo.fullAccountKeys()
        val hash = signatureInfo.hash()

        val tokenEntries = tokenEntries(signatureInfo.meta)
        val tokenChanges = ownerTokenChanges(userAddress, tokenEntries)
        val isTokenTransfer = tokenChanges.isNotEmpty()

        val transfers = SolanaUserTransferResolver.parseTransfers(
            instructions = message?.instructions.orEmpty(),
            accountKeys = accountKeys,
        )
        // Absent from the account keys (a token-account-only tx), the user's SOL did not move.
        val userTransfer = if (isTokenTransfer || userAddress !in accountKeys) null else SolanaUserTransferResolver.resolve(
            userAddress = userAddress,
            accountKeys = accountKeys,
            preBalances = signatureInfo.meta?.preBalances.orEmpty(),
            postBalances = signatureInfo.meta?.postBalances.orEmpty(),
            transfers = transfers,
        )

        val endpoints = resolveEndpoints(userTransfer, transfers, accountKeys)
        val (from, to) = if (isTokenTransfer) {
            tokenEndpoints(userAddress, tokenEntries, tokenChanges, endpoints)
        } else {
            endpoints
        }
        val transaction = Transaction(
            hash = hash,
            timestamp = signatureInfo.blockTime,
            fee = toBigNumWithMovePointLeft(signatureInfo.meta?.fee),
            from = from,
            to = to,
            error = signatureInfo.meta?.err?.toString(),
            amount = userTransfer?.amount,
            pending = false,
        )
        return FullTransaction(
            transaction = transaction,
            tokenTransfers = buildTokenTransfers(hash, tokenChanges, mintAccounts),
        )
    }

    // Pre/post entries of one token account are paired by accountIndex + mint; a missing side
    // (account created or closed in this transaction) counts as a zero balance.
    private fun tokenEntries(meta: Meta?): List<TokenEntry> {
        val preByKey = meta?.preTokenBalances.orEmpty().associateBy { it.entryKey() }
        val postByKey = meta?.postTokenBalances.orEmpty().associateBy { it.entryKey() }
        return (postByKey.keys + preByKey.keys).map { key ->
            val post = postByKey[key]
            val pre = preByKey[key]
            val mint = checkNotNull(post ?: pre).mint
            TokenEntry(mint = mint, owner = post?.owner ?: pre?.owner, change = post.amount() - pre.amount())
        }
    }

    private fun ownerTokenChanges(userAddress: String, entries: List<TokenEntry>): Map<String, BigDecimal> =
        entries.filter { it.isOwnedBy(userAddress) }
            .groupingBy { it.mint }
            .fold(BigDecimal.ZERO) { sum, entry -> sum + entry.change }
            .filterValues { it.signum() != 0 }

    // Consumers read `from` for every incoming leg and `to` for every outgoing one.
    private fun tokenEndpoints(
        userAddress: String,
        entries: List<TokenEntry>,
        tokenChanges: Map<String, BigDecimal>,
        fallback: Pair<String, String>,
    ): Pair<String, String> {
        val from = firstLegCounterparty(userAddress, entries, tokenChanges, incoming = true, fallback.first)
        val to = firstLegCounterparty(userAddress, entries, tokenChanges, incoming = false, fallback.second)
        return from to to
    }

    private fun firstLegCounterparty(
        userAddress: String,
        entries: List<TokenEntry>,
        tokenChanges: Map<String, BigDecimal>,
        incoming: Boolean,
        fallback: String,
    ): String {
        val mint = tokenChanges.entries.firstOrNull { (it.value.signum() > 0) == incoming }?.key
            ?: return userAddress
        return counterpartyOwner(userAddress, entries, mint, incoming) ?: fallback
    }

    // The sender of an incoming leg is the foreign owner whose balance dropped the most; the
    // recipient of an outgoing leg is the one whose balance grew the most.
    private fun counterpartyOwner(
        userAddress: String,
        entries: List<TokenEntry>,
        mint: String,
        incoming: Boolean,
    ): String? {
        val counterpartySign = if (incoming) -1 else 1
        return entries
            .filter { it.mint == mint && it.owner != null && !it.isOwnedBy(userAddress) }
            .filter { it.change.signum() == counterpartySign }
            .maxByOrNull { it.change.abs() }
            ?.owner
    }

    // Prefer the owner's own transfer; otherwise fall back to the first transfer and finally to the
    // raw account keys, preserving the legacy attribution when the owner cannot be located.
    private fun resolveEndpoints(
        userTransfer: UserTransfer?,
        transfers: List<Transfer>,
        accountKeys: List<String>,
    ): Pair<String, String> {
        val firstTransfer = transfers.firstOrNull()
        val from = userTransfer?.from
            ?: firstTransfer?.fromIndex?.let { accountKeys.getOrNull(it) }
            ?: accountKeys.firstOrNull().orEmpty()
        val to = userTransfer?.to
            ?: firstTransfer?.toIndex?.let { accountKeys.getOrNull(it) }
            ?: accountKeys.getOrNull(1).orEmpty()
        return from to to
    }

    private fun buildTokenTransfers(
        hash: String,
        tokenChanges: Map<String, BigDecimal>,
        mintAccounts: Map<String, MintAccount>,
    ): List<FullTokenTransfer> =
        tokenChanges.mapNotNull { (mint, change) ->
            val mintAccount = mintAccounts[mint] ?: return@mapNotNull null
            FullTokenTransfer(
                tokenTransfer = TokenTransfer(
                    transactionHash = hash,
                    mintAddress = mint,
                    incoming = change > BigDecimal.ZERO,
                    amount = change.abs()
                ),
                mintAccount = mintAccount
            )
        }

    // Balances and instruction account indices of a v0 transaction run over the static keys,
    // then the writable and readonly addresses loaded from lookup tables.
    private fun TransactionResult.fullAccountKeys(): List<String> {
        val loaded = meta?.loadedAddresses
        return transaction?.message?.accountKeys.orEmpty() + loaded?.writable.orEmpty() + loaded?.readonly.orEmpty()
    }

    private fun TransactionResult.hash() = transaction?.signatures?.firstOrNull().orEmpty()

    private fun TokenBalance.entryKey() = "${accountIndex}_$mint"

    private fun TokenBalance?.amount(): BigDecimal =
        this?.uiTokenAmount?.amount?.toBigDecimalOrNull() ?: BigDecimal.ZERO

    private class TokenEntry(val mint: String, val owner: String?, val change: BigDecimal) {
        fun isOwnedBy(address: String) = owner == address
    }

    private fun toBigNumWithMovePointLeft(value: Long?, shiftAmount: Int = LAMPORTS_DECIMALS) =
        value?.toBigDecimal()?.movePointLeft(shiftAmount)?.stripTrailingZeros()
}
