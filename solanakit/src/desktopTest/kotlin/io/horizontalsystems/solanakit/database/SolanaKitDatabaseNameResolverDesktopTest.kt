package io.horizontalsystems.solanakit.database

import io.horizontalsystems.databasenaming.DatabaseNameResolver
import io.horizontalsystems.solanakit.PlatformContext
import io.horizontalsystems.solanakit.SolanaKit
import io.horizontalsystems.solanakit.models.RpcSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URL

// Android keeps the default names forever: a change here makes every existing wallet resync from scratch.
class SolanaKitDatabaseNameResolverDesktopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val key = ByteArray(32) { it.toByte() }
    private val directory: File get() = tmp.root
    private val context: PlatformContext get() = PlatformContext(directory)
    private val opaque = DatabaseNameResolver { "n${it.hashCode().toUInt()}" }

    @Test
    fun getInstance_defaultResolver_opensLiteralNames() = runBlocking<Unit> {
        kit(WALLET)

        assertEquals(setOf("Solana-w1-main", "Solana-w1-txs"), databaseFiles())
    }

    @Test
    fun getInstance_differentWallets_namesArePairwiseDistinct() = runBlocking<Unit> {
        listOf(WALLET, "w2").forEach {
            kit(it)
            kit(it, opaque)
        }

        val defaults = listOf("Solana-w1-main", "Solana-w1-txs", "Solana-w2-main", "Solana-w2-txs")
        assertEquals((defaults + defaults.map(opaque::resolve)).toSet(), databaseFiles())
    }

    @Test
    fun getInstance_overrideResolver_leavesNoDefaultNameOnDisk() = runBlocking<Unit> {
        kit(WALLET, opaque)

        assertEquals(setOf("Solana-w1-main", "Solana-w1-txs").map(opaque::resolve).toSet(), databaseFiles())
    }

    @Test
    fun migrateDatabase_overrideResolver_namespaceFilesUseResolvedName() = runBlocking<Unit> {
        SolanaKit.migrateDatabase(context, WALLET, key, opaque)

        assertEquals(listOf(".${opaque.resolve("solana-kit")}-sqlcipher.lock"), directory.list().orEmpty().toList())
    }

    @Test
    fun clear_defaultResolver_removesLiteralNames() {
        File(directory, "Solana-w1-main").writeText("x")
        File(directory, "Solana-w1-txs").writeText("x")

        SolanaKit.clear(context, WALLET)

        assertEquals(emptySet<String>(), databaseFiles())
    }

    @Test
    fun clear_overrideResolver_removesResolvedNamesAndKeepsDefaultOnes() {
        File(directory, opaque.resolve("Solana-w1-main")).writeText("x")
        File(directory, opaque.resolve("Solana-w1-txs")).writeText("x")
        File(directory, "Solana-w1-main").writeText("x")

        SolanaKit.clear(context, WALLET, opaque)

        assertEquals(setOf("Solana-w1-main"), databaseFiles())
    }

    @Test
    fun clear_resolvedNameInResolvedNamespacePrefix_throws() {
        val reserved = DatabaseNameResolver { if (it == "solana-kit") "ns" else ".ns-sqlcipher-x" }

        assertThrows(IllegalArgumentException::class.java) { SolanaKit.clear(context, WALLET, reserved) }
    }

    private suspend fun kit(
        walletId: String,
        nameResolver: DatabaseNameResolver = DatabaseNameResolver.Identity,
    ): SolanaKit = SolanaKit.getInstance(
        context = context,
        addressString = ADDRESS,
        rpcSource = RpcSource.Custom("mock", URL("http://127.0.0.1:1/"), URL("http://127.0.0.1:1/"), 30),
        walletId = walletId,
        databaseKey = key,
        nameResolver = nameResolver,
    ).also { it.getSolTransactions() }

    private fun databaseFiles(): Set<String> = directory.list().orEmpty()
        .filterNot { it.startsWith(".") || it.endsWith(".lck") || it.endsWith("-wal") || it.endsWith("-shm") }.toSet()

    private companion object {
        const val WALLET = "w1"
        const val ADDRESS = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
    }
}
