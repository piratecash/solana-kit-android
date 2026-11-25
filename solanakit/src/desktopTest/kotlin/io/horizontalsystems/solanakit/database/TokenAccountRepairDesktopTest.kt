package io.horizontalsystems.solanakit.database

import io.horizontalsystems.solanakit.transactions.WellKnownPrograms
import io.horizontalsystems.solanakit.transactions.associatedTokenAddress
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.sol4k.Base58

class TokenAccountRepairDesktopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val node = FakeRpcRouter(emptyMap())
    private lateinit var kit: SyncerFixture

    @Before
    fun open() {
        kit = SyncerFixture(tmp.root, "Solana-token-accounts", USER, node)
    }

    @After
    fun tearDown() {
        kit.close()
    }

    @Test
    fun sync_token2022MintEnabledOffline_balanceFlowEmitsRepairedAccount() = runBlocking {
        val manager = kit.tokenAccountManager
        manager.addTokenAccount(USER, MINT, decimals = 6)
        node.owners[TOKEN_2022_ACCOUNT] = WellKnownPrograms.TOKEN_2022_PROGRAM
        node.balances[TOKEN_2022_ACCOUNT] = "5000000"
        val emitted = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { manager.tokenBalanceFlow(MINT).first() } }

        manager.sync()

        val account = emitted.await().tokenAccount
        assertEquals(TOKEN_2022_ACCOUNT to 5_000_000L, account.address to account.balance.toLong())
        assertEquals(listOf(TOKEN_2022_ACCOUNT), kit.storage.getTokenAccounts().map { it.address })
        assertEquals(TOKEN_2022_ACCOUNT, kit.storage.getFullTokenAccount(MINT)?.tokenAccount?.address)
    }

    private companion object {
        val USER: String = Base58.encode(ByteArray(32) { 5 })
        val MINT = FakeRpcRouter.TOKEN_2022_MINT
        val TOKEN_2022_ACCOUNT = associatedTokenAddress(USER, MINT, WellKnownPrograms.TOKEN_2022_PROGRAM)
    }
}
