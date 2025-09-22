package io.horizontalsystems.solanakit.transactions

enum class ComputeBudgetProgramInstruction(val discriminator: Int): InstructionType {
    REQUEST_UNITS(0),
    SET_COMPUTE_UNIT_LIMIT(2),
    SET_COMPUTE_UNIT_PRICE(3);

    companion object {
        fun fromDiscriminator(value: Int?) = entries.find { it.discriminator == value }
    }
}