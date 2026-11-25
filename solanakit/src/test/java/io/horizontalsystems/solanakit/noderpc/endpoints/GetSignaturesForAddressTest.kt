package io.horizontalsystems.solanakit.noderpc.endpoints

import com.solana.api.Api
import com.solana.core.PublicKey
import com.solana.networking.NetworkingRouter
import com.solana.networking.RpcResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GetSignaturesForAddressTest {

    @Test
    fun decode_responseWithStringMemo_decodesSignatureInformation() {
        val json = """
            [
              {
                "blockTime": 1710000000,
                "confirmationStatus": "finalized",
                "err": null,
                "memo": "[8] 5e068712",
                "signature": "4TSignature",
                "slot": 123456789
              }
            ]
        """.trimIndent()

        val signature = decode(json).single()

        assertEquals("4TSignature", signature.signature)
        assertEquals(123456789L, signature.slot)
        assertEquals(1710000000L, signature.blockTime)
        assertEquals("finalized", signature.confirmationStatus)
        assertEquals(JsonPrimitive("[8] 5e068712"), signature.memo)
    }

    @Test
    fun decode_nullBlockTimeAndConfirmationStatus_keepsTheEntry() {
        val json = """
            [
              {"blockTime": null, "confirmationStatus": null, "err": null, "memo": null, "signature": "a", "slot": 2},
              {"blockTime": 1710000000, "confirmationStatus": "finalized", "err": null, "memo": null, "signature": "b", "slot": 1}
            ]
        """.trimIndent()

        val signatures = decode(json)

        assertEquals(listOf("a", "b"), signatures.map { it.signature })
        assertNull(signatures.first().blockTime)
        assertNull(signatures.first().confirmationStatus)
    }

    @Test
    fun getSignaturesForAddress_nullResult_isFailure() = runBlocking {
        val router = mockk<NetworkingRouter>()
        coEvery { router.makeRequest<Any?>(any(), any()) } returns RpcResponse(result = null)

        val result = Api(router).getSignaturesForAddress(PublicKey(ACCOUNT), limit = 1)

        assertTrue(result.isFailure)
    }

    private fun decode(json: String) = Json.decodeFromString(ListSerializer(RpcSignatureInformation.serializer()), json)

    private companion object {
        const val ACCOUNT = "11111111111111111111111111111111"
    }
}
