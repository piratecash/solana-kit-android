package io.horizontalsystems.solanakit.network

import com.solana.networking.RpcError
import java.net.URL

sealed interface RpcOutcome<out R> {
    data class Success<R>(val result: R?) : RpcOutcome<R>

    /** A JSON-RPC `error` object actually sent by the node. */
    data class NodeError(val error: RpcError) : RpcOutcome<Nothing>

    data class TransportFailure(val error: Throwable) : RpcOutcome<Nothing>
    data class HttpFailure(val status: Int, val retryAfter: Long?, val body: String) : RpcOutcome<Nothing>
    data class DecodeFailure(val message: String) : RpcOutcome<Nothing>

    /** A batch item the provider refused for rate or quota reasons; its key is banned. */
    data object RateLimited : RpcOutcome<Nothing>
}

sealed interface BatchOutcome<out R> {
    /** One outcome per request, in request order. */
    data class Results<R>(val items: List<RpcOutcome<R>>) : BatchOutcome<R>
    data object KeyedUnavailable : BatchOutcome<Nothing>
}

/** Carries a keyed failure without its cause, whose message may hold the keyed URL. */
class SolanaKeyedRpcException(message: String) : Exception(message)

private const val REDACTED = "***"

/** The single place that strips an API key from anything a keyed request produced. */
internal fun String.redactKey(key: String): String = replace(key, REDACTED)

internal fun Throwable.redactKey(key: String): SolanaKeyedRpcException =
    SolanaKeyedRpcException(toString().redactKey(key))

internal fun URL.redactKey(key: String): URL = URL(toString().redactKey(key))

internal fun <R> RpcOutcome<R>.redactKey(key: String): RpcOutcome<R> = when (this) {
    is RpcOutcome.NodeError -> RpcOutcome.NodeError(error.redactKey(key))
    is RpcOutcome.TransportFailure -> RpcOutcome.TransportFailure(error.redactKey(key))
    is RpcOutcome.HttpFailure -> copy(body = body.redactKey(key))
    is RpcOutcome.DecodeFailure -> RpcOutcome.DecodeFailure(message.redactKey(key))
    is RpcOutcome.Success, RpcOutcome.RateLimited -> this
}

private fun RpcError.redactKey(key: String): RpcError =
    RpcError(code, message.redactKey(key)).also { it.retryAfter = retryAfter }

private val rateLimitMarkers = listOf("rate limit", "quota", "capacity")

internal fun RpcError.isRateLimit(): Boolean =
    code == 429 || rateLimitMarkers.any { message.contains(it, ignoreCase = true) }
