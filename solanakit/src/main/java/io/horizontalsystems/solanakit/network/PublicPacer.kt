package io.horizontalsystems.solanakit.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** The public node limits every JSON-RPC method separately, so each method gets its own queue. */
class PublicPacer(private val timeSource: TimeSource.WithComparableMarks) {

    private class MethodSlot {
        val mutex = Mutex()
        var lastStart: ComparableTimeMark? = null
    }

    private val slots = ConcurrentHashMap<String, MethodSlot>()

    suspend fun <T> paced(method: String, block: suspend () -> T): T {
        val slot = slots.getOrPut(method) { MethodSlot() }
        return slot.mutex.withLock {
            slot.lastStart?.let { delay(MIN_INTERVAL - it.elapsedNow()) }
            slot.lastStart = timeSource.markNow()
            block()
        }
    }

    private companion object {
        val MIN_INTERVAL = 1.seconds
    }
}
