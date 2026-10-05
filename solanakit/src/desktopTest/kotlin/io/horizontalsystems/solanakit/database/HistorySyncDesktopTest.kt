package io.horizontalsystems.solanakit.database

import SplTokenAccountWithPublicKey
import com.solana.actions.Action
import com.solana.api.Api
import com.solana.api.Header
import com.solana.api.Message
import com.solana.api.Meta
import com.solana.api.Status
import com.solana.core.PublicKey
import com.solana.networking.Network
import com.solana.networking.NetworkingRouter
import com.solana.networking.RPCEndpoint
import com.solana.networking.RpcError
import com.solana.networking.RpcRequest
import com.solana.networking.RpcResponse
import io.horizontalsystems.solanakit.PlatformContext
import io.horizontalsystems.solanakit.SolanaKit
import io.horizontalsystems.solanakit.core.TokenAccountManager
import io.horizontalsystems.solanakit.database.main.MainDatabase
import io.horizontalsystems.solanakit.database.main.MainStorage
import io.horizontalsystems.solanakit.database.transaction.TransactionDatabase
import io.horizontalsystems.solanakit.database.transaction.TransactionStorage
import io.horizontalsystems.solanakit.models.Address
import io.horizontalsystems.solanakit.models.FullTransaction
import io.horizontalsystems.solanakit.models.Transaction
import io.horizontalsystems.solanakit.network.AlchemyEndpoint
import io.horizontalsystems.solanakit.network.ApiKeyRotation
import io.horizontalsystems.solanakit.network.BatchOutcome
import io.horizontalsystems.solanakit.network.FailoverRpcRouter
import io.horizontalsystems.solanakit.network.PublicPacer
import io.horizontalsystems.solanakit.network.RpcExecutor
import io.horizontalsystems.solanakit.network.RpcOutcome
import io.horizontalsystems.solanakit.noderpc.endpoints.RpcSignatureInformation
import io.horizontalsystems.solanakit.transactions.MintTokenAccountInfo
import io.horizontalsystems.solanakit.transactions.MintTokenAccountInfoParsedData
import io.horizontalsystems.solanakit.transactions.MintTokenAccountTokenInfo
import io.horizontalsystems.solanakit.transactions.MintTokenAccountValue
import io.horizontalsystems.solanakit.transactions.PendingTransactionSyncer
import io.horizontalsystems.solanakit.transactions.TransactionFetcher
import io.horizontalsystems.solanakit.transactions.TransactionManager
import io.horizontalsystems.solanakit.transactions.TransactionResult
import io.horizontalsystems.solanakit.transactions.TransactionSyncer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.sol4k.Base58
import java.io.File
import java.io.IOException
import java.net.URL
import kotlin.random.Random
import kotlin.time.TimeSource
import com.solana.api.Transaction as RpcTransaction

class HistorySyncDesktopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val transactions = mutableMapOf<String, TransactionResult>()
    private val node = FakeRpcRouter(transactions)
    private lateinit var kit: SyncerFixture

    @After
    fun tearDown() {
        kit.close()
    }

    @Test
    fun sync_fiveTransactionsArrivedWhileOffline_allStoredAfterReconnect() = runBlocking {
        kit = open()
        onChain("old", slot = 1)
        kit.syncer.sync()

        node.online = false
        (1..5).forEach { onChain("n$it", slot = 10L + it) }
        kit.syncer.sync()
        assertTrue(kit.syncer.syncState.value is SolanaKit.SyncState.NotSynced)

        node.online = true
        kit.syncer.sync()

        assertEquals(listOf("n5", "n4", "n3", "n2", "n1", "old"), storedHashes())
        assertTrue(kit.syncer.syncState.value is SolanaKit.SyncState.Synced)
    }

    @Test
    fun sync_processRestartMidWalk_resumesFromPersistedCursors() = runBlocking {
        kit = open()
        (1..30).forEach { onChain("h$it", slot = it.toLong()) }
        kit.syncer.sync()
        assertEquals(20, storedHashes().size)

        kit.close()
        kit = open()
        kit.syncer.sync()

        assertEquals((30 downTo 1).map { "h$it" }, storedHashes())
        assertEquals((1..30).map { "h$it" }.toSet(), node.transactionRequests.toSet())
        assertEquals(30, node.transactionRequests.size)
        assertEquals("done", kit.storage.getSyncedBlockTime("history-backward-v1")?.hash)
    }

    @Test
    fun sync_upgradeFromDatabaseWithGaps_fillsGapsWithoutDuplicates() = runBlocking {
        kit = open()
        (1..6).forEach { onChain("g$it", slot = it.toLong()) }
        // Rows of the previous syncer, which skipped g2, g4 and g6, and no cursor rows.
        kit.storage.addTransactions(listOf(1, 3, 5).map { storedRow("g$it", timestamp = it * 10L) })

        kit.syncer.sync()

        assertEquals((6 downTo 1).map { "g$it" }, storedHashes())
        assertEquals(listOf("g6", "g4", "g2"), node.transactionRequests)
    }

    @Test
    fun sync_ownSendConfirmedAsPlaceholder_remappedFromHistory() = runBlocking {
        kit = open()
        onChain("sent", slot = 7)
        kit.storage.addTransactions(
            listOf(FullTransaction(Transaction("sent", timestamp = 1, pending = false, base64Encoded = "SIGNED_TX"), emptyList()))
        )

        kit.syncer.sync()

        assertEquals(listOf("sent"), node.transactionRequests)
        assertEquals(70, kit.storage.getFullTransactions(listOf("sent")).single().transaction.timestamp)
    }

    private fun open() = SyncerFixture(tmp.root, "Solana-history", USER, node)

    private suspend fun storedHashes() = kit.storage.getTransactions(null, null, null).map { it.transaction.hash }

    private fun onChain(signature: String, slot: Long) {
        node.history += RpcSignatureInformation(signature = signature, confirmationStatus = "finalized", slot = slot, blockTime = slot * 10)
        transactions[signature] = solTransfer(signature, slot)
    }

    private fun storedRow(hash: String, timestamp: Long) =
        FullTransaction(Transaction(hash, timestamp, from = "SENDER", to = USER, pending = false), emptyList())

    private fun solTransfer(signature: String, slot: Long): TransactionResult {
        val message = Message(
            accountKeys = listOf("SENDER", USER),
            header = Header(numReadonlySignedAccounts = 0, numReadonlyUnsignedAccounts = 0, numRequiredSignatures = 1),
            instructions = emptyList(),
            recentBlockhash = "blockhash",
        )
        val meta = Meta(
            err = null,
            fee = 5_000,
            preBalances = listOf(2_000_000L, 0L),
            postBalances = listOf(995_000L, 1_000_000L),
            status = Status(null),
        )
        return TransactionResult(blockTime = slot * 10, meta = meta, slot = slot, transaction = RpcTransaction(message, listOf(signature)))
    }

    private companion object {
        val USER: String = Base58.encode(ByteArray(32) { 5 })
    }
}

/** The syncer of one kit instance over the real encrypted databases; opening it again models a process restart. */
internal class SyncerFixture(root: File, name: String, user: String, node: FakeRpcRouter) {
    private val transactionDatabase = TransactionDatabase.getInstance(PlatformContext(root), "$name-txs", DATABASE_KEY)
    private val mainDatabase = MainDatabase.getInstance(PlatformContext(root), "$name-main", DATABASE_KEY)
    val storage = TransactionStorage(transactionDatabase, user)
    val transactionManager: TransactionManager
    val syncer: TransactionSyncer

    init {
        val api = Api(node)
        val localhost = URL("http://localhost")
        val router = FailoverRpcRouter(
            publicEndpoint = RPCEndpoint.custom(localhost, localhost, Network.mainnetBeta),
            keys = ApiKeyRotation(emptyList(), Random(0), TimeSource.Monotonic),
            alchemy = AlchemyEndpoint(),
            pacer = PublicPacer(TimeSource.Monotonic),
            networkErrorListener = null,
        )
        transactionManager = TransactionManager(
            address = Address(user),
            storage = storage,
            rpcAction = Action(api, emptyList()),
            tokenAccountManager = TokenAccountManager(user, api, storage, MainStorage(mainDatabase)),
            router = router,
        )
        syncer = TransactionSyncer(
            publicKey = PublicKey(user),
            rpcClient = api,
            storage = storage,
            transactionManager = transactionManager,
            pendingTransactionSyncer = PendingTransactionSyncer(api, storage, transactionManager, router, null),
            fetcher = TransactionFetcher(node),
        )
    }

    fun close() {
        transactionDatabase.close()
        mainDatabase.close()
    }

    private companion object {
        val DATABASE_KEY = ByteArray(32) { it.toByte() }
    }
}

/**
 * A node with one wallet history (oldest first) and no token accounts. Unknown transactions are
 * answered with null, like the node does; while offline every call fails.
 */
internal class FakeRpcRouter(private val transactions: Map<String, TransactionResult>) : NetworkingRouter, RpcExecutor {
    val history = mutableListOf<RpcSignatureInformation>()
    val transactionRequests = mutableListOf<String>()
    var online = true

    override val endpoint: RPCEndpoint get() = error("not used")

    override fun isKeyedAvailable() = false

    override suspend fun <R> execute(request: RpcRequest, serializer: KSerializer<R>, publicOnly: Boolean): RpcOutcome<R> {
        val response = makeRequest(request, serializer)
        return response.error?.let { RpcOutcome.TransportFailure(IOException(it.message)) } ?: RpcOutcome.Success(response.result)
    }

    override suspend fun <R> executeBatch(requests: List<RpcRequest>, serializer: KSerializer<R>): BatchOutcome<R> =
        BatchOutcome.KeyedUnavailable

    @Suppress("UNCHECKED_CAST")
    override suspend fun <R> makeRequest(request: RpcRequest, resultSerializer: KSerializer<R>): RpcResponse<R> {
        val params = request.params?.jsonArray
        val result: Any? = when {
            !online -> return unavailable()
            request.method == "getTransaction" -> params?.get(0)?.jsonPrimitive?.content?.let {
                transactionRequests += it
                transactions[it]
            }
            request.method == "getMultipleAccounts" -> params?.get(0)?.jsonArray?.map { mintAccountValue(it.jsonPrimitive.content) }
            request.method == "getSignaturesForAddress" -> params?.get(1)?.jsonObject?.let(::listing)
            request.method == "getTokenAccountsByOwner" -> emptyList<SplTokenAccountWithPublicKey>()
            else -> return unavailable()
        }
        return RpcResponse(result = result as R?)
    }

    private fun <R> unavailable() = RpcResponse<R>(error = RpcError(code = -1, message = "unavailable"))

    // Every cursor here is a wallet signature, so `before`/`until` resolve by position in the wallet history.
    private fun listing(config: JsonObject): List<RpcSignatureInformation> {
        val newestFirst = history.asReversed()
        val start = config.positionOf("before", newestFirst)?.plus(1) ?: 0
        val end = config.positionOf("until", newestFirst) ?: newestFirst.size
        return newestFirst.subList(start, maxOf(start, end)).take(config["limit"]?.jsonPrimitive?.int ?: newestFirst.size)
    }

    private fun JsonObject.positionOf(key: String, listed: List<RpcSignatureInformation>): Int? =
        get(key)?.jsonPrimitive?.contentOrNull?.let { signature -> listed.indexOfFirst { it.signature == signature } }

    // A mint owned by another program (e.g. Token-2022) yields no MintAccount.
    private fun mintAccountValue(mint: String) = MintTokenAccountValue(
        data = MintTokenAccountInfo(
            parsed = MintTokenAccountInfoParsedData(
                info = MintTokenAccountTokenInfo(decimals = 6, isInitialized = true, mintAuthority = "AUTHORITY", supply = "1000"),
                type = "mint",
            ),
            program = "spl-token",
        ),
        owner = if (mint == TOKEN_2022_MINT) TOKEN_2022_PROGRAM else TransactionSyncer.tokenProgramId,
    )

    companion object {
        val TOKEN_2022_MINT: String = Base58.encode(ByteArray(32) { 13 })
        private const val TOKEN_2022_PROGRAM = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
    }
}
