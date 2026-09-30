package io.horizontalsystems.solanakit

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SolanaV5V11FixtureDesktopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun getInstance_room272Fixtures_keepsEveryRowAndSchemaVersion() = runBlocking {
        val mainFile = SolanaV5V11Fixture.copyMainDbTo(File(tmp.root, MAIN_DB_NAME))
        val txsFile = SolanaV5V11Fixture.copyTxsDbTo(File(tmp.root, TXS_DB_NAME))

        val main = openPlaintextMainDatabase(mainFile)
        val txs = openPlaintextTransactionDatabase(txsFile)
        try {
            SolanaV5V11Fixture.assertMainContents(main)
            SolanaV5V11Fixture.assertTxsContents(txs)
        } finally {
            main.close()
            txs.close()
        }

        // Unchanged versions prove neither a migration nor a destructive fallback ran.
        assertEquals(5, userVersion(mainFile))
        assertEquals(11, userVersion(txsFile))
    }

    private companion object {
        const val MAIN_DB_NAME = "Solana-fixture-main"
        const val TXS_DB_NAME = "Solana-fixture-txs"
    }
}
