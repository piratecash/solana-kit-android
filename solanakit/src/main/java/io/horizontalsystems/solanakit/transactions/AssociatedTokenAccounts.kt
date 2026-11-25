package io.horizontalsystems.solanakit.transactions

import org.sol4k.PublicKey

fun associatedTokenAddress(owner: String, mint: String, programId: String): String =
    PublicKey.findProgramDerivedAddress(PublicKey(owner), PublicKey(mint), PublicKey(programId))
        .publicKey.toBase58()

/** Anyone can fund a derived address with plain SOL, so only a token-program owner proves a token account. */
fun isTokenAccount(owner: String?): Boolean = owner in WellKnownPrograms.TOKEN_PROGRAMS
