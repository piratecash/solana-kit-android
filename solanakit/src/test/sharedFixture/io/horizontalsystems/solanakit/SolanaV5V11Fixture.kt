package io.horizontalsystems.solanakit

import androidx.room.RoomRawQuery
import io.horizontalsystems.solanakit.database.main.MainDatabase
import io.horizontalsystems.solanakit.database.transaction.TransactionDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.math.BigDecimal

// Fixtures generated once by a temporary Robolectric test through the current DAOs
// (Room 2.7.2, MainDatabase version = 5, TransactionDatabase version = 11), then deleted;
// see solana-main-v5-room-2.7.2.db and solana-txs-v11-room-2.7.2.db.
object SolanaV5V11Fixture {

    const val MAIN_DB_RESOURCE = "fixtures/solana-main-v5-room-2.7.2.db"
    const val TXS_DB_RESOURCE = "fixtures/solana-txs-v11-room-2.7.2.db"

    const val BALANCE_LAMPORTS = 123456789L
    const val LAST_BLOCK_HEIGHT = 312345678L

    const val CONFIRMED_TRANSACTION_HASH = "confirmedTransactionHash1111111111111111111"
    const val CONFIRMED_TRANSACTION_TIMESTAMP = 1_700_000_000L
    val CONFIRMED_TRANSACTION_FEE: BigDecimal = BigDecimal("0.000005")
    const val CONFIRMED_TRANSACTION_FROM = "confirmedSourceAddress11111111111111111111"
    const val CONFIRMED_TRANSACTION_TO = "confirmedDestinationAddress1111111111111111"
    val CONFIRMED_TRANSACTION_AMOUNT: BigDecimal = BigDecimal("1.5")

    const val PENDING_EXTERNAL_TRANSACTION_HASH = "pendingExternalTransactionHash111111111111"
    const val PENDING_EXTERNAL_TRANSACTION_TIMESTAMP = 1_700_000_100L
    const val PENDING_EXTERNAL_BLOCK_HASH = "pendingExternalBlockHash1111111111111111111"
    const val PENDING_EXTERNAL_LAST_VALID_BLOCK_HEIGHT = 312345999L
    const val PENDING_EXTERNAL_BASE64_ENCODED = "AQIDBAUGBwgJCgsMDQ4PEA=="
    const val PENDING_EXTERNAL_RETRY_COUNT = 2

    const val MINT_ADDRESS = "fixtureMintAddress1111111111111111111111111"
    const val MINT_DECIMALS = 6
    const val MINT_SUPPLY = 1_000_000_000L
    const val MINT_NAME = "Fixture Token"
    const val MINT_SYMBOL = "FIX"

    const val TOKEN_ACCOUNT_ADDRESS = "fixtureTokenAccountAddress111111111111111111"

    val TOKEN_ACCOUNT_BALANCE: BigDecimal = BigDecimal("42.5")
    const val TOKEN_ACCOUNT_DECIMALS = 6

    const val TOKEN_TRANSFER_INCOMING = true
    val TOKEN_TRANSFER_AMOUNT: BigDecimal = BigDecimal("10")

    const val SYNC_SOURCE_NAME = "solana-transactions"

    fun copyMainDbTo(target: File): File = copyResourceTo(MAIN_DB_RESOURCE, target)

    fun copyTxsDbTo(target: File): File = copyResourceTo(TXS_DB_RESOURCE, target)

    private fun copyResourceTo(resource: String, target: File): File {
        target.parentFile?.mkdirs()
        val classLoader = requireNotNull(javaClass.classLoader) { "No class loader for fixture object" }
        val input = requireNotNull(classLoader.getResourceAsStream(resource)) {
            "Fixture $resource not found on classpath"
        }
        input.use { i -> target.outputStream().use { o -> i.copyTo(o) } }
        return target
    }

    suspend fun assertMainContents(database: MainDatabase) {
        val balance = checkNotNull(database.balanceDao().getBalance()) { "Fixture has no balance row" }
        assertEquals(BALANCE_LAMPORTS, balance.lamports)

        val lastBlockHeight = checkNotNull(database.lastBlockHeightDao().getLastBlockHeight()) {
            "Fixture has no last block height row"
        }
        assertEquals(LAST_BLOCK_HEIGHT, lastBlockHeight.height)

        val initialSyncRows = database.initialSyncDao().getAllEntities()
        assertEquals(1, initialSyncRows.size)
        assertTrue(initialSyncRows.single().initial)
    }

    suspend fun assertTxsContents(database: TransactionDatabase) {
        val transactionsDao = database.transactionsDao()

        val confirmedWrapper = checkNotNull(
            transactionsDao.getTransactions(
                RoomRawQuery("SELECT * FROM `Transaction` WHERE hash = '$CONFIRMED_TRANSACTION_HASH'")
            ).singleOrNull()
        ) { "Fixture has no single confirmed transaction $CONFIRMED_TRANSACTION_HASH" }
        val confirmedTransaction = confirmedWrapper.fullTransaction
        assertEquals(false, confirmedTransaction.transaction.pending)
        assertEquals(false, confirmedTransaction.transaction.external)
        assertEquals(CONFIRMED_TRANSACTION_FEE, confirmedTransaction.transaction.fee)
        assertEquals(CONFIRMED_TRANSACTION_FROM, confirmedTransaction.transaction.from)
        assertEquals(CONFIRMED_TRANSACTION_TO, confirmedTransaction.transaction.to)
        assertEquals(CONFIRMED_TRANSACTION_AMOUNT, confirmedTransaction.transaction.amount)

        val transfer = confirmedTransaction.tokenTransfers.single()
        assertEquals(MINT_ADDRESS, transfer.tokenTransfer.mintAddress)
        assertEquals(TOKEN_TRANSFER_INCOMING, transfer.tokenTransfer.incoming)
        assertEquals(TOKEN_TRANSFER_AMOUNT, transfer.tokenTransfer.amount)
        assertEquals(MINT_ADDRESS, transfer.mintAccount.address)
        assertEquals(MINT_DECIMALS, transfer.mintAccount.decimals)
        assertEquals(MINT_SUPPLY, transfer.mintAccount.supply)
        assertEquals(MINT_NAME, transfer.mintAccount.name)
        assertEquals(MINT_SYMBOL, transfer.mintAccount.symbol)

        val pending = checkNotNull(transactionsDao.get(PENDING_EXTERNAL_TRANSACTION_HASH)) {
            "Fixture has no pending transaction $PENDING_EXTERNAL_TRANSACTION_HASH"
        }
        assertTrue(pending.pending)
        assertTrue(pending.external)
        assertTrue(pending.base64Encoded.isNotEmpty())
        assertEquals(PENDING_EXTERNAL_BASE64_ENCODED, pending.base64Encoded)
        assertEquals(PENDING_EXTERNAL_LAST_VALID_BLOCK_HEIGHT, pending.lastValidBlockHeight)
        assertEquals(PENDING_EXTERNAL_RETRY_COUNT, pending.retryCount)

        val tokenAccountWrapper = checkNotNull(database.tokenAccountsDao().get(MINT_ADDRESS)) {
            "Fixture has no token account for mint $MINT_ADDRESS"
        }
        assertEquals(TOKEN_ACCOUNT_ADDRESS, tokenAccountWrapper.tokenAccount.address)
        assertEquals(TOKEN_ACCOUNT_BALANCE, tokenAccountWrapper.tokenAccount.balance)
        assertEquals(TOKEN_ACCOUNT_DECIMALS, tokenAccountWrapper.tokenAccount.decimals)
        assertEquals(MINT_ADDRESS, tokenAccountWrapper.mintAccount.address)

        val lastSynced = checkNotNull(database.transactionSyncerStateDao().get(SYNC_SOURCE_NAME)) {
            "Fixture has no sync state for $SYNC_SOURCE_NAME"
        }
        assertEquals(CONFIRMED_TRANSACTION_HASH, lastSynced.hash)
    }
}

internal fun hasPlaintextSqliteHeader(file: File): Boolean {
    if (!file.isFile || file.length() < SQLITE_HEADER.size) return false
    val header = file.inputStream().use { input -> ByteArray(SQLITE_HEADER.size).also { input.read(it) } }
    return header.contentEquals(SQLITE_HEADER)
}

private val SQLITE_HEADER = "SQLite format 3\u0000".encodeToByteArray()
