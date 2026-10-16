package io.horizontalsystems.solanakit.network

import java.net.URL

class AlchemyEndpoint(private val baseUrl: String = "https://solana-mainnet.g.alchemy.com/v2/") {
    fun url(key: String): URL = URL(baseUrl + key)
}
