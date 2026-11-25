package io.horizontalsystems.solanakit.sample

import android.app.Application
import io.horizontalsystems.hdwalletkit.Mnemonic
import io.horizontalsystems.solanakit.Signer
import io.horizontalsystems.solanakit.SolanaKit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

class App : Application() {

    private val kitMutex = Mutex()
    private var kit: SolanaKit? = null

    lateinit var signer: Signer
        private set

    /** The kit factory suspends, so the first view model that needs it awaits the creation. */
    suspend fun awaitSolanaKit(): SolanaKit = kitMutex.withLock {
        kit ?: createKit().also { kit = it }
    }

    private suspend fun createKit(): SolanaKit {
        val words = Configuration.defaultsWords
        val seed = Mnemonic().toSeed(words, "")
        val address = Signer.address(seed)

        signer = Signer.getInstance(seed)

        // Demo only: a real wallet stores a random key in secure storage.
        val databaseKey = MessageDigest.getInstance("SHA-256").digest(seed)
        SolanaKit.migrateDatabase(this, Configuration.walletId, databaseKey)

        return SolanaKit.getInstance(
            context = this,
            addressString = address,
            rpcSource = Configuration.rpcSource,
            walletId = Configuration.walletId,
            databaseKey = databaseKey
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: App
            private set
    }

}
