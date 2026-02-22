package io.horizontalsystems.solanakit.transactions

enum class SystemProgramInstruction(val discriminator: Int): InstructionType {
    CREATE_ACCOUNT(0),
    ASSIGN(1),
    TRANSFER(2),
    CREATE_ACCOUNT_WITH_SEED(3),
    ADVANCE_NONCE_ACCOUNT(4),
    WITHDRAW_NONCE_ACCOUNT(5),
    INITIALIZE_NONCE_ACCOUNT(6),
    AUTHORIZE_NONCE_ACCOUNT(7),
    ALLOCATE(8),
    ALLOCATE_WITH_SEED(9),
    ASSIGN_WITH_SEED(10),
    TRANSFER_WITH_SEED(11),
    UPGRADE_NONCE_ACCOUNT(12);

    companion object {
        fun fromDiscriminator(value: Int) = entries.find { it.discriminator == value }
    }
}