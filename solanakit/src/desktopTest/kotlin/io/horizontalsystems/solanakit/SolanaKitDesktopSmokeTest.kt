package io.horizontalsystems.solanakit

import io.horizontalsystems.solanakit.database.transaction.TransactionDatabase
import io.horizontalsystems.solanakit.database.transaction.TransactionStorage
import io.horizontalsystems.solanakit.models.MintAccount
import io.horizontalsystems.solanakit.models.RpcSource
import io.horizontalsystems.solanakit.models.TokenAccount
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.math.BigDecimal

class SolanaKitDesktopSmokeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val rpc = MockWebServer()

    @Before
    fun startRpc() {
        rpc.start()
    }

    @After
    fun shutdownRpc() {
        rpc.shutdown()
    }

    @Test
    fun getInstance_desktopEmptyDatabases_writesTokenAccountAndReadsEmptyTransactions() = runBlocking {
        val kit = kit()

        assertNull(kit.tokenAccount(MINT_ADDRESS))
        kit.addTokenAccount(MINT_ADDRESS, DECIMALS)

        assertEquals(MINT_ADDRESS, kit.tokenAccount(MINT_ADDRESS)?.mintAccount?.address)
        assertEquals(DECIMALS, kit.tokenAccount(MINT_ADDRESS)?.mintAccount?.decimals)
        assertEquals(emptyList<Any>(), kit.getSolTransactions())

        kit.stop()
        assertEquals(0, rpc.requestCount)
    }

    @Test
    fun getInstance_desktopStoredTokenAccount_seedsMirrorFromDatabase() = runBlocking {
        val database = TransactionDatabase.getInstance(PlatformContext(tmp.root), "Solana-$WALLET_ID-txs", DATABASE_KEY)
        try {
            TransactionStorage(database, WALLET_ADDRESS).addTokenAccountIfMissing(
                TokenAccount(TOKEN_ACCOUNT_ADDRESS, MINT_ADDRESS, BigDecimal.TEN, DECIMALS),
                MintAccount(MINT_ADDRESS, DECIMALS),
            )
        } finally {
            database.close()
        }

        val kit = kit()

        assertEquals(BigDecimal.TEN, kit.tokenAccount(MINT_ADDRESS)?.tokenAccount?.balance)
        assertEquals(1, kit.fungibleTokenAccounts().size)

        kit.stop()
    }

    private suspend fun kit(): SolanaKit = SolanaKit.getInstance(
        context = PlatformContext(tmp.root),
        addressString = WALLET_ADDRESS,
        rpcSource = RpcSource.Custom("mock", rpc.url("/").toUrl(), rpc.url("/").toUrl(), 30),
        walletId = WALLET_ID,
        databaseKey = DATABASE_KEY,
    )

    private companion object {
        val DATABASE_KEY = ByteArray(32) { it.toByte() }
        const val WALLET_ID = "desktop-smoke"
        const val WALLET_ADDRESS = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
        const val MINT_ADDRESS = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        const val TOKEN_ACCOUNT_ADDRESS = "GPLZgHf1UMUwVzgTNbLWDGvNtQEg2ZfCsxXkmRfLbH8y"
        const val DECIMALS = 6
    }
}
