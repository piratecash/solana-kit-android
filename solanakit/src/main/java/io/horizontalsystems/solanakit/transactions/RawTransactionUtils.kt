package io.horizontalsystems.solanakit.transactions

import com.solana.core.PACKET_DATA_SIZE
import com.solana.core.SIGNATURE_LENGTH
import org.sol4k.Base58
import org.sol4k.Binary
import org.sol4k.VersionedTransaction
import java.util.Base64

internal fun rawTransactionSignature(rawTransaction: ByteArray): String {
    require(rawTransaction.isNotEmpty()) { "Raw transaction is empty" }
    require(rawTransaction.size <= PACKET_DATA_SIZE) {
        "Transaction too large: ${rawTransaction.size} > $PACKET_DATA_SIZE"
    }

    val decodedLength = try {
        Binary.decodeLength(rawTransaction)
    } catch (error: Throwable) {
        throw IllegalArgumentException("Invalid transaction signature count", error)
    }

    require(decodedLength.length > 0) { "Transaction has no signatures" }

    val signaturesSize = decodedLength.length.toLong() * SIGNATURE_LENGTH
    require(signaturesSize <= decodedLength.bytes.size) { "Transaction signature data is truncated" }

    val signature = decodedLength.bytes.copyOfRange(0, SIGNATURE_LENGTH)
    require(!signature.contentEquals(ByteArray(SIGNATURE_LENGTH))) { "Transaction signature is empty" }

    validateVersionedTransaction(rawTransaction)

    return Base58.encode(signature)
}

private fun validateVersionedTransaction(rawTransaction: ByteArray) {
    val encoded = Base64.getEncoder().encodeToString(rawTransaction)
    try {
        VersionedTransaction.from(encoded)
    } catch (error: Throwable) {
        throw IllegalArgumentException("Invalid Solana transaction", error)
    }
}
