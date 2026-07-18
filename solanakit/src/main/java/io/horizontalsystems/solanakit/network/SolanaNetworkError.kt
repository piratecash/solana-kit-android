package io.horizontalsystems.solanakit.network

import com.solana.networking.NetworkRequestError
import java.net.InetAddress
import java.net.URL

fun interface SolanaNetworkErrorListener {
    fun onNetworkError(error: SolanaNetworkError)
}

/**
 * Safe, lazy emission: the [buildError] lambda (which may resolve DNS) runs only
 * when a listener is installed, and any Throwable from the listener is swallowed
 * so diagnostics can never break the observed network error path.
 */
internal inline fun SolanaNetworkErrorListener?.emitSafely(buildError: () -> SolanaNetworkError) {
    val listener = this ?: return
    try {
        listener.onNetworkError(buildError())
    } catch (_: Throwable) {
    }
}

data class SolanaNetworkError(
    val source: String,
    val method: String,
    val url: String,
    val host: String,
    val resolvedIps: List<String>,
    val throwable: Throwable
)

internal fun NetworkRequestError.toSolanaNetworkError(source: String): SolanaNetworkError =
    SolanaNetworkError(
        source = source,
        method = method,
        url = url,
        host = host,
        resolvedIps = resolvedIps,
        throwable = throwable
    )

internal fun URL.toSolanaNetworkError(
    source: String,
    method: String,
    throwable: Throwable
): SolanaNetworkError =
    SolanaNetworkError(
        source = source,
        method = method,
        url = toString(),
        host = host,
        resolvedIps = resolveHostAddresses(),
        throwable = throwable
    )

private fun URL.resolveHostAddresses(): List<String> = try {
    InetAddress.getAllByName(host)
        .mapNotNull { it.hostAddress }
        .distinct()
} catch (_: Throwable) {
    emptyList()
}
