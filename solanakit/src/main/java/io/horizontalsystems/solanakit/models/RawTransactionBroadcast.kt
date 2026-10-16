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
    // The node reports the signature as already processed, meaning a previous
    // broadcast attempt already reached the network. Distinguished from
    // Submitted so callers don't mistake it for a fresh, successful send.
    AlreadyKnown,
}

data class RawTransactionRetryMetadata(
    val blockHash: String,
    val lastValidBlockHeight: Long,
)
