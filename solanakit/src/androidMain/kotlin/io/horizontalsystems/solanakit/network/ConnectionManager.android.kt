package io.horizontalsystems.solanakit.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

actual class ConnectionManager actual constructor(context: Context) {

    actual interface Listener {
        actual fun onConnectionChange()
    }

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    actual var listener: Listener? = null
    actual var isConnected = getInitialConnectionStatus()
    private var hasValidInternet = false
    private var hasConnection = false
    private var callback = ConnectionStatusCallback()

    init {
        start()
    }

    actual fun recheckConnection() {
        isConnected = getInitialConnectionStatus()
    }

    private fun getInitialConnectionStatus(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false

        hasConnection = true
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        hasValidInternet = capabilities?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } ?: false

        return hasValidInternet
    }

    inner class ConnectionStatusCallback : ConnectivityManager.NetworkCallback() {

        private val activeNetworks: MutableList<Network> = mutableListOf()

        override fun onLost(network: Network) {
            super.onLost(network)
            activeNetworks.removeAll { activeNetwork -> activeNetwork == network }
            hasConnection = activeNetworks.isNotEmpty()
            updatedConnectionState()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            hasValidInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            updatedConnectionState()
        }

        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            if (activeNetworks.none { activeNetwork -> activeNetwork == network }) {
                activeNetworks.add(network)
            }
            hasConnection = activeNetworks.isNotEmpty()
            updatedConnectionState()
        }
    }

    private fun updatedConnectionState() {
        val oldValue = isConnected
        isConnected = hasConnection && hasValidInternet
        if (oldValue != isConnected) {
            listener?.onConnectionChange()
        }
    }

    actual fun start() {
        stop() // to avoid double registration

        try {
            connectivityManager.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
        } catch (e: Exception) {
            //already unregistered
        }
    }

    actual fun stop() {
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            //already unregistered
        }
    }
}
