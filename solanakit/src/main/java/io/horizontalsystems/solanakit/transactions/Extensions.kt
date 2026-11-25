package io.horizontalsystems.solanakit.transactions

import com.solana.actions.Action
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
import com.solana.networking.serialization.serializers.solana.SolanaResponseSerializer
import com.solana.programs.AssociatedTokenProgram
import com.solana.programs.SystemProgram
import com.solana.programs.TokenProgram
import io.horizontalsystems.solanakit.network.makeRequestResultWithRepeat
import io.reactivex.Single
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
): Result<List<MintTokenAccountValue?>?> =
    getMultipleAccountsChunked(accounts, mintAccountsSerializer) { chunk ->
        MultipleAccountsRequest(chunk, encoding, commitment, length, offset)
    }

// A closed mint account is answered with null.
internal val mintAccountsSerializer = SolanaResponseSerializer(ListSerializer(MintTokenAccountValue.serializer().nullable))

@Serializable
internal data class AccountOwner(val owner: String)

internal val accountOwnersSerializer = SolanaResponseSerializer(ListSerializer(AccountOwner.serializer().nullable))

/** Owner program by account address, null for a missing account; an empty data slice skips the account data. */
internal suspend fun Api.getAccountOwners(addresses: List<String>): Result<Map<String, String?>> =
    getMultipleAccountsChunked(addresses.map { PublicKey(it) }, accountOwnersSerializer) { chunk ->
        MultipleAccountsRequest(chunk, length = 0)
    }.mapCatching { owners ->
        addresses.zip(checkNotNull(owners) { "Empty getMultipleAccounts result" }.map { it?.owner }).toMap()
    }

private suspend inline fun <reified T> Api.getMultipleAccountsChunked(
    accounts: List<PublicKey>,
    serializer: KSerializer<List<T>?>,
    request: (List<String>) -> MultipleAccountsRequest,
): Result<List<T>?> {
    val values = mutableListOf<T>()
    for (chunk in accounts.chunked(MAX_MULTIPLE_ACCOUNTS)) {
        val result = router.makeRequestResultWithRepeat(request(chunk.map { Base58.encode(it.pubkey) }), serializer)
        values += result.getOrElse { return Result.failure(it) } ?: return Result.success(null)
    }
    return Result.success(values)
}

// RPC limit of getMultipleAccounts.
private const val MAX_MULTIPLE_ACCOUNTS = 100

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

internal fun signSPLTokens(
    transfer: SplTransfer,
    amount: Long,
    account: Account,
    instructions: List<TransactionInstruction>,
    recentBlockHash: String
): SignedSolanaTransactionData {
    require(transfer.source.toBase58() != transfer.destination.toBase58()) { "Same send and destination address." }
    return splTransferTransaction(transfer, amount, account.publicKey, instructions)
        .signAndSerialize(account, recentBlockHash)
}

/** Accounts of one SPL transfer; [programId] is the token program that owns the mint's accounts. */
internal class SplTransfer(
    val programId: PublicKey,
    val mint: PublicKey,
    val decimals: Int,
    val source: PublicKey,
    val destination: PublicKey,
    val destinationOwner: PublicKey,
    val createDestination: Boolean,
)

internal fun splTransferTransaction(
    transfer: SplTransfer,
    amount: Long,
    owner: PublicKey,
    instructions: List<TransactionInstruction>,
): Transaction = Transaction().apply {
    if (instructions.isNotEmpty()) {
        add(*instructions.toTypedArray())
    }
    if (transfer.createDestination) {
        add(
            AssociatedTokenProgram.createAssociatedTokenAccountInstruction(
                programId = transfer.programId,
                mint = transfer.mint,
                associatedAccount = transfer.destination,
                owner = transfer.destinationOwner,
                payer = owner
            )
        )
    }
    // Ledger's Solana app never parses the deprecated Transfer, so only TransferChecked signs in clear.
    val transferChecked = TokenProgram.transferChecked(
        transfer.source, transfer.destination, amount, transfer.decimals.toByte(), owner, transfer.mint
    )
    // Token-2022 keeps the TransferChecked layout of the legacy program.
    add(transferChecked.copy(programId = transfer.programId))
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
