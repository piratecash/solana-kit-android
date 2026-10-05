package io.horizontalsystems.solanakit.transactions

import co.touchlab.kermit.Logger
import io.horizontalsystems.solanakit.network.BatchOutcome
import io.horizontalsystems.solanakit.network.RpcExecutor
import io.horizontalsystems.solanakit.network.RpcOutcome
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Gets transaction details for an ordered list of signatures and stops at the first one it cannot
 * resolve in this pass. Fetched results are kept until [release]d, so a later pass asks only for
 * what is still missing. Not thread-safe: used only inside the syncer's single pass.
 */
class TransactionFetcher(private val rpc: RpcExecutor) {

    private sealed interface Answer {
        class Ok(val result: TransactionResult) : Answer
        data object Transient : Answer
        data object Unusable : Answer
    }

    private class UnusableCount(val count: Int, val pass: Int)

    private val logger = Logger.withTag("SolanaKit")
    private val carryOver = HashMap<String, TransactionResult>()
    private val unusable = HashMap<String, UnusableCount>()
    private var pass = 0
    private var publicCallsLeft = 0
    private var keyedBackfillLeft = 0

    fun startPass() {
        pass++
        publicCallsLeft = PUBLIC_BUDGET
        keyedBackfillLeft = KEYED_BACKFILL_BUDGET
    }

    fun hasBudget(): Boolean = if (rpc.isKeyedAvailable()) keyedBackfillLeft > 0 else publicCallsLeft > 0

    /**
     * The resolved leading part of [signatures]; a null value is a signature skipped as permanently
     * unusable. [backfill] work (backward walk, repair) is bounded on the keyed endpoint too.
     */
    suspend fun fetch(signatures: List<String>, backfill: Boolean): Map<String, TransactionResult?> {
        fetchMissing(signatures.filterNot(::isResolved), backfill)
        // A request cut short by cancellation must not read as a shorter result.
        currentCoroutineContext().ensureActive()
        return signatures.takeWhile(::isResolved).associateWith { carryOver[it] }
    }

    fun release(signatures: Collection<String>) {
        carryOver.keys.removeAll(signatures.toSet())
    }

    private fun isResolved(signature: String) = signature in carryOver || isSkipped(signature)

    private fun isSkipped(signature: String) = (unusable[signature]?.count ?: 0) >= MAX_UNUSABLE

    private suspend fun fetchMissing(missing: List<String>, backfill: Boolean) {
        val keyedAnswers = HashMap<String, Answer>()
        for ((index, signature) in missing.withIndex()) {
            while (signature !in keyedAnswers && rpc.isKeyedAvailable()) {
                val size = keyedBatchSize(backfill)
                if (size == 0) return
                val batch = missing.asSequence().drop(index).filterNot { it in keyedAnswers }.take(size).toList()
                val answers = fetchKeyed(batch, backfill) ?: break
                // Bans can expire during a slow request, so only progress bounds this loop.
                if (answers.isEmpty()) break
                keyedAnswers += answers
            }
            if (!resolve(signature, keyedAnswers[signature])) return
        }
    }

    private fun keyedBatchSize(backfill: Boolean) =
        if (backfill) minOf(BATCH_SIZE, keyedBackfillLeft) else BATCH_SIZE

    // A keyed provider may be unable to serve a request the public node can: only the public answer counts.
    private suspend fun resolve(signature: String, keyedAnswer: Answer?): Boolean = when (keyedAnswer) {
        is Answer.Ok -> true
        Answer.Transient -> false
        Answer.Unusable, null -> fetchPublic(signature)
    }

    /** Null when no key is left: the walk goes on through the public node. Quota-refused items get no answer. */
    private suspend fun fetchKeyed(signatures: List<String>, backfill: Boolean): Map<String, Answer>? {
        val outcome = rpc.executeBatch(signatures.map(::GetTransactionRequest), GetTransactionSerializer())
        if (outcome !is BatchOutcome.Results) return null
        val answers = signatures.zip(outcome.items)
            .filter { (_, item) -> item != RpcOutcome.RateLimited }
            .associate { (signature, item) -> signature to keep(signature, item.classifyKeyed()) }
        if (backfill) keyedBackfillLeft -= answers.size
        return answers
    }

    private suspend fun fetchPublic(signature: String): Boolean {
        if (publicCallsLeft == 0) return false
        publicCallsLeft--
        val outcome = rpc.execute(GetTransactionRequest(signature), GetTransactionSerializer(), publicOnly = true)
        return when (keep(signature, outcome.classify())) {
            is Answer.Ok -> true
            Answer.Transient -> false
            Answer.Unusable -> countUnusable(signature)
        }
    }

    private fun keep(signature: String, answer: Answer): Answer {
        if (answer is Answer.Ok) carryOver[signature] = answer.result
        return answer
    }

    /** True once the signature is skipped: unusable in [MAX_UNUSABLE] different passes. */
    private fun countUnusable(signature: String): Boolean {
        val previous = unusable[signature]
        if (previous?.pass != pass) unusable[signature] = UnusableCount((previous?.count ?: 0) + 1, pass)
        val skipped = isSkipped(signature)
        if (skipped) logger.w { "Transaction skipped after $MAX_UNUSABLE unusable answers" }
        return skipped
    }

    // Only an answer about the transaction itself can lead to a skip; a node error may pass.
    private fun RpcOutcome<TransactionResult>.classify(): Answer = when (this) {
        is RpcOutcome.Success -> result?.let { Answer.Ok(it) } ?: Answer.Unusable
        is RpcOutcome.DecodeFailure -> Answer.Unusable
        is RpcOutcome.NodeError, is RpcOutcome.TransportFailure, is RpcOutcome.HttpFailure, RpcOutcome.RateLimited ->
            Answer.Transient
    }

    // A keyed provider's node error is re-asked on the public node.
    private fun RpcOutcome<TransactionResult>.classifyKeyed(): Answer =
        if (this is RpcOutcome.NodeError) Answer.Unusable else classify()

    private companion object {
        const val PUBLIC_BUDGET = 20
        const val KEYED_BACKFILL_BUDGET = 1000
        const val BATCH_SIZE = 100
        const val MAX_UNUSABLE = 3
    }
}
