package io.horizontalsystems.solanakit.transactions

import com.solana.api.Api
import com.solana.networking.NetworkingRouter
import com.solana.networking.RpcRequest
import com.solana.networking.RpcResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.sol4k.Base58

class AccountOwnersTest {

    private val router = mockk<NetworkingRouter>()
    private val requests = mutableListOf<RpcRequest>()

    @Test
    fun getAccountOwners_101Addresses_splitsInTwoRequestsAndKeepsAddressOrder() = runBlocking {
        coEvery { router.makeRequest<Any?>(any(), any()) } answers {
            val request = firstArg<RpcRequest>()
            requests += request
            RpcResponse(result = accountsOf(request).map { AccountOwner(ownerOf(it)) })
        }
        val addresses = List(101) { Base58.encode(ByteArray(32) { byte -> (it + byte).toByte() }) }

        val owners = Api(router).getAccountOwners(addresses).getOrThrow()

        assertEquals(listOf(100, 1), requests.map { accountsOf(it).size })
        assertEquals(addresses.associateWith(::ownerOf), owners)
        assertEquals("0", requests.first().params?.jsonArray?.get(1)?.jsonObject?.get("dataSlice")?.jsonObject?.get("length")?.jsonPrimitive?.content)
    }

    @Test
    fun accountOwnersSerializer_emptyDataSlice_decodesOwnerAndMissingAccount() {
        val response = """
            {"context":{"slot":1},"value":[
              {"data":["","base64"],"executable":false,"lamports":2039280,"owner":"${WellKnownPrograms.TOKEN_2022_PROGRAM}","rentEpoch":18446744073709551615,"space":175},
              null
            ]}
        """.trimIndent()

        val owners = requireNotNull(Json { ignoreUnknownKeys = true }.decodeFromString(accountOwnersSerializer, response))

        assertEquals(WellKnownPrograms.TOKEN_2022_PROGRAM, owners[0]?.owner)
        assertNull(owners[1])
    }

    private fun accountsOf(request: RpcRequest) =
        requireNotNull(request.params?.jsonArray?.get(0)?.jsonArray).map { it.jsonPrimitive.content }

    private fun ownerOf(address: String) = "owner-$address"
}
