package io.horizontalsystems.solanakit.transactions

import com.solana.api.Instruction
import org.sol4k.Base58
import java.math.BigDecimal

/**
 * Derives the SOL transfer from the wallet owner's point of view.
 *
 * A single Solana transaction can carry many `system::transfer` instructions (e.g. a
 * disperse/airdrop or a dust-spam attack that mixes one real transfer with many 1-lamport
 * transfers). Collapsing such a transaction into its first instruction loses the owner's
 * own share, so the resulting record cannot be classified as incoming or outgoing.
 *
 * This resolver locates the owner in [accountKeys], decides the direction from their lamport
 * delta, and reports the exact amount carried by the owner's own transfer instructions.
 */
internal object SolanaUserTransferResolver {

    private const val TRANSFER_DATA_SIZE = 12
    private const val LAMPORTS_OFFSET = 4
    private const val LAMPORTS_SIZE = 8

    data class Transfer(val fromIndex: Int, val toIndex: Int, val lamports: BigDecimal)

    data class UserTransfer(val from: String?, val to: String?, val amount: BigDecimal)

    /**
     * Extracts every `system::transfer` instruction together with its exact lamport amount,
     * decoded from the little-endian u64 stored in the instruction data.
     */
    fun parseTransfers(instructions: List<Instruction>, accountKeys: List<String>): List<Transfer> =
        instructions
            .filter {
                SolanaInstructionParser.parseInstruction(it, accountKeys) == SystemProgramInstruction.TRANSFER
            }
            .mapNotNull { instruction ->
                val fromIndex = instruction.accounts?.getOrNull(0)?.toInt() ?: return@mapNotNull null
                val toIndex = instruction.accounts?.getOrNull(1)?.toInt() ?: return@mapNotNull null
                val lamports = instruction.data?.let(::decodeTransferLamports) ?: return@mapNotNull null
                Transfer(fromIndex, toIndex, lamports)
            }

    fun resolve(
        userAddress: String,
        accountKeys: List<String>,
        preBalances: List<Long>,
        postBalances: List<Long>,
        transfers: List<Transfer>,
    ): UserTransfer {
        val userIndex = accountKeys.indexOf(userAddress)
        val userDelta = balanceDelta(userIndex, preBalances, postBalances)

        // The owner's own balance change decides the direction: a non-negative delta means SOL
        // arrived (keep transfers where the user is the recipient), a negative delta means SOL
        // left (keep transfers where the user is the sender). This prevents a mixed send+dust
        // transaction from being misclassified as an incoming dust.
        val incoming = (userDelta?.signum() ?: 0) >= 0
        val ownerTransfers = transfers.filter {
            if (incoming) it.toIndex == userIndex else it.fromIndex == userIndex
        }
        val chosen = ownerTransfers.firstOrNull() ?: transfers.firstOrNull()

        return UserTransfer(
            from = chosen?.fromIndex?.let { accountKeys.getOrNull(it) },
            to = chosen?.toIndex?.let { accountKeys.getOrNull(it) },
            amount = resolveAmount(ownerTransfers, userDelta, preBalances, postBalances),
        )
    }

    // The amount is the exact value moved by the owner's transfers (fee excluded), so a confirmed
    // transfer matches the amount stored for its pending counterpart. When the owner cannot be
    // tied to a transfer, fall back to their balance delta and finally to the fee-payer delta.
    private fun resolveAmount(
        ownerTransfers: List<Transfer>,
        userDelta: BigDecimal?,
        preBalances: List<Long>,
        postBalances: List<Long>,
    ): BigDecimal = when {
        ownerTransfers.isNotEmpty() -> ownerTransfers.fold(BigDecimal.ZERO) { acc, t -> acc + t.lamports }
        userDelta != null -> userDelta.abs()
        else -> feePayerDelta(preBalances, postBalances)
    }

    private fun decodeTransferLamports(data: String): BigDecimal? {
        val bytes = Base58.decode(data)
        if (bytes.size < TRANSFER_DATA_SIZE) return null
        var lamports = 0L
        for (i in 0 until LAMPORTS_SIZE) {
            lamports = lamports or ((bytes[LAMPORTS_OFFSET + i].toLong() and 0xFF) shl (8 * i))
        }
        return BigDecimal(lamports)
    }

    private fun balanceDelta(index: Int, pre: List<Long>, post: List<Long>): BigDecimal? {
        if (index < 0) return null
        val preValue = pre.getOrNull(index) ?: return null
        val postValue = post.getOrNull(index) ?: return null
        return BigDecimal(postValue - preValue)
    }

    private fun feePayerDelta(pre: List<Long>, post: List<Long>): BigDecimal =
        BigDecimal((pre.getOrNull(0) ?: 0L) - (post.getOrNull(0) ?: 0L))
}
