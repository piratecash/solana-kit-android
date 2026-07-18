package io.horizontalsystems.solanakit.transactions

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
 * Token transfers keep the SOL amount NULL and record a [TokenTransfer] instead.
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

    private fun toFullTransaction(
        userAddress: String,
        signatureInfo: TransactionResult,
        mintAccounts: Map<String, MintAccount>,
    ): FullTransaction {
        val message = signatureInfo.transaction?.message
        val accountKeys = message?.accountKeys.orEmpty()
        val hash = signatureInfo.transaction?.signatures?.firstOrNull().orEmpty()

        val postTokenBalances = signatureInfo.meta?.postTokenBalances?.firstOrNull()
        val preTokenBalances = signatureInfo.meta?.preTokenBalances?.firstOrNull()
        val isTokenTransfer = postTokenBalances != null && preTokenBalances != null

        val transfers = SolanaUserTransferResolver.parseTransfers(
            instructions = message?.instructions.orEmpty(),
            accountKeys = accountKeys,
        )
        val userTransfer = if (isTokenTransfer) null else SolanaUserTransferResolver.resolve(
            userAddress = userAddress,
            accountKeys = accountKeys,
            preBalances = signatureInfo.meta?.preBalances.orEmpty(),
            postBalances = signatureInfo.meta?.postBalances.orEmpty(),
            transfers = transfers,
        )

        val (from, to) = resolveEndpoints(userTransfer, transfers, accountKeys)
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
            tokenTransfers = buildTokenTransfers(hash, preTokenBalances, postTokenBalances, mintAccounts),
        )
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
        preTokenBalances: TokenBalance?,
        postTokenBalances: TokenBalance?,
        mintAccounts: Map<String, MintAccount>,
    ): List<FullTokenTransfer> {
        if (preTokenBalances == null || postTokenBalances == null) return emptyList()
        val mintAccount = mintAccounts[postTokenBalances.mint] ?: return emptyList()
        val amount = (preTokenBalances.uiTokenAmount.amount?.toBigDecimal() ?: BigDecimal.ZERO) -
            (postTokenBalances.uiTokenAmount.amount?.toBigDecimal() ?: BigDecimal.ZERO)
        return listOf(
            FullTokenTransfer(
                tokenTransfer = TokenTransfer(
                    transactionHash = hash,
                    mintAddress = postTokenBalances.mint,
                    incoming = amount > BigDecimal.ZERO,
                    amount = amount.abs()
                ),
                mintAccount = mintAccount
            )
        )
    }

    private fun toBigNumWithMovePointLeft(value: Long?, shiftAmount: Int = LAMPORTS_DECIMALS) =
        value?.toBigDecimal()?.movePointLeft(shiftAmount)?.stripTrailingZeros()
}
