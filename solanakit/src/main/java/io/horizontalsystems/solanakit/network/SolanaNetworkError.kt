package io.horizontalsystems.solanakit.network

import com.solana.networking.NetworkRequestError
import java.net.InetAddress
import java.net.URL

fun interface SolanaNetworkErrorListener {
    fun onNetworkError(error: SolanaNetworkError)
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
