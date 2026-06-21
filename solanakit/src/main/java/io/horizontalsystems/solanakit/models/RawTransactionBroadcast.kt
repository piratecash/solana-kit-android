package io.horizontalsystems.solanakit.models

import java.math.BigDecimal

class SignedRawSolanaTransaction(
    val raw: ByteArray,
    val base64Encoded: String,
    val signature: String,
    val fee: BigDecimal,
    val blockHash: String,
    val lastValidBlockHeight: Long,
)

data class RawTransactionBroadcastResult(
    val signature: String,
    val status: RawTransactionBroadcastStatus,
)

enum class RawTransactionBroadcastStatus {
    Submitted,
    Queued,
}

data class RawTransactionRetryMetadata(
    val blockHash: String,
    val lastValidBlockHeight: Long,
)
