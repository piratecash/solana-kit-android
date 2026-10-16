import com.solana.core.PublicKey
import com.solana.programs.TokenProgram
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class GetTokenAccountsByOwnerTest {

    @Test
    fun parsedRequest_buildsJsonParsedRequest() {
        val owner = PublicKey.valueOf("5sRHUTn6ShZBpDyHMxfgCHvMVHLoeZWoeaYsqMVV3ssc")
        val request = GetParsedTokenAccountsByOwnerRequest(owner)

        assertEquals("getTokenAccountsByOwner", request.method)
        assertEquals(owner.toString(), request.params[0].jsonPrimitive.content)
        assertEquals(
            TokenProgram.PROGRAM_ID.toString(),
            request.params[1].jsonObject["programId"]?.jsonPrimitive?.content
        )
        assertEquals(
            JsonPrimitive("jsonParsed"),
            request.params[2].jsonObject["encoding"]
        )
        assertEquals(
            JsonPrimitive("confirmed"),
            request.params[2].jsonObject["commitment"]
        )
    }

    @Test
    fun parsedSerializer_decodesRequiredFields() {
        val accounts = decodeParsedAccounts()

        assertEquals(1, accounts.size)
        assertEquals("Coa3i6QcRvGeckMzJebYbsNdkT5a29EFRkeseFEs3eS9", accounts.first().publicKey)
        assertEquals(
            "6p6xgHyF7AeE6TZkSmFsko444wqoP15icUSqi2jfGiPN",
            accounts.first().account.data.parsed.info.mint
        )
        assertEquals("489800", accounts.first().account.data.parsed.info.tokenAmount.amount)
        assertEquals(6, accounts.first().account.data.parsed.info.tokenAmount.decimals)
    }

    @Test
    fun parsedAccount_toTokenAccount_preservesRawAmount() {
        val account = decodeParsedAccounts().first().toTokenAccount()

        assertEquals("Coa3i6QcRvGeckMzJebYbsNdkT5a29EFRkeseFEs3eS9", account.address)
        assertEquals("6p6xgHyF7AeE6TZkSmFsko444wqoP15icUSqi2jfGiPN", account.mintAddress)
        assertEquals(BigDecimal("489800"), account.balance)
        assertEquals(6, account.decimals)
    }

    @Test
    fun parsedAccount_toMintAccount_mapsMintAndDecimals() {
        val account = decodeParsedAccounts().first().toMintAccount()

        assertEquals("6p6xgHyF7AeE6TZkSmFsko444wqoP15icUSqi2jfGiPN", account.address)
        assertEquals(6, account.decimals)
    }

    private fun decodeParsedAccounts(): List<ParsedSplTokenAccountWithPublicKey> {
        val json = Json { ignoreUnknownKeys = true }
        val fixture = """
            {
              "context": {
                "apiVersion": "4.0.3",
                "slot": 428504145
              },
              "value": [
                {
                  "account": {
                    "data": {
                      "parsed": {
                        "info": {
                          "isNative": false,
                          "mint": "6p6xgHyF7AeE6TZkSmFsko444wqoP15icUSqi2jfGiPN",
                          "owner": "5sRHUTn6ShZBpDyHMxfgCHvMVHLoeZWoeaYsqMVV3ssc",
                          "state": "initialized",
                          "tokenAmount": {
                            "amount": "489800",
                            "decimals": 6,
                            "uiAmount": 0.4898,
                            "uiAmountString": "0.4898"
                          }
                        },
                        "type": "account"
                      },
                      "program": "spl-token",
                      "space": 165
                    },
                    "executable": false,
                    "lamports": 2039280,
                    "owner": "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
                    "rentEpoch": 18446744073709551615,
                    "space": 165
                  },
                  "pubkey": "Coa3i6QcRvGeckMzJebYbsNdkT5a29EFRkeseFEs3eS9"
                }
              ]
            }
        """.trimIndent()

        return requireNotNull(json.decodeFromString(GetParsedTokenAccountsByOwnerSerializer(), fixture))
    }
}
