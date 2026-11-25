package io.horizontalsystems.solanakit.transactions

import com.solana.networking.RpcError
import com.solana.networking.RpcRequest
import io.horizontalsystems.solanakit.network.BatchOutcome
import io.horizontalsystems.solanakit.network.RpcExecutor
import io.horizontalsystems.solanakit.network.RpcOutcome
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class TransactionFetcherTest {

    private val rpc = mockk<RpcExecutor>()
    private val fetcher = TransactionFetcher(rpc)
    private val publicRequests = mutableListOf<String>()
    private val batches = mutableListOf<List<String>>()
    private var keyedAvailable = false
    private var healthyKeys = 1
    private var batchUnavailable = false
    private var publicAnswer: (String) -> RpcOutcome<TransactionResult> = ::ok
    private var keyedAnswer: (String) -> RpcOutcome<TransactionResult> = ::ok

    init {
        every { rpc.isKeyedAvailable() } answers { keyedAvailable && healthyKeys > 0 }
        coEvery { rpc.execute<Any?>(any(), any(), true) } answers {
            val signature = signatureOf(firstArg())
            publicRequests += signature
            publicAnswer(signature)
        }
        coEvery { rpc.executeBatch<Any?>(any(), any()) } answers {
            val signatures = firstArg<List<RpcRequest>>().map(::signatureOf)
            batches += signatures
            if (batchUnavailable) return@answers BatchOutcome.KeyedUnavailable
            val items = signatures.map(keyedAnswer)
            // Like the router: a quota refusal of any item bans the key that answered.
            if (RpcOutcome.RateLimited in items) healthyKeys--
            BatchOutcome.Results(items)
        }
        fetcher.startPass()
    }

    @Test
    fun fetch_publicTransientOnSecond_returnsLeadingResultAndStops() = runBlocking {
        publicAnswer = { if (it == "b") RpcOutcome.TransportFailure(IOException("timeout")) else ok(it) }

        val fetched = fetcher.fetch(listOf("a", "b", "c"), backfill = false)

        assertEquals(listOf("a"), fetched.keys.toList())
        assertEquals("a", fetched.getValue("a")?.transaction?.signatures?.single())
        assertEquals(listOf("a", "b"), publicRequests)
    }

    @Test
    fun fetch_transientOutcomesAndAnyNodeError_neverSkipped() = runBlocking {
        val transient = listOf(
            RpcOutcome.TransportFailure(IOException("reset")),
            RpcOutcome.HttpFailure(429, retryAfter = 5, body = ""),
            RpcOutcome.HttpFailure(503, retryAfter = null, body = ""),
            RpcOutcome.RateLimited,
        ) + listOf(429, -32005, -32004, -32007, -32009, -32014, -32016, -32603, -32015, -32602).map(::nodeError) + listOf(
            RpcOutcome.NodeError(RpcError(-32000, "Rate limit reached")),
            RpcOutcome.NodeError(RpcError(-32600, "Monthly quota exceeded")),
        )

        transient.forEach { outcome ->
            val fetched = fetchInPasses(passes = 5) { outcome }

            assertTrue("$outcome must not be skipped", fetched.isEmpty())
        }
    }

    @Test
    fun fetch_unusableOutcomes_skippedOnlyInThirdPass() = runBlocking {
        val unusable = listOf(
            RpcOutcome.Success(null),
            RpcOutcome.DecodeFailure("unexpected field"),
        )

        unusable.forEach { outcome ->
            assertTrue("$outcome skipped too early", fetchInPasses(passes = 2) { outcome }.isEmpty())

            val fetched = fetchInPasses(passes = 3) { outcome }

            assertEquals("$outcome", setOf("a"), fetched.keys)
            assertNull(fetched.getValue("a"))
        }
    }

    @Test
    fun fetch_unusableTwiceInOnePass_countedOnce() = runBlocking {
        publicAnswer = { RpcOutcome.Success(null) }
        fetcher.fetch(listOf("a"), backfill = false)
        fetcher.fetch(listOf("a"), backfill = false)
        fetcher.startPass()

        assertTrue(fetcher.fetch(listOf("a"), backfill = false).isEmpty())
        fetcher.startPass()
        assertEquals(setOf("a"), fetcher.fetch(listOf("a"), backfill = false).keys)
    }

    @Test
    fun fetch_skippedSignature_notRequestedAgain() = runBlocking {
        val signatures = listOf("a", "b")
        publicAnswer = { if (it == "a") RpcOutcome.Success(null) else ok(it) }
        repeat(3) {
            fetcher.startPass()
            fetcher.fetch(signatures, backfill = false)
        }
        publicRequests.clear()

        val fetched = fetcher.fetch(signatures, backfill = false)

        assertEquals(listOf("a", "b"), fetched.keys.toList())
        assertTrue(publicRequests.isEmpty())
    }

    @Test
    fun fetch_publicBudgetSpent_resumesNextPassFromCarryOver() = runBlocking {
        val signatures = signatures(25)

        assertEquals(signatures.take(20), fetcher.fetch(signatures, backfill = false).keys.toList())
        assertFalse(fetcher.hasBudget())

        fetcher.startPass()
        val fetched = fetcher.fetch(signatures, backfill = false)

        assertEquals(signatures, fetched.keys.toList())
        assertEquals(signatures, publicRequests)
    }

    @Test
    fun release_releasedSignatures_fetchedAgain() = runBlocking {
        fetcher.fetch(listOf("a", "b"), backfill = false)

        fetcher.release(listOf("a"))
        fetcher.fetch(listOf("a", "b"), backfill = false)

        assertEquals(listOf("a", "b", "a"), publicRequests)
    }

    @Test
    fun fetch_keyed_batchesOfHundredWithoutPublicCalls() = runBlocking {
        keyedAvailable = true
        val signatures = signatures(150)

        val fetched = fetcher.fetch(signatures, backfill = false)

        assertEquals(signatures, fetched.keys.toList())
        assertEquals(listOf(100, 50), batches.map { it.size })
        assertTrue(publicRequests.isEmpty())
    }

    @Test
    fun fetch_keyedBackfill_boundedToThousandPerPassForwardUnbounded() = runBlocking {
        keyedAvailable = true

        assertEquals(1000, fetcher.fetch(signatures(1200), backfill = true).size)
        assertFalse(fetcher.hasBudget())
        assertEquals(1200, fetcher.fetch(signatures(1200, prefix = "f"), backfill = false).size)

        fetcher.startPass()
        assertEquals(1200, fetcher.fetch(signatures(1200), backfill = true).size)
        assertTrue(publicRequests.isEmpty())
    }

    @Test
    fun fetch_batchWithTransientItem_consumedUpToItAndLaterOkKept() = runBlocking {
        keyedAvailable = true
        keyedAnswer = { if (it == "c") RpcOutcome.TransportFailure(IOException("reset")) else ok(it) }
        val signatures = listOf("a", "b", "c", "d")

        assertEquals(listOf("a", "b"), fetcher.fetch(signatures, backfill = false).keys.toList())

        keyedAnswer = ::ok
        fetcher.startPass()
        assertEquals(signatures, fetcher.fetch(signatures, backfill = false).keys.toList())
        assertEquals(listOf(signatures, listOf("c")), batches)
        assertTrue(publicRequests.isEmpty())
    }

    @Test
    fun fetch_keyedUnusableItem_refetchedOnPublicAndOnlyThatAnswerCounts() = runBlocking {
        keyedAvailable = true
        keyedAnswer = { if (it == "b") RpcOutcome.DecodeFailure("v1 not served") else ok(it) }

        assertEquals(listOf("a", "b", "c"), fetcher.fetch(listOf("a", "b", "c"), backfill = false).keys.toList())
        assertEquals(listOf("b"), publicRequests)

        keyedAnswer = { RpcOutcome.DecodeFailure("v1 not served") }
        publicAnswer = { RpcOutcome.HttpFailure(503, retryAfter = null, body = "") }
        repeat(4) {
            fetcher.startPass()
            assertTrue(fetcher.fetch(listOf("x"), backfill = false).isEmpty())
        }
    }

    @Test
    fun fetch_keyedNodeErrorItem_refetchedOnPublic() = runBlocking {
        keyedAvailable = true
        keyedAnswer = { if (it == "b") nodeError(-32004) else ok(it) }

        assertEquals(listOf("a", "b", "c"), fetcher.fetch(listOf("a", "b", "c"), backfill = false).keys.toList())
        assertEquals(listOf("b"), publicRequests)
    }

    @Test
    fun fetch_keyedBatchWithoutNewAnswersWhileBansExpire_continuesOnPublic() = runBlocking {
        keyedAvailable = true
        healthyKeys = Int.MAX_VALUE
        keyedAnswer = {
            check(batches.size <= 10) { "unbounded keyed retries" }
            RpcOutcome.RateLimited
        }

        assertEquals(listOf("a"), fetcher.fetch(listOf("a"), backfill = false).keys.toList())
        assertEquals(1, batches.size)
        assertEquals(listOf("a"), publicRequests)
    }

    @Test
    fun fetch_lastKeyRateLimitedInBatch_continuesOnPublicInSamePass() = runBlocking {
        keyedAvailable = true
        keyedAnswer = { if (it == "b") RpcOutcome.RateLimited else ok(it) }

        val fetched = fetcher.fetch(listOf("a", "b", "c"), backfill = false)

        assertEquals(listOf("a", "b", "c"), fetched.keys.toList())
        assertEquals(listOf("b"), publicRequests)
    }

    @Test
    fun fetch_keyRateLimitedInBatchWithAnotherKeyLeft_reRequestedOnKeyed() = runBlocking {
        keyedAvailable = true
        healthyKeys = 2
        val refused = mutableSetOf<String>()
        keyedAnswer = { if (it == "b" && refused.add(it)) RpcOutcome.RateLimited else ok(it) }

        val fetched = fetcher.fetch(listOf("a", "b", "c"), backfill = false)

        assertEquals(listOf("a", "b", "c"), fetched.keys.toList())
        assertEquals(listOf(listOf("a", "b", "c"), listOf("b")), batches)
        assertTrue(publicRequests.isEmpty())
    }

    @Test
    fun fetch_keyedUnavailable_continuesOnPublicWithinBudget() = runBlocking {
        keyedAvailable = true
        batchUnavailable = true
        val signatures = signatures(25)

        val fetched = fetcher.fetch(signatures, backfill = false)

        assertEquals(signatures.take(20), fetched.keys.toList())
        assertEquals(signatures.take(20), publicRequests)
    }

    @Test
    fun fetch_cancelledDuringRequest_cancellationPropagates() = runBlocking {
        publicAnswer = { throw CancellationException("cancelled") }

        try {
            fetcher.fetch(listOf("a"), backfill = false)
            fail("cancellation was swallowed")
        } catch (_: CancellationException) {
        }
    }

    private suspend fun fetchInPasses(passes: Int, answer: (String) -> RpcOutcome<TransactionResult>): Map<String, TransactionResult?> {
        val fetcher = TransactionFetcher(rpc)
        publicAnswer = answer
        var fetched = emptyMap<String, TransactionResult?>()
        repeat(passes) {
            fetcher.startPass()
            fetched = fetcher.fetch(listOf("a"), backfill = false)
        }
        return fetched
    }

    private fun signatures(count: Int, prefix: String = "s") = List(count) { "$prefix$it" }

    private fun signatureOf(request: RpcRequest) = checkNotNull(request.params?.jsonArray?.get(0)?.jsonPrimitive?.content)

    private fun nodeError(code: Int) = RpcOutcome.NodeError(RpcError(code, "node error $code"))

    private fun ok(signature: String): RpcOutcome<TransactionResult> = RpcOutcome.Success(
        transactionResult(signature, listOf("USER"), emptyList(), fee = 5_000, preBalances = listOf(1), postBalances = listOf(1))
    )
}
