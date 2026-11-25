package io.horizontalsystems.solanakit.network

import co.touchlab.kermit.Logger
import com.solana.networking.JsonRpcDriver
import com.solana.networking.RpcRequest
import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Internal, but read by the public inline [makeRequestResultWithRepeat]. */
@PublishedApi
internal val networkLogger: Logger = Logger.withTag("SolanaKit")

suspend inline fun <reified R> JsonRpcDriver.makeRequestResultWithRepeat(
    request: RpcRequest,
    serializer: KSerializer<R>,
    repeatCount: Int = 2
): Result<R?> {
    var timeout = 15000L
    repeat(repeatCount) { attempt ->
        try {
            this.makeRequest(request, serializer).let { response ->
                (response.result)?.let { result ->
                    return Result.success(result)
                }

                response.error?.let { errorResponse ->
                    errorResponse.retryAfter?.let { retryAfter ->
                        timeout = (retryAfter + 1) * 1000
                    }
                    val rpcException = parseRpcError(errorResponse.message)
                    check(rpcException?.error?.code != 429) {
                        "429: Too Many Requests"
                    }
                    return Result.failure(Error(errorResponse.message))
                }

                // an empty error and empty result means we did not find anything, return null
                return Result.success(null)
            }
        } catch (e: Exception) {
            val tooManyRequests = e.message?.contains("429") == true
            if (tooManyRequests) {
                networkLogger.d {
                    "makeRequestResultWithRepeat waiting for ${timeout / 1000} seconds to request ${request.method} with params ${request.params}"
                }
                /* retry-after header is not present in the response, so we can't use it to determine the delay */
                delay(timeout)
                timeout *= 1.5.toLong()
            } else {
                networkLogger.w(e) {
                    "makeRequestResultWithRepeat exception in ${request.method} with params ${request.params}"
                }
            }
        }
    }
    return Result.failure(Error("Failed to make request"))
}

@Serializable
data class RpcError(
    val code: Int,
    val message: String
)

@Serializable
data class RpcErrorResponse(
    val jsonrpc: String,
    val error: RpcError,
    val id: String?
)

fun parseRpcError(
    errorMessage: String
): RpcErrorResponse? {
    val serializer: KSerializer<RpcErrorResponse> = RpcErrorResponse.serializer()
    val json = Json { ignoreUnknownKeys = true }
    return try {
        json.decodeFromString(serializer, errorMessage)
    } catch (e: Exception) {
        networkLogger.w(e) { "Unexpected error while parsing error response: $errorMessage" }
        null
    }
}