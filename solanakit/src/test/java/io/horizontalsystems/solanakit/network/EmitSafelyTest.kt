package io.horizontalsystems.solanakit.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EmitSafelyTest {

    private fun sampleError() = SolanaNetworkError(
        source = "test",
        method = "GET",
        url = "https://example.test/x",
        host = "example.test",
        resolvedIps = emptyList(),
        throwable = RuntimeException("boom")
    )

    @Test
    fun emitSafely_throwingListener_doesNotPropagate() {
        val listener = SolanaNetworkErrorListener { throw RuntimeException("listener boom") }

        // Must not throw — diagnostics never break the observed error path.
        listener.emitSafely { sampleError() }
    }

    @Test
    fun emitSafely_nullListener_doesNotBuildError() {
        var built = false
        val listener: SolanaNetworkErrorListener? = null

        listener.emitSafely {
            built = true
            sampleError()
        }

        assertFalse(built)
    }

    @Test
    fun emitSafely_workingListener_receivesBuiltError() {
        var received: SolanaNetworkError? = null
        val listener = SolanaNetworkErrorListener { received = it }

        listener.emitSafely { sampleError() }

        assertEquals("test", received?.source)
    }
}
