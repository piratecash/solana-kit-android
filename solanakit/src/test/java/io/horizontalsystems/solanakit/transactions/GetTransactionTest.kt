package io.horizontalsystems.solanakit.transactions

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class GetTransactionTest {

    @Test
    fun request_includesMaxSupportedTransactionVersion() {
        val request = GetTransactionRequest("signature")

        assertEquals("signature", request.params[0].jsonPrimitive.content)

        val version = request.params[1].jsonObject["maxSupportedTransactionVersion"]
        assertEquals(
            JsonPrimitive(0),
            version
        )
    }
}
