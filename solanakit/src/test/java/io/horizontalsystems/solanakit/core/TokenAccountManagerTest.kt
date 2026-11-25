package io.horizontalsystems.solanakit.core

import ParsedSplTokenAccountWithPublicKey
import com.solana.api.Api
import com.solana.api.TokenAmountInfoResponse
import getParsedTokenAccountsByOwner
import getTokenAccountBalanceWithRepeat
import io.horizontalsystems.solanakit.SolanaKit
import io.horizontalsystems.solanakit.database.main.MainStorage
import io.horizontalsystems.solanakit.database.transaction.TransactionStorage
import io.horizontalsystems.solanakit.models.MintAccount
import io.horizontalsystems.solanakit.models.TokenAccount
import io.horizontalsystems.solanakit.transactions.WellKnownPrograms
import io.horizontalsystems.solanakit.transactions.associatedTokenAddress
import io.horizontalsystems.solanakit.transactions.getAccountOwners
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.sol4k.Base58
import java.math.BigDecimal

class TokenAccountManagerTest {

    private val rpcClient = mockk<Api>()
    private val storage = mockk<TransactionStorage>(relaxed = true)
    private val mainStorage = mockk<MainStorage>(relaxed = true)
    private val owners = mutableMapOf<String, String?>()
    private val ownerRequests = mutableListOf<List<String>>()
    private val savedAccounts = mutableListOf<List<TokenAccount>>()

    private val manager = TokenAccountManager(
        walletAddress = WALLET,
        rpcClient = rpcClient,
        storage = storage,
        mainStorage = mainStorage
    )

    @Before
    fun setUp() {
        mockkStatic("GetTokenAccountsByOwnerKt")
        mockkStatic("GetTokenAccountBalanceKt")
        mockkStatic("io.horizontalsystems.solanakit.transactions.ExtensionsKt")
        coEvery { mainStorage.isInitialSync() } returns false
        coEvery { rpcClient.getAccountOwners(any()) } answers {
            val addresses = secondArg<List<String>>()
            ownerRequests += addresses
            Result.success(addresses.associateWith { owners[it] })
        }
        coEvery { rpcClient.getTokenAccountBalanceWithRepeat(any()) } returns Result.success(
            mockk<TokenAmountInfoResponse> { every { amount } returns SYNCED_BALANCE }
        )
        coEvery { storage.saveTokenAccounts(any()) } answers { savedAccounts += firstArg<List<TokenAccount>>() }
    }

    @After
    fun tearDown() {
        unmockkStatic("GetTokenAccountsByOwnerKt")
        unmockkStatic("GetTokenAccountBalanceKt")
        unmockkStatic("io.horizontalsystems.solanakit.transactions.ExtensionsKt")
    }

    @Test
    fun sync_initialDiscoveryReturnsEmpty_savesInitialSync() = runBlocking {
        coEvery { mainStorage.isInitialSync() } returns true
        coEvery { storage.getTokenAccounts() } returns emptyList()
        coEvery { rpcClient.getParsedTokenAccountsByOwner(any(), any()) } returns Result.success(emptyList())

        manager.sync()

        coVerify(exactly = 1) { mainStorage.saveInitialSync() }
        assertTrue(manager.syncState is SolanaKit.SyncState.Synced)
    }

    @Test
    fun sync_initialDiscovery_storesAccountsOfBothPrograms() = runBlocking {
        coEvery { mainStorage.isInitialSync() } returns true
        givenDiscovered(WellKnownPrograms.TOKEN_PROGRAM, LEGACY_MINT_ATA, LEGACY_MINT)
        givenDiscovered(WellKnownPrograms.TOKEN_2022_PROGRAM, TOKEN_2022_ATA, TOKEN_2022_MINT)

        manager.sync()

        coVerify {
            storage.saveTokenAccounts(
                match { accounts -> accounts.map { it.address } == listOf(LEGACY_MINT_ATA, TOKEN_2022_ATA) },
                match<List<MintAccount>> { mints -> mints.map { it.address } == listOf(LEGACY_MINT, TOKEN_2022_MINT) },
            )
        }
        coVerify(exactly = 1) { mainStorage.saveInitialSync() }
    }

    @Test
    fun sync_initialDiscoveryOfToken2022Fails_retriesDiscoveryNextSync() = runBlocking {
        coEvery { mainStorage.isInitialSync() } returns true
        givenDiscovered(WellKnownPrograms.TOKEN_PROGRAM, LEGACY_MINT_ATA, LEGACY_MINT)
        coEvery {
            rpcClient.getParsedTokenAccountsByOwner(any(), WellKnownPrograms.TOKEN_2022_PROGRAM)
        } returns Result.failure(IllegalStateException("unavailable"))

        manager.sync()

        coVerify(exactly = 0) { storage.saveTokenAccounts(any(), any()) }
        coVerify(exactly = 0) { mainStorage.saveInitialSync() }
    }

    @Test
    fun sync_legacyAtaStoredForToken2022Mint_movesRowToToken2022Ata() = runBlocking {
        givenStored(TokenAccount(PLACEHOLDER, TOKEN_2022_MINT, BigDecimal.ZERO, DECIMALS))
        owners[TOKEN_2022_ATA] = WellKnownPrograms.TOKEN_2022_PROGRAM

        manager.sync()

        coVerify { storage.replaceTokenAccount(PLACEHOLDER, match { it.address == TOKEN_2022_ATA }) }
        assertEquals(listOf(TOKEN_2022_ATA to BigDecimal(SYNCED_BALANCE)), lastSaved())
    }

    @Test
    fun sync_placeholderFundedWithSol_movesRowToToken2022Ata() = runBlocking {
        givenStored(TokenAccount(PLACEHOLDER, TOKEN_2022_MINT, BigDecimal.ZERO, DECIMALS))
        owners[PLACEHOLDER] = WellKnownPrograms.SYSTEM_PROGRAM
        owners[TOKEN_2022_ATA] = WellKnownPrograms.TOKEN_2022_PROGRAM

        manager.sync()

        coVerify { storage.replaceTokenAccount(PLACEHOLDER, match { it.address == TOKEN_2022_ATA }) }
        assertEquals(listOf(TOKEN_2022_ATA to BigDecimal(SYNCED_BALANCE)), lastSaved())
    }

    @Test
    fun sync_placeholderAndDiscoveredToken2022Account_keepsOneRow() = runBlocking {
        givenStored(
            TokenAccount(PLACEHOLDER, TOKEN_2022_MINT, BigDecimal.ZERO, DECIMALS),
            TokenAccount(TOKEN_2022_ATA, TOKEN_2022_MINT, BigDecimal.ONE, DECIMALS),
        )
        owners[TOKEN_2022_ATA] = WellKnownPrograms.TOKEN_2022_PROGRAM

        manager.sync()

        coVerify { storage.replaceTokenAccount(PLACEHOLDER, match { it.address == TOKEN_2022_ATA }) }
        assertEquals(listOf(listOf(PLACEHOLDER, TOKEN_2022_ATA)), ownerRequests)
        assertEquals(listOf(TOKEN_2022_ATA to BigDecimal(SYNCED_BALANCE)), lastSaved())
    }

    @Test
    fun sync_unfundedLegacyToken_keepsRowWithoutBalanceRequest() = runBlocking {
        givenStored(TokenAccount(LEGACY_MINT_ATA, LEGACY_MINT, BigDecimal.ZERO, DECIMALS))

        manager.sync()

        assertEquals(1, ownerRequests.size)
        coVerify(exactly = 0) { storage.replaceTokenAccount(any(), any()) }
        coVerify(exactly = 0) { rpcClient.getTokenAccountBalanceWithRepeat(any()) }
        assertTrue(lastSaved().isEmpty())
    }

    @Test
    fun sync_fundedLegacyToken_requestsOnlyStoredAddress() = runBlocking {
        givenStored(TokenAccount(LEGACY_MINT_ATA, LEGACY_MINT, BigDecimal.ONE, DECIMALS))
        owners[LEGACY_MINT_ATA] = WellKnownPrograms.TOKEN_PROGRAM

        manager.sync()

        assertEquals(listOf(listOf(LEGACY_MINT_ATA)), ownerRequests)
        assertEquals(listOf(LEGACY_MINT_ATA to BigDecimal(SYNCED_BALANCE)), lastSaved())
    }

    private fun givenStored(vararg accounts: TokenAccount) {
        coEvery { storage.getTokenAccounts() } returns accounts.toList()
    }

    private fun givenDiscovered(programId: String, address: String, mint: String) {
        val tokenAccount = TokenAccount(address, mint, BigDecimal.ONE, DECIMALS)
        val parsed = mockk<ParsedSplTokenAccountWithPublicKey> {
            every { toTokenAccount() } returns tokenAccount
            every { toMintAccount() } returns MintAccount(mint, DECIMALS)
        }
        coEvery { rpcClient.getParsedTokenAccountsByOwner(any(), programId) } returns Result.success(listOf(parsed))
    }

    private fun lastSaved() = savedAccounts.last().map { it.address to it.balance }

    private companion object {
        const val WALLET = "5sRHUTn6ShZBpDyHMxfgCHvMVHLoeZWoeaYsqMVV3ssc"
        const val SYNCED_BALANCE = "42"
        const val DECIMALS = 6
        val LEGACY_MINT: String = Base58.encode(ByteArray(32) { 7 })
        val TOKEN_2022_MINT: String = Base58.encode(ByteArray(32) { 8 })
        val LEGACY_MINT_ATA = associatedTokenAddress(WALLET, LEGACY_MINT, WellKnownPrograms.TOKEN_PROGRAM)
        val PLACEHOLDER = associatedTokenAddress(WALLET, TOKEN_2022_MINT, WellKnownPrograms.TOKEN_PROGRAM)
        val TOKEN_2022_ATA = associatedTokenAddress(WALLET, TOKEN_2022_MINT, WellKnownPrograms.TOKEN_2022_PROGRAM)
    }
}
