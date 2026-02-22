package io.horizontalsystems.solanakit.transactions

import com.solana.api.Instruction
import org.sol4k.Base58

object SolanaInstructionParser {
    fun parseInstruction(instruction: Instruction?, accountKeys: List<String>): InstructionType? {
        val programIdIndex = instruction?.programIdIndex ?: return null
        val programId = accountKeys.getOrNull(programIdIndex.toInt())
        val data = instruction.data ?: return null

        return when (programId) {
            WellKnownPrograms.SYSTEM_PROGRAM -> {
                val discriminator = Base58.decode(data).getOrNull(0)?.toInt() ?: return null
                SystemProgramInstruction.fromDiscriminator(discriminator)
            }
            WellKnownPrograms.COMPUTE_BUDGET -> {
                // Это Compute Budget Program
                val discriminator = Base58.decode(data).getOrNull(0)?.toInt()
                ComputeBudgetProgramInstruction.fromDiscriminator(discriminator)
            }
            else -> {
                null
            }
        }
    }
}