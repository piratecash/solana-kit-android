package io.horizontalsystems.solanakit.database

import io.horizontalsystems.solanakit.PlatformContext
import io.horizontalsystems.solanakit.database.transaction.TransactionDatabase
import io.horizontalsystems.solanakit.database.transaction.TransactionStorage
import io.horizontalsystems.solanakit.models.FullTokenTransfer
import io.horizontalsystems.solanakit.models.FullTransaction
import io.horizontalsystems.solanakit.models.MintAccount
import io.horizontalsystems.solanakit.models.TokenTransfer
import io.horizontalsystems.solanakit.models.Transaction
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.math.BigDecimal

class TransactionStorageAtomicityDesktopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var database: TransactionDatabase
    private lateinit var storage: TransactionStorage

    @Before
    fun openDatabase() {
        database = TransactionDatabase.getInstance(PlatformContext(tmp.root), DB_NAME, DATABASE_KEY)
        storage = TransactionStorage(database, WALLET_ADDRESS)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun addTransactions_transferReferencesMissingTransaction_commitsNoTransaction() {
        val orphanTransfer = FullTokenTransfer(
            TokenTransfer(MISSING_HASH, MINT_ADDRESS, true, BigDecimal.ONE),
            MintAccount(MINT_ADDRESS, 6),
        )

        assertThrows(Exception::class.java) {
            runBlocking {
                storage.addTransactions(
                    listOf(
                        FullTransaction(transaction(FIRST_HASH), listOf()),
                        FullTransaction(transaction(SECOND_HASH), listOf(orphanTransfer)),
                    )
                )
            }
        }

        runBlocking {
            assertEquals(emptyList<Transaction>(), database.transactionsDao().pendingTransactions())
            assertEquals(null, database.transactionsDao().get(FIRST_HASH))
            assertEquals(null, database.transactionsDao().get(SECOND_HASH))
        }
    }

    private fun transaction(hash: String) = Transaction(hash = hash, timestamp = 1_700_000_000)

    private companion object {
        val DATABASE_KEY = ByteArray(32) { it.toByte() }
        const val DB_NAME = "Solana-atomicity-txs"
        const val WALLET_ADDRESS = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
        const val MINT_ADDRESS = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        const val FIRST_HASH = "atomicityFirstTransactionHash11111111111111"
        const val SECOND_HASH = "atomicitySecondTransactionHash1111111111111"
        const val MISSING_HASH = "atomicityMissingTransactionHash111111111111"
    }
}
