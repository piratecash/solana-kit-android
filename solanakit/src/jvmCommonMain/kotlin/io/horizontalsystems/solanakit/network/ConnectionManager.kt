package io.horizontalsystems.solanakit.network

import io.horizontalsystems.solanakit.PlatformContext

expect class ConnectionManager(context: PlatformContext) {

    interface Listener {
        fun onConnectionChange()
    }

    var listener: Listener?
    var isConnected: Boolean

    fun recheckConnection()
    fun start()
    fun stop()
}
