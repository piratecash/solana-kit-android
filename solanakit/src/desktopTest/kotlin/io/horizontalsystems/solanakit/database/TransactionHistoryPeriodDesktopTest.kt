package io.horizontalsystems.solanakit.database

import io.horizontalsystems.solanakit.database.transaction.TransactionStorage
import io.horizontalsystems.solanakit.models.FullTokenTransfer
import io.horizontalsystems.solanakit.models.FullTransaction
import io.horizontalsystems.solanakit.models.MintAccount
import io.horizontalsystems.solanakit.models.TokenTransfer
import io.horizontalsystems.solanakit.models.Transaction
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.math.BigDecimal

class TransactionHistoryPeriodDesktopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var kit: SyncerFixture
    private lateinit var storage: TransactionStorage

    @Before
    fun setUp() {
        kit = SyncerFixture(tmp.root, "Solana-period", USER, FakeRpcRouter(emptyMap()))
        storage = kit.storage
    }

    @After
    fun tearDown() {
        kit.close()
    }

    @Test
    fun getTransactions_sinceAndUntil_sinceInclusiveUntilExclusive() = runBlocking {
        storeSol("h100", 100)
        storeSol("h200", 200)
        storeSol("h300", 300)

        val hashes = storage.getTransactions(null, null, null, sinceTimestamp = 200, untilTimestamp = 300).hashes()

        assertEquals(listOf("h200"), hashes)
    }

    @Test
    fun getTransactions_onlySince_returnsNewerOrEqual() = runBlocking {
        storeSol("h100", 100)
        storeSol("h200", 200)
        storeSol("h300", 300)

        assertEquals(listOf("h300", "h200"), storage.getTransactions(null, null, null, sinceTimestamp = 200).hashes())
    }

    @Test
    fun getTransactions_onlyUntil_returnsOlderOnly() = runBlocking {
        storeSol("h100", 100)
        storeSol("h200", 200)
        storeSol("h300", 300)

        assertEquals(listOf("h200", "h100"), storage.getTransactions(null, null, null, untilTimestamp = 300).hashes())
    }

    @Test
    fun getTransactions_onlyUntil_excludesRowsWithoutBlockTime() = runBlocking {
        storeSol("noBlockTime", 0)
        storeSol("h200", 200)

        assertEquals(listOf("h200"), storage.getTransactions(null, null, null, untilTimestamp = 300).hashes())
    }

    @Test
    fun getTransactions_periodWithoutMatches_returnsEmpty() = runBlocking {
        storeSol("h100", 100)

        assertEquals(emptyList<String>(), storage.getTransactions(null, null, null, sinceTimestamp = 500, untilTimestamp = 600).hashes())
    }

    @Test
    fun getTransactions_cursorAndPeriod_bothApply() = runBlocking {
        storeSol("1h100", 100)
        storeSol("1h200", 200)
        storeSol("1cursor", 300)
        storeSol("1h400", 400)

        val hashes = storage.getTransactions(null, "1cursor", null, sinceTimestamp = 200, untilTimestamp = 400).hashes()

        assertEquals(listOf("1h200"), hashes)
    }

    @Test
    fun getSolTransactions_period_appliesBounds() = runBlocking {
        storeSol("1h100", 100)
        storeSol("1h200", 200)
        storeSol("1cursor", 300)

        assertEquals(listOf("1h200"), storage.getSolTransactions(null, null, null, 150, 250).hashes())
    }

    @Test
    fun getSplTransactions_period_appliesBounds() = runBlocking {
        storeSpl("s100", 100, MINT)
        storeSpl("s200", 200, MINT)
        storeSpl("s300", 300, MINT)

        assertEquals(listOf("s200"), storage.getSplTransactions(MINT, null, null, null, 150, 250).hashes())
    }

    @Test
    fun oldestTimestamp_noFilter_returnsMinimumIgnoringExternalAndZero() = runBlocking {
        storeSol("noBlockTime", 0)
        storeSol("external", 50, external = true)
        storeSol("h200", 200)
        storeSpl("s300", 300, MINT)

        assertEquals(200L, storage.oldestTimestamp(null))
    }

    @Test
    fun oldestSolTimestamp_incomingFilter_ignoresOtherDirectionAndSpl() = runBlocking {
        storeSol("out100", 100, from = USER, to = "OTHER")
        storeSol("in200", 200, from = "OTHER", to = USER)
        storeSpl("s50", 50, MINT)

        assertEquals(200L, storage.oldestSolTimestamp(true))
        assertEquals(100L, storage.oldestSolTimestamp(false))
        assertEquals(100L, storage.oldestSolTimestamp(null))
    }

    @Test
    fun oldestSplTimestamp_mintFilter_ignoresOtherMints() = runBlocking {
        storeSpl("s100", 100, OTHER_MINT)
        storeSpl("s300", 300, MINT)
        storeSol("h50", 50)

        assertEquals(300L, storage.oldestSplTimestamp(MINT, null))
        assertEquals(300L, storage.oldestSplTimestamp(MINT, false))
        assertNull(storage.oldestSplTimestamp(MINT, true))
    }

    @Test
    fun oldestTimestamp_emptyStorage_returnsNull() = runBlocking {
        assertNull(storage.oldestTimestamp(null))
        assertNull(storage.oldestSolTimestamp(null))
        assertNull(storage.oldestSplTimestamp(MINT, null))
    }

    private fun List<FullTransaction>.hashes() = map { it.transaction.hash }

    private suspend fun storeSol(
        hash: String,
        timestamp: Long,
        external: Boolean = false,
        from: String = "OTHER",
        to: String = USER,
    ) {
        val transaction = Transaction(hash, timestamp, from = from, to = to, amount = BigDecimal.ONE, pending = false, external = external)
        storage.addTransactions(listOf(FullTransaction(transaction, emptyList())))
    }

    private suspend fun storeSpl(hash: String, timestamp: Long, mint: String) {
        val transaction = Transaction(hash, timestamp, pending = false)
        val transfer = TokenTransfer(hash, mint, incoming = false, amount = BigDecimal.ONE)
        storage.addTransactions(listOf(FullTransaction(transaction, listOf(FullTokenTransfer(transfer, MintAccount(mint, 6))))))
    }

    private companion object {
        const val USER = "UserAddress"
        const val MINT = "MintA"
        const val OTHER_MINT = "MintB"
    }
}
