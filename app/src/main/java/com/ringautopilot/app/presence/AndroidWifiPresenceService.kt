package com.ringautopilot.app.presence

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.ringautopilot.app.logging.Diagnostics
import com.ringautopilot.app.model.PresenceState
import com.ringautopilot.app.storage.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidWifiPresenceService(
    context: Context,
    private val settingsRepository: SettingsRepository,
) : PresenceService {
    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private val mutablePresence = MutableStateFlow(PresenceState.UNKNOWN)
    private var started = false
    private var lastReason: String? = null

    override val presence: StateFlow<PresenceState> = mutablePresence.asStateFlow()

    @Volatile
    private var callbackWifiSsid: String? = null

    private val networkCallback = createNetworkCallback()

    override fun start() {
        if (started) return
        started = true
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            evaluate()
        } catch (error: SecurityException) {
            Diagnostics.warn("presence_callback_failed", mapOf("reason" to "permission_denied"), error)
            publish(PresenceState.UNKNOWN, "permission_denied")
        }
    }

    override fun stop() {
        if (!started) return
        started = false
        try { connectivityManager.unregisterNetworkCallback(networkCallback) }
        catch (error: SecurityException) { Diagnostics.debug("presence_callback_stop", mapOf("reason" to "permission_denied"), error) }
        catch (error: IllegalArgumentException) { Diagnostics.debug("presence_callback_stop", mapOf("reason" to "not_registered"), error) }
    }

    override fun refresh() = evaluate()

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    override fun currentWifiSsid(): String? = try {
        val activeNetwork = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            ?.takeIf { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
            ?: return null
        ssidFrom(capabilities.transportInfo as? WifiInfo)
            // Some Android 12+ devices return a redacted WifiInfo through
            // ConnectivityManager even when precise location is allowed. Validate the
            // SSID before falling back instead of only falling back when WifiInfo is null.
            ?: ssidFrom(wifiManager.connectionInfo)
            ?: callbackWifiSsid
    } catch (error: SecurityException) {
        Diagnostics.warn("wifi_read_failed", mapOf("reason" to "permission_denied"), error)
        null
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun evaluate() {
        val homeSsid = settingsRepository.settings.value.homeWifiSsid
        if (homeSsid.isBlank()) {
            publish(PresenceState.NOT_CONFIGURED, "not_configured")
            return
        }

        try {
            val activeNetwork = connectivityManager.activeNetwork
            val capabilities = activeNetwork?.let(connectivityManager::getNetworkCapabilities)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
                publish(PresenceState.AWAY, if (activeNetwork == null) "no_active_network" else "non_wifi")
                return
            }

            val connectedSsid = ssidFrom(capabilities.transportInfo as? WifiInfo)
                ?: ssidFrom(wifiManager.connectionInfo)
                ?: callbackWifiSsid

            when {
                connectedSsid == null -> publish(PresenceState.UNKNOWN, "ssid_unavailable")
                connectedSsid == homeSsid -> publish(PresenceState.HOME, "match")
                else -> publish(PresenceState.AWAY, "mismatch")
            }
        } catch (error: SecurityException) {
            Diagnostics.warn("presence_read_failed", mapOf("reason" to "permission_denied"), error)
            publish(PresenceState.UNKNOWN, "permission_denied")
        }
    }

    private fun publish(state: PresenceState, reason: String) {
        if (mutablePresence.value != state || lastReason != reason) {
            Diagnostics.info("presence_changed", mapOf("presence" to state, "reason" to reason))
            lastReason = reason
        }
        mutablePresence.value = state
    }

    private fun createNetworkCallback(): ConnectivityManager.NetworkCallback {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return object : ConnectivityManager.NetworkCallback(
                ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO,
            ) {
                override fun onAvailable(network: Network) {
                    callbackWifiSsid = null
                    evaluate()
                }

                override fun onLost(network: Network) = handleNetworkLost()

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) = handleCapabilitiesChanged(capabilities)
            }
        }

        return object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                callbackWifiSsid = null
                evaluate()
            }

            override fun onLost(network: Network) = handleNetworkLost()

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) = handleCapabilitiesChanged(capabilities)
        }
    }

    private fun handleNetworkLost() {
        callbackWifiSsid = null
        evaluate()
    }

    private fun handleCapabilitiesChanged(capabilities: NetworkCapabilities) {
        callbackWifiSsid = if (
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        ) {
            ssidFrom(capabilities.transportInfo as? WifiInfo)
        } else {
            null
        }
        evaluate()
    }

    private fun ssidFrom(wifiInfo: WifiInfo?): String? = wifiInfo?.ssid
        ?.removeSurrounding("\"")
        ?.trim()
        ?.takeUnless {
            it.isBlank() ||
                it == WifiManager.UNKNOWN_SSID ||
                it.equals("<unknown ssid>", ignoreCase = true)
        }
}
