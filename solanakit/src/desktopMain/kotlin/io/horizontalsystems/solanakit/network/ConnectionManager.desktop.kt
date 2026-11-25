package io.horizontalsystems.solanakit.network

import io.horizontalsystems.solanakit.PlatformContext

// Desktop has no connectivity service to subscribe to, so the kit always syncs;
// the listener is never called because the state never changes.
actual class ConnectionManager actual constructor(context: PlatformContext) {

    actual interface Listener {
        actual fun onConnectionChange()
    }

    actual var listener: Listener? = null
    actual var isConnected: Boolean = true

    actual fun recheckConnection() = Unit

    actual fun start() = Unit

    actual fun stop() = Unit
}
