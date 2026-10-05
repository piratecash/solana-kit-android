package io.horizontalsystems.solanakit.network

import com.solana.networking.Network
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class ApiKeyRotationTest {

    private val timeSource = TestTimeSource()

    @Test
    fun nextKey_seededRandom_startsAtRandomIndex() {
        val keys = listOf("a", "b", "c", "d", "e")

        val firstKeys = (0..20).map { seed -> ApiKeyRotation(keys, Random(seed), timeSource).nextKey() }

        (0..20).forEach { seed -> assertEquals(keys[Random(seed).nextInt(keys.size)], firstKeys[seed]) }
        assertTrue(firstKeys.toSet().size > 1)
    }

    @Test
    fun nextKey_noBans_roundRobin() {
        val rotation = rotation("a", "b", "c")

        assertEquals(listOf("a", "b", "c", "a", "b", "c"), List(6) { rotation.nextKey() })
    }

    @Test
    fun nextKey_bannedKey_skippedUntilBanExpires() {
        val rotation = rotation("a", "b")

        rotation.ban("a", 10.seconds)
        assertEquals(listOf("b", "b", "b"), List(3) { rotation.nextKey() })

        timeSource += 10.seconds
        assertEquals(setOf("a", "b"), List(2) { rotation.nextKey() }.toSet())
    }

    @Test
    fun ban_shorterBanOverLongerOne_keepsLongerBan() {
        val rotation = rotation("a", "b")

        rotation.ban("a", 60.seconds)
        rotation.ban("a", 1.seconds)
        timeSource += 30.seconds

        assertEquals(listOf("b", "b"), List(2) { rotation.nextKey() })
    }

    @Test
    fun nextKey_allBanned_returnsNull() {
        val rotation = rotation("a", "b")

        rotation.ban("a", 10.seconds)
        rotation.ban("b", 10.seconds)

        assertNull(rotation.nextKey())
        assertFalse(rotation.hasHealthyKey())
    }

    @Test
    fun nextKey_blankKeys_filteredOut() {
        val rotation = rotation("", "  ", "k")

        assertEquals(listOf("k", "k", "k"), List(3) { rotation.nextKey() })
    }

    @Test
    fun nextKey_noKeys_returnsNull() {
        val rotation = rotation()

        assertNull(rotation.nextKey())
        assertFalse(rotation.hasHealthyKey())
    }

    @Test
    fun forNetwork_offMainnet_ignoresKeys() {
        assertNull(ApiKeyRotation.forNetwork(Network.devnet, listOf("k")).nextKey())
        assertNull(ApiKeyRotation.forNetwork(Network.testnet, listOf("k")).nextKey())
        assertEquals("k", ApiKeyRotation.forNetwork(Network.mainnetBeta, listOf("k")).nextKey())
    }

    private fun rotation(vararg keys: String) = ApiKeyRotation(keys.toList(), FirstIndexRandom, timeSource)
}

/** Makes the rotation start at the first key. */
internal object FirstIndexRandom : Random() {
    override fun nextBits(bitCount: Int): Int = 0
}
