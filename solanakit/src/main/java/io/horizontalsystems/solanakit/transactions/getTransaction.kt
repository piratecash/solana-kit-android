package io.horizontalsystems.solanakit.transactions

import com.solana.api.Api
import com.solana.api.Meta
import com.solana.api.Transaction
import com.solana.networking.RpcRequest
import io.horizontalsystems.solanakit.network.makeRequestResultWithRepeat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

class GetTransactionRequest(signature: String) : RpcRequest() {
    override val method: String = "getTransaction"
    override val params = buildJsonArray {
        add(signature)
        addJsonObject {
            put("maxSupportedTransactionVersion", 0)
        }
    }
}

@Serializable
data class TransactionResult(
    val blockTime: Long,
    val meta: Meta?,
    val slot: Long,
    val transaction: Transaction?
)

internal fun GetTransactionSerializer() = TransactionResult.serializer()

suspend fun Api.getTransaction(
    signature: String,
): Result<TransactionResult> =
    router.makeRequestResultWithRepeat(GetTransactionRequest(signature), GetTransactionSerializer())
        .let { result ->
            @Suppress("UNCHECKED_CAST")
            if (result.isSuccess && result.getOrNull() == null)
                Result.failure(Error("Can not be null"))
            else result as Result<TransactionResult> // safe cast, null case handled above
        }