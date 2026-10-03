import com.solana.api.Api
import com.solana.core.PublicKey
import com.solana.models.RpcSendTransactionConfig
import com.solana.networking.RpcRequest
import com.solana.networking.serialization.serializers.solana.SolanaResponseSerializer
import com.solana.programs.TokenProgram
import io.horizontalsystems.solanakit.models.MintAccount
import io.horizontalsystems.solanakit.models.TokenAccount
import io.horizontalsystems.solanakit.network.makeRequestResultWithRepeat
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

class GetTokenAccountsByOwnerRequest(tokenAccount: PublicKey) : RpcRequest() {
    override val method: String = "getTokenAccountsByOwner"
    override val params = buildJsonArray {
        add(tokenAccount.toString())
        addJsonObject {
            put("programId", TokenProgram.PROGRAM_ID.toString())
        }
        addJsonObject {
            put("encoding", RpcSendTransactionConfig.Encoding.base64.getEncoding())
        }
    }
}

class GetParsedTokenAccountsByOwnerRequest(owner: PublicKey) : RpcRequest() {
    override val method: String = "getTokenAccountsByOwner"
    override val params = buildJsonArray {
        add(owner.toString())
        addJsonObject {
            put("programId", TokenProgram.PROGRAM_ID.toString())
        }
        addJsonObject {
            put("encoding", RpcSendTransactionConfig.Encoding.jsonParsed.getEncoding())
            put("commitment", "confirmed")
        }
    }
}

@Serializable
data class SplTokenAccountWithPublicKey(
    // No need other fields
    @SerialName("pubkey") val publicKey: String
)

@Serializable
data class ParsedSplTokenAccountWithPublicKey(
    @SerialName("pubkey") val publicKey: String,
    val account: ParsedSplTokenAccount
) {
    fun toTokenAccount(): TokenAccount {
        val info = account.data.parsed.info
        return TokenAccount(
            address = publicKey,
            mintAddress = info.mint,
            balance = info.tokenAmount.amount.toBigDecimal(),
            decimals = info.tokenAmount.decimals
        )
    }

    fun toMintAccount(): MintAccount {
        val info = account.data.parsed.info
        return MintAccount(info.mint, info.tokenAmount.decimals)
    }
}

@Serializable
data class ParsedSplTokenAccount(
    val data: ParsedSplTokenAccountData
)

@Serializable
data class ParsedSplTokenAccountData(
    val parsed: ParsedSplTokenAccountParsedData
)

@Serializable
data class ParsedSplTokenAccountParsedData(
    val info: ParsedSplTokenAccountInfo
)

@Serializable
data class ParsedSplTokenAccountInfo(
    val mint: String,
    val tokenAmount: ParsedSplTokenAmount
)

@Serializable
data class ParsedSplTokenAmount(
    val amount: String,
    val decimals: Int
)

internal fun GetTokenAccountByOwnerSerializer() =
    SolanaResponseSerializer(ListSerializer(SplTokenAccountWithPublicKey.serializer().nullable))

internal fun GetParsedTokenAccountsByOwnerSerializer() =
    SolanaResponseSerializer(ListSerializer(ParsedSplTokenAccountWithPublicKey.serializer()))

suspend fun Api.getTokenAccountsByOwner(tokenAccount: PublicKey): Result<List<SplTokenAccountWithPublicKey>> =
    router.makeRequestResultWithRepeat(
        GetTokenAccountsByOwnerRequest(tokenAccount),
        GetTokenAccountByOwnerSerializer()
    ).let { result ->
        @Suppress("UNCHECKED_CAST")
        if (result.isSuccess && result.getOrNull() == null)
            Result.failure(Error("Can not be null"))
        else result as Result<List<SplTokenAccountWithPublicKey>> // safe cast, null case handled above
    }

suspend fun Api.getParsedTokenAccountsByOwner(owner: PublicKey): Result<List<ParsedSplTokenAccountWithPublicKey>> =
    router.makeRequestResultWithRepeat(
        GetParsedTokenAccountsByOwnerRequest(owner),
        GetParsedTokenAccountsByOwnerSerializer()
    ).let { result ->
        @Suppress("UNCHECKED_CAST")
        if (result.isSuccess && result.getOrNull() == null)
            Result.failure(Error("Can not be null"))
        else result as Result<List<ParsedSplTokenAccountWithPublicKey>> // safe cast, null case handled above
    }
