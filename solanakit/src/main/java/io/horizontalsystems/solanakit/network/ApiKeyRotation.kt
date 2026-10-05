package io.horizontalsystems.solanakit.network

import com.solana.networking.Network
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource

class ApiKeyRotation(
    keys: List<String>,
    random: Random,
    private val timeSource: TimeSource.WithComparableMarks,
) {
    private val keys = keys.filter { it.isNotBlank() }
    private val nextIndex = AtomicInteger(if (this.keys.isEmpty()) 0 else random.nextInt(this.keys.size))
    private val bannedUntil = AtomicReference<Map<String, ComparableTimeMark>>(emptyMap())

    val size: Int get() = keys.size

    /** Round-robin over the keys that are not banned; null when every key is banned or there are none. */
    fun nextKey(): String? {
        repeat(keys.size) {
            val key = keys[nextIndex.getAndIncrement().mod(keys.size)]
            if (!isBanned(key)) return key
        }
        return null
    }

    fun hasHealthyKey(): Boolean = keys.any { !isBanned(it) }

    fun ban(key: String, duration: Duration) {
        val until = timeSource.markNow() + duration
        bannedUntil.updateAndGet { banned ->
            banned + (key to maxOf(banned[key] ?: until, until))
        }
    }

    private fun isBanned(key: String): Boolean = bannedUntil.get()[key]?.hasNotPassedNow() == true

    companion object {
        /** The keys are Alchemy mainnet keys, so any other network gets none. */
        fun forNetwork(network: Network, keys: List<String>): ApiKeyRotation =
            ApiKeyRotation(
                keys = if (network == Network.mainnetBeta) keys else emptyList(),
                random = Random.Default,
                timeSource = TimeSource.Monotonic,
            )
    }
}
