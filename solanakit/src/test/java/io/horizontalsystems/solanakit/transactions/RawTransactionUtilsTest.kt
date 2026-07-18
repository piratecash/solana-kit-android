package io.horizontalsystems.solanakit.transactions

import com.solana.core.HotAccount
import com.solana.core.Transaction
import com.solana.programs.SystemProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.sol4k.Base58
import org.sol4k.Keypair
import org.sol4k.TransactionMessage
import org.sol4k.VersionedTransaction
import org.sol4k.instruction.TransferInstruction

class RawTransactionUtilsTest {

    @Test
    fun rawTransactionSignature_legacyTransaction_returnsFirstSignature() {
        val account = HotAccount()
        val recipient = HotAccount()
        val transaction = Transaction().apply {
            add(SystemProgram.transfer(account.publicKey, recipient.publicKey, 1_000L))
            setRecentBlockHash(HotAccount().publicKey.toBase58())
            sign(listOf(account))
        }

        val expectedSignature = Base58.encode(requireNotNull(transaction.signature))

        assertEquals(expectedSignature, rawTransactionSignature(transaction.serialize()))
    }

    @Test
    fun rawTransactionSignature_versionedTransaction_returnsFirstSignature() {
        val keypair = Keypair.generate()
        val recipient = Keypair.generate()
        val recentBlockhash = Keypair.generate().publicKey.toBase58()
        val message = TransactionMessage.newMessage(
            feePayer = keypair.publicKey,
            recentBlockhash = recentBlockhash,
            instruction = TransferInstruction(keypair.publicKey, recipient.publicKey, 1_000L),
        )
        val transaction = VersionedTransaction(message)
        transaction.sign(keypair)
        val expectedSignature = Base58.encode(keypair.sign(message.serialize()))

        assertEquals(expectedSignature, rawTransactionSignature(transaction.serialize()))
    }

    @Test
    fun rawTransactionSignature_emptyTransaction_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            rawTransactionSignature(ByteArray(0))
        }
    }

    @Test
    fun rawTransactionSignature_defaultSignature_throws() {
        val rawTransaction = byteArrayOf(1) + ByteArray(64) + ByteArray(10)

        assertThrows(IllegalArgumentException::class.java) {
            rawTransactionSignature(rawTransaction)
        }
    }
}
