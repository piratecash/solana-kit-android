package io.horizontalsystems.solanakit.network

import com.solana.networking.HttpRpcResponse
import com.solana.networking.NetworkingRouter
import com.solana.networking.RPCEndpoint
import com.solana.networking.RpcError
import com.solana.networking.RpcRequest
import com.solana.networking.RpcResponse
import com.solana.networking.decodeRpcResponse
import com.solana.networking.postJsonRpc
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** The classified RPC calls the transaction history walks on; [FailoverRpcRouter] in production. */
interface RpcExecutor {
    fun isKeyedAvailable(): Boolean

    suspend fun <R> execute(request: RpcRequest, serializer: KSerializer<R>, publicOnly: Boolean = false): RpcOutcome<R>

    /** Keyed only: a batch never reaches the public endpoint. Requests must carry distinct ids. */
    suspend fun <R> executeBatch(requests: List<RpcRequest>, serializer: KSerializer<R>): BatchOutcome<R>
}

/**
 * Sends requests through the next healthy API key and falls back to the paced public endpoint
 * once every key is banned. [endpoint] is the public one, so nothing shows a keyed host.
 */
class FailoverRpcRouter(
    private val publicEndpoint: RPCEndpoint,
    private val keys: ApiKeyRotation,
    private val alchemy: AlchemyEndpoint,
    private val pacer: PublicPacer,
    private val networkErrorListener: SolanaNetworkErrorListener?,
) : NetworkingRouter, RpcExecutor {

    override val endpoint: RPCEndpoint = publicEndpoint

    override fun isKeyedAvailable(): Boolean = keys.hasHealthyKey()

    override suspend fun <R> execute(
        request: RpcRequest,
        serializer: KSerializer<R>,
        publicOnly: Boolean,
    ): RpcOutcome<R> {
        val body = requestJson.encodeToString(RpcRequest.serializer(), request)
        // One attempt per key: a slow failure can outlive an earlier key's ban.
        if (!publicOnly) repeat(keys.size) {
            val key = keys.nextKey() ?: return@repeat
            val response = tryKeyed(key, request.method) { postJsonRpc(it, body) } ?: return@repeat
            val outcome = decode(response, serializer).redactKey(key)
            val ban = outcome.keyBan() ?: return outcome
            keys.ban(key, ban)
        }
        return pacer.paced(request.method) { executePublic(request.method, body, serializer) }
    }

    override suspend fun <R> executeBatch(requests: List<RpcRequest>, serializer: KSerializer<R>): BatchOutcome<R> {
        val body = requestJson.encodeToString(ListSerializer(RpcRequest.serializer()), requests)
        repeat(keys.size) {
            val key = keys.nextKey() ?: return BatchOutcome.KeyedUnavailable
            val response = tryKeyed(key, BATCH_METHOD) { postJsonRpc(it, body) } ?: return@repeat
            httpBan(response.status, response.retryAfter)?.let {
                keys.ban(key, it)
                return@repeat
            }
            val items = decodeBatch(response.body, requests, serializer).map { it.redactKey(key) }
            if (items.any { it.isRateLimited() }) keys.ban(key, RATE_LIMIT_BAN)
            return BatchOutcome.Results(items.map { if (it.isRateLimited()) RpcOutcome.RateLimited else it })
        }
        return BatchOutcome.KeyedUnavailable
    }

    /** Runs [block] against a keyed URL; if that fails, bans the key and runs it once on the paced public URL. */
    suspend fun <T : Any> withDirectUrl(method: String, block: suspend (URL) -> T): T =
        keys.nextKey()?.let { key -> tryKeyed(key, method, block) }
            ?: pacer.paced(method) { block(publicEndpoint.url) }

    override suspend fun <R> makeRequest(request: RpcRequest, resultSerializer: KSerializer<R>): RpcResponse<R> =
        when (val outcome = execute(request, resultSerializer)) {
            is RpcOutcome.Success -> RpcResponse(result = outcome.result)
            is RpcOutcome.NodeError -> RpcResponse(error = outcome.error)
            is RpcOutcome.TransportFailure -> failure(outcome.error.message ?: UNKNOWN_ERROR, retryAfter = null)
            is RpcOutcome.HttpFailure -> failure(outcome.body, outcome.retryAfter)
            is RpcOutcome.DecodeFailure -> failure(outcome.message, retryAfter = null)
            RpcOutcome.RateLimited -> failure(UNKNOWN_ERROR, retryAfter = null)
        }

    /** Null when [block] failed: the key is banned and the failure is reported without the key. */
    private suspend fun <T : Any> tryKeyed(key: String, method: String, block: suspend (URL) -> T): T? {
        val url = alchemy.url(key)
        return try {
            block(url)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            keys.ban(key, TRANSIENT_BAN)
            reportFailure(url.redactKey(key), method, error.redactKey(key))
            null
        }
    }

    private suspend fun <R> executePublic(method: String, body: String, serializer: KSerializer<R>): RpcOutcome<R> {
        val url = publicEndpoint.url
        val response = try {
            postJsonRpc(url, body)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            reportFailure(url, method, error)
            return RpcOutcome.TransportFailure(error)
        }
        return decode(response, serializer)
    }

    private fun <R> decode(response: HttpRpcResponse, serializer: KSerializer<R>): RpcOutcome<R> =
        if (response.status == HttpURLConnection.HTTP_OK) {
            decodeEnvelope(response.body, serializer, response.retryAfter)
        } else {
            RpcOutcome.HttpFailure(response.status, response.retryAfter, response.body)
        }

    private fun <R> decodeBatch(body: String, requests: List<RpcRequest>, serializer: KSerializer<R>): List<RpcOutcome<R>> {
        val items = try {
            Json.parseToJsonElement(body)
        } catch (error: Exception) {
            return requests.map { RpcOutcome.DecodeFailure(error.messageOrName()) }
        }
        // A batch-level error object answers every request alike.
        if (items !is JsonArray) {
            val outcome = decodeEnvelope(items.toString(), serializer, retryAfter = null)
            return requests.map { outcome }
        }
        val byId = items.associateBy { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull }
        return requests.map { request ->
            byId[request.id]?.let { decodeEnvelope(it.toString(), serializer, retryAfter = null) }
                ?: RpcOutcome.DecodeFailure("No response for request ${request.id}")
        }
    }

    /** Any decode exception, not only a serialization one, means the node answered something unusable. */
    private fun <R> decodeEnvelope(body: String, serializer: KSerializer<R>, retryAfter: Long?): RpcOutcome<R> {
        val response = try {
            decodeRpcResponse(body, serializer, retryAfter)
        } catch (error: Exception) {
            return RpcOutcome.DecodeFailure(error.messageOrName())
        }
        return response.error?.let { RpcOutcome.NodeError(it) } ?: RpcOutcome.Success(response.result)
    }

    private fun RpcOutcome<*>.keyBan(): Duration? = when (this) {
        is RpcOutcome.HttpFailure -> httpBan(status, retryAfter)
        is RpcOutcome.NodeError -> RATE_LIMIT_BAN.takeIf { error.isRateLimit() }
        else -> null
    }

    private fun httpBan(status: Int, retryAfter: Long?): Duration? = when (status) {
        HttpURLConnection.HTTP_OK -> null
        HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> AUTH_BAN
        HTTP_TOO_MANY_REQUESTS -> retryAfter?.takeIf { it > 0 }?.seconds ?: RATE_LIMIT_BAN
        else -> TRANSIENT_BAN
    }

    private fun RpcOutcome<*>.isRateLimited(): Boolean = this is RpcOutcome.NodeError && error.isRateLimit()

    private fun reportFailure(url: URL, method: String, error: Throwable) {
        networkErrorListener.emitSafely { url.toSolanaNetworkError(SOURCE, method, error) }
    }

    private fun <R> failure(message: String, retryAfter: Long?) =
        RpcResponse<R>(error = RpcError(code = -1, message = message).also { it.retryAfter = retryAfter })

    private fun Throwable.messageOrName(): String = message ?: javaClass.simpleName

    private companion object {
        const val SOURCE = "solana-rpc"
        const val BATCH_METHOD = "batch"
        const val UNKNOWN_ERROR = "Unknown error"
        const val HTTP_TOO_MANY_REQUESTS = 429
        val AUTH_BAN = 1.hours
        val RATE_LIMIT_BAN = 60.seconds
        val TRANSIENT_BAN = 30.seconds

        // `jsonrpc` and `id` are defaults of RpcRequest and must be sent.
        val requestJson = Json { encodeDefaults = true }
    }
}
