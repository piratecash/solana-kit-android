package io.horizontalsystems.solanakit.noderpc.endpoints

import com.solana.api.Api
import com.solana.core.PublicKey
import com.solana.networking.RpcRequest
import io.horizontalsystems.solanakit.network.makeRequestResultWithRepeat
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

class GetConfirmedSignaturesForAddressRequest(
    account: PublicKey,
    limit: Int? = null,
    before: String? = null,
    until: String? = null,
) : RpcRequest() {
    override val method: String = "getSignaturesForAddress"
    override val params = buildJsonArray {
        add(account.toString())
        addJsonObject {
            put("limit", limit?.toLong())
            put("before", before)
            put("until", until)
        }
    }
}

/** A missing list is a failure too: read as an empty page, it would end the listing early. */
internal suspend fun Api.getSignaturesForAddress(
    account: PublicKey,
    limit: Int? = null,
    before: String? = null,
    until: String? = null
): Result<List<RpcSignatureInformation>> =
    router.makeRequestResultWithRepeat(
        request = GetConfirmedSignaturesForAddressRequest(account, limit, before, until),
        serializer = ListSerializer(RpcSignatureInformation.serializer())
    ).mapCatching { checkNotNull(it) { "No signature list" } }

@Serializable
internal data class RpcSignatureInformation(
    val err: JsonElement? = null,
    val memo: JsonElement? = null,
    val signature: String,
    val confirmationStatus: String?,
    val slot: Long,
    val blockTime: Long?
)
