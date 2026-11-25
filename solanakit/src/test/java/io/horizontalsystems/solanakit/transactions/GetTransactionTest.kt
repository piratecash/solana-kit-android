package io.horizontalsystems.solanakit.transactions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GetTransactionTest {

    @Test
    fun request_includesMaxSupportedTransactionVersion() {
        val request = GetTransactionRequest("signature")

        assertEquals("signature", request.params[0].jsonPrimitive.content)

        val version = request.params[1].jsonObject["maxSupportedTransactionVersion"]
        assertEquals(
            JsonPrimitive(1),
            version
        )
    }

    @Test
    fun decode_nullBlockTime_decodesTransaction() {
        val json = """{"blockTime": null, "meta": null, "slot": 7, "transaction": null}"""

        val result = Json.decodeFromString(GetTransactionSerializer(), json)

        assertNull(result.blockTime)
        assertEquals(7L, result.slot)
    }

    @Test
    fun map_nullBlockTime_timestampZero() {
        val result = transactionResult("sig", listOf("USER"), emptyList(), fee = 5_000, preBalances = listOf(1), postBalances = listOf(1))
            .copy(blockTime = null)

        val mapped = SolanaTransactionMapper.map("USER", listOf(result), emptyMap()).single()

        assertEquals(0L, mapped.transaction.timestamp)
    }
}
