package io.horizontalsystems.solanakit.models

import com.solana.core.PublicKey
import org.sol4k.Base58

data class Address(val publicKey: PublicKey) {

    constructor(pubkeyString: String) : this(PublicKey(pubkeyString))

    override fun toString() = Base58.encode(publicKey.pubkey)

}
