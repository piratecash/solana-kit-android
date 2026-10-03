package io.horizontalsystems.solanakit.noderpc.endpoints

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

        val signatures = Json.decodeFromString(
            ListSerializer(RpcSignatureInformation.serializer()),
            json
        ).map { it.toSignatureInformation() }

        val signature = signatures.single()
        assertEquals("4TSignature", signature.signature)
        assertEquals(123456789L, signature.slot)
        assertEquals(1710000000L, signature.blockTime)
        assertEquals("finalized", signature.confirmationStatus)
        assertNull(signature.memo)
    }
}
