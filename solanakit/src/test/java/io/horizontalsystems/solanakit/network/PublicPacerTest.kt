package io.horizontalsystems.solanakit.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

// Real time: the kit has no kotlinx-coroutines-test dependency.
class PublicPacerTest {

    private val pacer = PublicPacer(TimeSource.Monotonic)

    @Test
    fun paced_sameMethod_serializedAndOneSecondApart() = runBlocking {
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()

        val starts = List(3) {
            async(Dispatchers.Default) {
                pacer.paced("getTransaction") {
                    val start = TimeSource.Monotonic.markNow()
                    maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                    delay(50)
                    inFlight.decrementAndGet()
                    start
                }
            }
        }.awaitAll().sorted()

        assertEquals(1, maxInFlight.get())
        starts.zipWithNext().forEach { (first, second) ->
            assertTrue(second - first >= MIN_GAP)
        }
    }

    @Test
    fun paced_differentMethods_independent() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val blocked = launch(Dispatchers.Default) {
            pacer.paced("getTransaction") {
                started.complete(Unit)
                release.await()
            }
        }
        started.await()

        val other = withTimeout(500.milliseconds) { pacer.paced("getSignaturesForAddress") { "done" } }

        assertEquals("done", other)
        release.complete(Unit)
        blocked.join()
    }

    companion object {
        /** Pacing is 1 s; a few ms of slack absorbs the gap between the pacer's mark and the block's own. */
        val MIN_GAP = 990.milliseconds
    }
}
