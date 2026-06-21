package io.horizontalsystems.solanakit.transactions

import com.solana.actions.Action
import com.solana.actions.findSPLTokenDestinationAddress
import com.solana.api.Api
import com.solana.api.MultipleAccountsRequest
import com.solana.core.Account
import com.solana.core.PublicKey
import org.sol4k.Base58
import com.solana.core.Transaction
import com.solana.core.TransactionInstruction
import com.solana.models.RpcSendTransactionConfig
import com.solana.networking.Commitment
import com.solana.networking.RpcRequest
import com.solana.networking.makeRequestResult
import com.solana.networking.serialization.serializers.base64.BorshAsBase64JsonArraySerializer
import com.solana.networking.serialization.serializers.solana.AnchorAccountSerializer
import com.solana.networking.serialization.serializers.solana.SolanaResponseSerializer
import com.solana.programs.AssociatedTokenProgram
import com.solana.programs.SystemProgram
import com.solana.programs.TokenProgram
import com.solana.vendor.ContResult
import com.solana.vendor.ResultError
import com.solana.vendor.flatMap
import io.horizontalsystems.solanakit.models.AccountInfoFixed
import io.horizontalsystems.solanakit.network.makeRequestResultWithRepeat
import io.reactivex.Single
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import java.util.Base64

internal data class SignedSolanaTransactionData(
    val signature: String,
    val raw: ByteArray,
    val base64Encoded: String,
)

fun <T> Api.getMultipleAccountsFixed(
    serializer: KSerializer<T>,
    accounts: List<PublicKey>,
): Single<List<AccountInfoFixed<T>?>> = Single.create { emitter ->
    getMultipleAccounts(serializer, accounts) { result ->
        result.onSuccess { emitter.onSuccess(it) }
        result.onFailure { emitter.onError(it) }
    }
}

fun <A> Api.getMultipleAccounts(
    serializer: KSerializer<A>,
    accounts: List<PublicKey>,
    onComplete: ((Result<List<AccountInfoFixed<A>?>>) -> Unit)
) {
    CoroutineScope(dispatcher + CoroutineExceptionHandler { coroutineContext, throwable ->
        onComplete(Result.failure(ResultError(throwable)))
    }).launch {
        getMultipleAccountsInfo(
            serializer = serializer,
            accounts
        ).onSuccess {
            val buffers = it.map { account ->
                account
            }
            onComplete(Result.success(buffers))
        }.onFailure {
            onComplete(Result.failure(ResultError(it)))
        }
    }
}

@Serializable
data class MintTokenAccountValue(
    val data: MintTokenAccountInfo?,
    val owner: String,
)

@Serializable
data class MintTokenAccountInfo(
    val parsed: MintTokenAccountInfoParsedData,
    val program: String,
    val space: Int? = null
)

@Serializable
data class MintTokenAccountInfoParsedData(
    val info: MintTokenAccountTokenInfo,
    val type: String
)

@Serializable
data class MintTokenAccountTokenInfo(
    val decimals: Int,
    val isInitialized: Boolean,
    val mintAuthority: String?,
    val supply: String,
)

suspend fun Api.getMultipleMintAccountsInfo(
    accounts: List<PublicKey>,
    encoding: RpcSendTransactionConfig.Encoding = RpcSendTransactionConfig.Encoding.jsonParsed,
    commitment: String = "max",
    length: Int? = null,
    offset: Int? = length?.let { 0 }
): Result<List<MintTokenAccountValue>?> =
    router.makeRequestResultWithRepeat(
        request = MultipleAccountsRequest(
            accounts = accounts.map { Base58.encode(it.pubkey) },
            encoding = encoding,
            commitment = commitment,
            length = length,
            offset = offset
        ),
        serializer = SolanaResponseSerializer(ListSerializer(MintTokenAccountValue.serializer()))
    ).let { result ->
        @Suppress("UNCHECKED_CAST")
        if (result.isSuccess && result.getOrNull() == null) Result.success(null)
        else result as Result<List<MintTokenAccountValue>> // safe cast, null case handled above
    }

suspend fun <A> Api.getMultipleAccountsInfo(
    serializer: KSerializer<A>,
    accounts: List<PublicKey>,
    encoding: RpcSendTransactionConfig.Encoding = RpcSendTransactionConfig.Encoding.base64,
    commitment: String = "max",
    length: Int? = null,
    offset: Int? = length?.let { 0 }
): Result<List<AccountInfoFixed<A>?>> =
    router.makeRequestResultWithRepeat(
        request = MultipleAccountsRequest(
            accounts = accounts.map { Base58.encode(it.pubkey) },
            encoding = encoding,
            commitment = commitment,
            length = length,
            offset = offset
        ),
        serializer = MultipleAccountsSerializer(serializer)
    ).let { result ->
        @Suppress("UNCHECKED_CAST")
        if (result.isSuccess && result.getOrNull() == null) Result.success(listOf())
        else result as Result<List<AccountInfoFixed<A>?>> // safe cast, null case handled above
    }

internal fun <A> MultipleAccountsSerializer(serializer: KSerializer<A>) =
    MultipleAccountsInfoSerializer(
        BorshAsBase64JsonArraySerializer(
            AnchorAccountSerializer(serializer.descriptor.serialName, serializer)
        )
    )

private fun <D> MultipleAccountsInfoSerializer(serializer: KSerializer<D>) =
    SolanaResponseSerializer(ListSerializer(AccountInfoFixed.serializer(serializer).nullable))

internal fun Action.signSOL(
    account: Account,
    destination: PublicKey,
    amount: Long,
    instructions: List<TransactionInstruction>,
    recentBlockHash: String
) = Single.fromCallable {
    val transaction = Transaction()

    if (instructions.isNotEmpty()) {
        transaction.add(*instructions.toTypedArray())
    }

    transaction.add(SystemProgram.transfer(account.publicKey, destination, amount))
    transaction.signAndSerialize(account, recentBlockHash)
}

internal fun Action.signSPLTokens(
    mintAddress: PublicKey,
    fromPublicKey: PublicKey,
    destinationAddress: PublicKey,
    amount: Long,
    allowUnfundedRecipient: Boolean = false,
    account: Account,
    instructions: List<TransactionInstruction>,
    recentBlockHash: String
) = Single.create { emitter ->
    ContResult { cb ->
        this.findSPLTokenDestinationAddress(
            mintAddress,
            destinationAddress,
            allowUnfundedRecipient
        ) { cb(it) }
    }.flatMap { spl ->
        val toPublicKey = spl.first
        val unregisteredAssociatedToken = spl.second
        if (Base58.encode(fromPublicKey.pubkey) == Base58.encode(toPublicKey.pubkey)) {
            return@flatMap ContResult.failure(ResultError("Same send and destination address."))
        }
        val transaction = Transaction()

        if (instructions.isNotEmpty()) {
            transaction.add(*instructions.toTypedArray())
        }

        if (unregisteredAssociatedToken) {
            val createATokenInstruction =
                AssociatedTokenProgram.createAssociatedTokenAccountInstruction(
                    mint = mintAddress,
                    associatedAccount = toPublicKey,
                    owner = destinationAddress,
                    payer = account.publicKey
                )
            transaction.add(createATokenInstruction)
        }

        transaction.add(TokenProgram.transfer(fromPublicKey, toPublicKey, amount, account.publicKey))
        return@flatMap ContResult.success(transaction)
    }.run { result ->
        result.onSuccess { transaction ->
            try {
                emitter.onSuccess(transaction.signAndSerialize(account, recentBlockHash))
            } catch (error: Throwable) {
                emitter.onError(error)
            }
        }.onFailure {
            emitter.onError(it)
        }
    }
}

internal suspend fun Api.sendRawTransactionWithoutRetries(transaction: ByteArray): Result<String> {
    val base64Transaction = Base64.getEncoder().encodeToString(transaction)
    return router.makeRequestResult(
        SendRawTransactionNoRetriesRequest(base64Transaction),
        String.serializer()
    ).let { result ->
        @Suppress("UNCHECKED_CAST")
        if (result.isSuccess && result.getOrNull() == null) {
            Result.failure(Error("Can not send transaction"))
        } else {
            result as Result<String>
        }
    }
}

private fun Transaction.signAndSerialize(account: Account, recentBlockHash: String): SignedSolanaTransactionData {
    setRecentBlockHash(recentBlockHash)
    sign(listOf(account))
    val signature = requireNotNull(signature) { "Signed transaction has no signature" }
    val raw = serialize()
    return SignedSolanaTransactionData(
        signature = Base58.encode(signature),
        raw = raw,
        base64Encoded = Base64.getEncoder().encodeToString(raw),
    )
}

private class SendRawTransactionNoRetriesRequest(serializedTransaction: String) : RpcRequest(
    method = "sendTransaction",
    params = buildJsonArray {
        add(serializedTransaction)
        addJsonObject {
            put("encoding", RpcSendTransactionConfig.Encoding.base64.getEncoding())
            put("skipPreflight", false)
            put("preflightCommitment", Commitment.CONFIRMED.toString())
            put("maxRetries", 0)
        }
    }
)
