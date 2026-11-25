package io.horizontalsystems.solanakit.transactions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssociatedTokenAccountsTest {

    @Test
    fun associatedTokenAddress_token2022Mint_matchesMainnetAccount() {
        assertEquals(PUMP_ACCOUNT, associatedTokenAddress(PUMP_HOLDER, PUMP_MINT, WellKnownPrograms.TOKEN_2022_PROGRAM))
    }

    @Test
    fun associatedTokenAddress_legacyMint_matchesMainnetAccount() {
        assertEquals(USDC_ACCOUNT, associatedTokenAddress(USDC_HOLDER, USDC_MINT, WellKnownPrograms.TOKEN_PROGRAM))
    }

    @Test
    fun isTokenAccount_ownerIsTokenProgram_true() {
        assertTrue(isTokenAccount(WellKnownPrograms.TOKEN_PROGRAM))
        assertTrue(isTokenAccount(WellKnownPrograms.TOKEN_2022_PROGRAM))
    }

    @Test
    fun isTokenAccount_systemOwnedOrMissing_false() {
        assertFalse(isTokenAccount(WellKnownPrograms.SYSTEM_PROGRAM))
        assertFalse(isTokenAccount(null))
    }

    private companion object {
        // Mainnet token accounts read through getTokenAccountsByOwner / getTransaction.
        const val PUMP_MINT = "pumpCmXqMfrsAkQ5r49WcJnRayYRqmXz6ae8H7H9Dfn"
        const val PUMP_HOLDER = "3Xtu99JsQ2j7r6DJW5TqMz9hDEBeDC75XdNK5mYxTUjT"
        const val PUMP_ACCOUNT = "394cYnspcPZUzoFWHe78f5D16i3RxbX3LpNkJEWh7WW9"
        const val USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        const val USDC_HOLDER = "3f5muPeU6NnJnht955wgPZWXHyLwZEwC6BQEjPoBhjgc"
        const val USDC_ACCOUNT = "GnYs2ZAjeh4ubkJtg5HzY2zruiuWcUGfX1iUUg1qsw4F"
    }
}
