package io.horizontalsystems.solanakit.transactions

import com.solana.core.HotAccount
import com.solana.core.PublicKey
import org.junit.Assert.assertEquals
import org.junit.Test
import org.sol4k.Base58

class SplTransferTransactionTest {

    // Expected layout from the SPL Token program: TransferChecked = instruction 12,
    // accounts [source (w), mint, destination (w), owner (signer)], data = 12 || u64 amount LE || u8 decimals.
    @Test
    fun splTransferTransaction_newRecipient_createsAccountThenTransfersChecked() {
        val message = decodeLegacyMessage(serialize(createDestination = true))

        assertEquals(listOf(ASSOCIATED_TOKEN_PROGRAM, TOKEN_PROGRAM), message.instructions.map { it.program })
        val transfer = message.instructions.last()
        assertEquals(
            listOf(
                Meta(SOURCE, signer = false, writable = true),
                Meta(MINT, signer = false, writable = false),
                Meta(DESTINATION, signer = false, writable = true),
                // Writable at message level because the owner also pays the fee.
                Meta(owner.publicKey.toBase58(), signer = true, writable = true),
            ),
            transfer.accounts.map(message::meta),
        )
        assertEquals("0c87d612000000000006", transfer.data.toHexString())
    }

    @Test
    fun splTransferTransaction_existingRecipient_onlyTransfersChecked() {
        val message = decodeLegacyMessage(serialize(createDestination = false))

        assertEquals(listOf(TOKEN_PROGRAM), message.instructions.map { it.program })
        assertEquals("0c87d612000000000006", message.instructions.single().data.toHexString())
    }

    private val owner = HotAccount()

    private fun serialize(createDestination: Boolean): ByteArray = splTransferTransaction(
        mintAddress = PublicKey(MINT),
        decimals = 6,
        source = PublicKey(SOURCE),
        destination = PublicKey(DESTINATION),
        destinationOwner = PublicKey(DESTINATION_OWNER),
        createDestination = createDestination,
        amount = 1_234_567,
        owner = owner.publicKey,
        instructions = emptyList(),
    ).apply {
        setRecentBlockHash(BLOCK_HASH)
        sign(listOf(owner))
    }.serialize()

    private data class Meta(val key: String, val signer: Boolean, val writable: Boolean)

    private class Instruction(val program: String, val accounts: List<Int>, val data: ByteArray)

    private class Message(
        val requiredSignatures: Int,
        val readonlySigned: Int,
        val readonlyUnsigned: Int,
        val keys: List<String>,
        val instructions: List<Instruction>,
    ) {
        fun meta(index: Int): Meta {
            val signer = index < requiredSignatures
            val writable = if (signer) index < requiredSignatures - readonlySigned else index < keys.size - readonlyUnsigned
            return Meta(keys[index], signer, writable)
        }
    }

    // Legacy wire format: shortvec signatures, header (3 bytes), shortvec keys, blockhash, shortvec instructions.
    private fun decodeLegacyMessage(raw: ByteArray): Message {
        var offset = 0
        fun byte() = raw[offset++].toInt() and 0xFF
        fun shortVec(): Int {
            var value = 0
            var shift = 0
            while (true) {
                val next = byte()
                value = value or ((next and 0x7F) shl shift)
                if (next and 0x80 == 0) return value
                shift += 7
            }
        }
        fun bytes(size: Int) = raw.copyOfRange(offset, offset + size).also { offset += size }

        bytes(shortVec() * 64)
        val header = List(3) { byte() }
        val keys = List(shortVec()) { Base58.encode(bytes(32)) }
        bytes(32)
        val instructions = List(shortVec()) {
            val program = keys[byte()]
            val accounts = List(shortVec()) { byte() }
            Instruction(program, accounts, bytes(shortVec()))
        }
        check(offset == raw.size) { "Trailing bytes after the message" }
        return Message(header[0], header[1], header[2], keys, instructions)
    }

    private companion object {
        const val TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
        const val ASSOCIATED_TOKEN_PROGRAM = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
        const val MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        val SOURCE: String = Base58.encode(ByteArray(32) { 1 })
        val DESTINATION: String = Base58.encode(ByteArray(32) { 2 })
        val DESTINATION_OWNER: String = Base58.encode(ByteArray(32) { 3 })
        val BLOCK_HASH: String = Base58.encode(ByteArray(32) { 4 })
    }
}
