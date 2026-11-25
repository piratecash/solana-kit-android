package io.horizontalsystems.solanakit.transactions

import com.solana.api.Instruction
import io.horizontalsystems.solanakit.transactions.SolanaUserTransferResolver.Transfer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.sol4k.Base58
import java.math.BigDecimal

class SolanaUserTransferResolverTest {

    /**
     * Dust-spam pattern: one large transfer between strangers plus many 1-lamport
     * transfers, one of which lands on the user. The user's share must be attributed
     * to the user, not collapsed into the first (stranger-to-stranger) transfer.
     */
    @Test
    fun resolve_userIsDustRecipientAmongManyTransfers_attributesIncomingTransferToUser() {
        // FUND sprays 0.44 SOL to BIG and 1 lamport each to USER and R3.
        val accountKeys = listOf("FUND", "BIG", "USER", "R3")
        val preBalances = listOf(1_000_000_000L, 500L, 4_463_687L, 200L)
        val postBalances = listOf(559_994_998L, 440_000_500L, 4_463_688L, 201L)
        val transfers = listOf(
            Transfer(fromIndex = 0, toIndex = 1, lamports = BigDecimal(440_000_000)), // FUND -> BIG
            Transfer(fromIndex = 0, toIndex = 2, lamports = BigDecimal(1)),            // FUND -> USER dust
            Transfer(fromIndex = 0, toIndex = 3, lamports = BigDecimal(1)),            // FUND -> R3 dust
        )

        val result = SolanaUserTransferResolver.resolve(
            userAddress = "USER",
            accountKeys = accountKeys,
            preBalances = preBalances,
            postBalances = postBalances,
            transfers = transfers,
        )

        assertEquals("FUND", result.from)
        assertEquals("USER", result.to)
        assertEquals(0, BigDecimal(1).compareTo(result.amount))
    }

    @Test
    fun resolve_simpleIncomingTransfer_amountExcludesNetworkFee() {
        // SENDER pays the 5000-lamport fee and transfers 10_000 lamports to USER.
        val accountKeys = listOf("SENDER", "USER")
        val preBalances = listOf(1_000_000_000L, 2_000_000L)
        val postBalances = listOf(989_995_000L, 2_010_000L)
        val transfers = listOf(Transfer(fromIndex = 0, toIndex = 1, lamports = BigDecimal(10_000)))

        val result = SolanaUserTransferResolver.resolve(
            userAddress = "USER",
            accountKeys = accountKeys,
            preBalances = preBalances,
            postBalances = postBalances,
            transfers = transfers,
        )

        assertEquals("SENDER", result.from)
        assertEquals("USER", result.to)
        // Recipient gets exactly the transferred amount; the fee is paid by the sender.
        assertEquals(0, BigDecimal(10_000).compareTo(result.amount))
    }

    @Test
    fun resolve_simpleOutgoingTransferUserPaysFee_amountExcludesNetworkFee() {
        // USER is the fee payer and sends 10_000 lamports to DEST.
        val accountKeys = listOf("USER", "DEST")
        val preBalances = listOf(1_000_000_000L, 500L)
        val postBalances = listOf(989_995_000L, 10_500L)
        val transfers = listOf(Transfer(fromIndex = 0, toIndex = 1, lamports = BigDecimal(10_000)))

        val result = SolanaUserTransferResolver.resolve(
            userAddress = "USER",
            accountKeys = accountKeys,
            preBalances = preBalances,
            postBalances = postBalances,
            transfers = transfers,
        )

        assertEquals("USER", result.from)
        assertEquals("DEST", result.to)
        // Amount is the exact transfer value from the instruction; the network fee is excluded
        // so a confirmed outgoing transfer matches the amount stored for its pending counterpart.
        assertEquals(0, BigDecimal(10_000).compareTo(result.amount))
    }

    @Test
    fun resolve_userSendsAndReceivesDustInSameTx_attributesOutgoingTransfer() {
        // USER (fee payer) sends 1_000_000 lamports to DEST and receives a 1-lamport dust
        // from SPAMMER in the same transaction. The net balance change is negative, so the
        // transaction must be classified as outgoing, not as an incoming dust.
        val accountKeys = listOf("USER", "DEST", "SPAMMER")
        val preBalances = listOf(1_000_000_000L, 500L, 700L)
        val postBalances = listOf(998_995_001L, 1_000_500L, 699L)
        val transfers = listOf(
            Transfer(fromIndex = 0, toIndex = 1, lamports = BigDecimal(1_000_000)), // USER -> DEST
            Transfer(fromIndex = 2, toIndex = 0, lamports = BigDecimal(1)),          // SPAMMER -> USER dust
        )

        val result = SolanaUserTransferResolver.resolve(
            userAddress = "USER",
            accountKeys = accountKeys,
            preBalances = preBalances,
            postBalances = postBalances,
            transfers = transfers,
        )

        assertEquals("USER", result.from)
        assertEquals("DEST", result.to)
        assertEquals(0, BigDecimal(1_000_000).compareTo(result.amount))
    }

    @Test
    fun resolve_userIsSenderButNotFeePayer_attributesExactOutgoingAmount() {
        // FEEPAYER pays the network fee; USER sends 50_000 lamports to DEST. The user is not
        // the first account key, so the amount cannot be derived from the fee payer's delta.
        val accountKeys = listOf("FEEPAYER", "USER", "DEST")
        val preBalances = listOf(1_000_000_000L, 2_000_000L, 100L)
        val postBalances = listOf(999_995_000L, 1_950_000L, 50_100L)
        val transfers = listOf(Transfer(fromIndex = 1, toIndex = 2, lamports = BigDecimal(50_000)))

        val result = SolanaUserTransferResolver.resolve(
            userAddress = "USER",
            accountKeys = accountKeys,
            preBalances = preBalances,
            postBalances = postBalances,
            transfers = transfers,
        )

        assertEquals("USER", result.from)
        assertEquals("DEST", result.to)
        assertEquals(0, BigDecimal(50_000).compareTo(result.amount))
    }

    @Test
    fun resolve_userNotInAccountKeys_fallsBackToLegacyFirstTransfer() {
        val accountKeys = listOf("A", "B")
        val preBalances = listOf(1_000_000_000L, 500L)
        val postBalances = listOf(999_994_000L, 1_500L)
        val transfers = listOf(Transfer(fromIndex = 0, toIndex = 1, lamports = BigDecimal(1_000)))

        val result = SolanaUserTransferResolver.resolve(
            userAddress = "USER",
            accountKeys = accountKeys,
            preBalances = preBalances,
            postBalances = postBalances,
            transfers = transfers,
        )

        assertEquals("A", result.from)
        assertEquals("B", result.to)
        // No user balance to read; falls back to the fee-payer delta (legacy behaviour).
        assertEquals(0, BigDecimal(6_000).compareTo(result.amount))
    }

    @Test
    fun parseTransfers_multipleSystemTransfers_extractsIndicesAndLamports() {
        val accountKeys = listOf("FUND", "BIG", "USER", WellKnownPrograms.SYSTEM_PROGRAM)
        val systemProgramIndex = 3L
        val instructions = listOf(
            Instruction(accounts = listOf(0L, 1L), data = systemTransferData(440_000_000L), programIdIndex = systemProgramIndex),
            Instruction(accounts = listOf(0L, 2L), data = systemTransferData(1L), programIdIndex = systemProgramIndex),
        )

        val transfers = SolanaUserTransferResolver.parseTransfers(instructions, accountKeys)

        assertEquals(2, transfers.size)
        assertEquals(0, transfers[0].fromIndex)
        assertEquals(1, transfers[0].toIndex)
        assertEquals(0, BigDecimal(440_000_000).compareTo(transfers[0].lamports))
        assertEquals(0, transfers[1].fromIndex)
        assertEquals(2, transfers[1].toIndex)
        assertEquals(0, BigDecimal(1).compareTo(transfers[1].lamports))
    }

    @Test
    fun parseTransfers_lamportsExceedingUInt32_decodesFullU64() {
        // 5 SOL = 5_000_000_000 lamports overflows a 32-bit unsigned int, so the high bytes of
        // the little-endian u64 must be decoded to get the correct amount.
        val accountKeys = listOf("SENDER", "USER", WellKnownPrograms.SYSTEM_PROGRAM)
        val fiveSol = 5_000_000_000L
        val instructions = listOf(
            Instruction(accounts = listOf(0L, 1L), data = systemTransferData(fiveSol), programIdIndex = 2L),
        )

        val transfers = SolanaUserTransferResolver.parseTransfers(instructions, accountKeys)

        assertEquals(1, transfers.size)
        assertEquals(0, BigDecimal(fiveSol).compareTo(transfers[0].lamports))
    }

    @Test
    fun parseTransfers_nonTransferSystemInstruction_isIgnored() {
        val accountKeys = listOf("FUND", "DEST", WellKnownPrograms.SYSTEM_PROGRAM)
        val systemProgramIndex = 2L
        val createAccountData = Base58.encode(
            byteArrayOf(SystemProgramInstruction.CREATE_ACCOUNT.discriminator.toByte(), 0, 0, 0)
        )
        val instructions = listOf(
            Instruction(accounts = listOf(0L, 1L), data = createAccountData, programIdIndex = systemProgramIndex),
            Instruction(accounts = listOf(0L, 1L), data = systemTransferData(123L), programIdIndex = systemProgramIndex),
        )

        val transfers = SolanaUserTransferResolver.parseTransfers(instructions, accountKeys)

        assertEquals(1, transfers.size)
        assertEquals(0, BigDecimal(123).compareTo(transfers[0].lamports))
    }

    /**
     * End-to-end check mirroring the real dust-spam transaction: raw instructions are parsed
     * into transfers and then resolved from the owner's point of view.
     */
    @Test
    fun parseTransfersThenResolve_dustSpamTransaction_attributesUserDustAsIncoming() {
        val accountKeys = listOf("FUND", "BIG", "USER", "R3", WellKnownPrograms.SYSTEM_PROGRAM)
        val systemProgramIndex = 4L
        val preBalances = listOf(1_000_000_000L, 500L, 4_463_687L, 200L, 1L)
        val postBalances = listOf(559_994_998L, 440_000_500L, 4_463_688L, 201L, 1L)
        val instructions = listOf(
            Instruction(accounts = listOf(0L, 1L), data = systemTransferData(440_000_000L), programIdIndex = systemProgramIndex),
            Instruction(accounts = listOf(0L, 2L), data = systemTransferData(1L), programIdIndex = systemProgramIndex),
            Instruction(accounts = listOf(0L, 3L), data = systemTransferData(1L), programIdIndex = systemProgramIndex),
        )

        val transfers = SolanaUserTransferResolver.parseTransfers(instructions, accountKeys)
        val result = SolanaUserTransferResolver.resolve(
            userAddress = "USER",
            accountKeys = accountKeys,
            preBalances = preBalances,
            postBalances = postBalances,
            transfers = transfers,
        )

        assertEquals("FUND", result.from)
        assertEquals("USER", result.to)
        assertEquals(0, BigDecimal(1).compareTo(result.amount))
    }
}
