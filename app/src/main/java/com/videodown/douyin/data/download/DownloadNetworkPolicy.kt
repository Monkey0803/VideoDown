package com.videodown.douyin.data.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.videodown.douyin.core.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

interface DownloadNetworkPolicy {
    val allowed: Flow<Boolean>
    fun isAllowed(): Boolean

    object Unrestricted : DownloadNetworkPolicy {
        override val allowed = flowOf(true)
        override fun isAllowed() = true
    }
}

/**
 * The key stored on a record paused by the Wi-Fi-only setting.
 *
 * A key rather than a sentence: records outlive the app language, and the UI words it through
 * `storedErrorMessage`. Kept public because the engine also compares against it.
 */
const val WIFI_PAUSE_REASON = "download:wifi_paused"

class WifiRequiredException : CancellationException(WIFI_PAUSE_REASON)

class WifiDownloadNetworkPolicy(context: Context, private val preferences: AppPreferences) : DownloadNetworkPolicy {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    // Observe the default network actually used by HTTP, not any nearby/connected Wi-Fi.
    private val wifiConnected: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null

            override fun onAvailable(network: Network) { current = network }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (network == current) trySend(capabilities.isWifiOnlyTransport())
            }

            override fun onLost(network: Network) {
                if (network == current) {
                    current = null
                    trySend(false)
                }
            }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        trySend(isWifiConnected())
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    override val allowed: Flow<Boolean> = combine(preferences.wifiOnlyChanges, wifiConnected) { onlyWifi, wifi ->
        !onlyWifi || wifi
    }.distinctUntilChanged()

    override fun isAllowed(): Boolean = !preferences.wifiOnly || isWifiConnected()

    private fun isWifiConnected(): Boolean = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        ?.isWifiOnlyTransport() == true

    // A VPN may advertise both Wi-Fi and cellular. Treat that as restricted to avoid mobile usage.
    private fun NetworkCapabilities.isWifiOnlyTransport() =
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
