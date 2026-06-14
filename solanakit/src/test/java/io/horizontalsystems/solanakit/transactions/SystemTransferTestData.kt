package io.horizontalsystems.solanakit.transactions

import org.sol4k.Base58

/**
 * Builds the 12-byte `system::transfer` instruction data: a 4-byte little-endian discriminator
 * followed by the 8-byte little-endian lamport amount, Base58-encoded as the RPC node returns it.
 */
internal fun systemTransferData(lamports: Long): String {
    val data = ByteArray(12)
    data[0] = SystemProgramInstruction.TRANSFER.discriminator.toByte()
    var value = lamports
    for (i in 0 until 8) {
        data[4 + i] = (value and 0xFF).toByte()
        value = value ushr 8
    }
    return Base58.encode(data)
}
