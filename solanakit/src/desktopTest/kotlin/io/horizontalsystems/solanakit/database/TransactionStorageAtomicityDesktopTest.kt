package io.horizontalsystems.solanakit.database

import io.horizontalsystems.solanakit.PlatformContext
import io.horizontalsystems.solanakit.database.transaction.TransactionDatabase
import io.horizontalsystems.solanakit.database.transaction.TransactionStorage
import io.horizontalsystems.solanakit.models.FullTokenTransfer
import io.horizontalsystems.solanakit.models.FullTransaction
import io.horizontalsystems.solanakit.models.MintAccount
import io.horizontalsystems.solanakit.models.TokenAccount
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

    @Test
    fun addTransactions_syncedVersionOfLocalSend_replacesTokenTransfer() = runBlocking {
        val mintAccount = MintAccount(MINT_ADDRESS, 6)
        storage.addTransactions(
            listOf(
                FullTransaction(
                    transaction(FIRST_HASH),
                    listOf(FullTokenTransfer(TokenTransfer(FIRST_HASH, MINT_ADDRESS, false, BigDecimal(-2)), mintAccount)),
                )
            )
        )

        storage.addTransactions(
            listOf(
                FullTransaction(
                    transaction(FIRST_HASH).copy(pending = false),
                    listOf(FullTokenTransfer(TokenTransfer(FIRST_HASH, MINT_ADDRESS, false, BigDecimal(2)), mintAccount)),
                )
            )
        )

        val tokenTransfer = storage.getFullTransactions(listOf(FIRST_HASH)).single().tokenTransfers.single().tokenTransfer
        assertEquals(false, tokenTransfer.incoming)
        assertEquals(0, BigDecimal(2).compareTo(tokenTransfer.amount))
    }

    @Test
    fun replaceTokenAccount_staleRow_mintResolvesToActualAccount() = runBlocking {
        storage.addTokenAccountIfMissing(tokenAccount(STALE_ADDRESS, BigDecimal.ZERO), MintAccount(MINT_ADDRESS, 6))

        storage.replaceTokenAccount(STALE_ADDRESS, tokenAccount(ACTUAL_ADDRESS, BigDecimal.ZERO))

        assertEquals(listOf(ACTUAL_ADDRESS), storage.getTokenAccounts().map { it.address })
        assertEquals(ACTUAL_ADDRESS, storage.getFullTokenAccount(MINT_ADDRESS)?.tokenAccount?.address)
    }

    @Test
    fun replaceTokenAccount_actualRowAlreadyStored_keepsItsBalance() = runBlocking {
        storage.saveTokenAccounts(
            listOf(tokenAccount(STALE_ADDRESS, BigDecimal.ZERO), tokenAccount(ACTUAL_ADDRESS, BigDecimal.TEN)),
            listOf(MintAccount(MINT_ADDRESS, 6)),
        )

        storage.replaceTokenAccount(STALE_ADDRESS, tokenAccount(ACTUAL_ADDRESS, BigDecimal.ZERO))

        assertEquals(listOf(ACTUAL_ADDRESS to 0), storage.getTokenAccounts().map { it.address to BigDecimal.TEN.compareTo(it.balance) })
    }

    private fun tokenAccount(address: String, balance: BigDecimal) = TokenAccount(address, MINT_ADDRESS, balance, decimals = 6)

    private fun transaction(hash: String) = Transaction(hash = hash, timestamp = 1_700_000_000)

    private companion object {
        val DATABASE_KEY = ByteArray(32) { it.toByte() }
        const val DB_NAME = "Solana-atomicity-txs"
        const val WALLET_ADDRESS = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
        const val MINT_ADDRESS = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        const val FIRST_HASH = "atomicityFirstTransactionHash11111111111111"
        const val SECOND_HASH = "atomicitySecondTransactionHash1111111111111"
        const val MISSING_HASH = "atomicityMissingTransactionHash111111111111"
        const val STALE_ADDRESS = "staleLegacyDerivedTokenAccount1111111111111"
        const val ACTUAL_ADDRESS = "actualToken2022TokenAccount111111111111111"
    }
}
