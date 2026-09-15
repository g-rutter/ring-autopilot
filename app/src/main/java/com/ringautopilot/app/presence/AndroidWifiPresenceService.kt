package com.ringautopilot.app.presence

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
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

    override val presence: StateFlow<PresenceState> = mutablePresence.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = evaluate()
        override fun onLost(network: Network) = evaluate()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            evaluate()
    }

    override fun start() {
        if (started) return
        started = true
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            evaluate()
        } catch (_: SecurityException) {
            mutablePresence.value = PresenceState.UNKNOWN
        }
    }

    override fun stop() {
        if (!started) return
        started = false
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
    }

    override fun refresh() = evaluate()

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    override fun currentWifiSsid(): String? = try {
        val activeNetwork = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            ?.takeIf { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
            ?: return null
        val wifiInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            capabilities.transportInfo as? WifiInfo
        } else {
            wifiManager.connectionInfo
        }
        wifiInfo?.ssid
            ?.removeSurrounding("\"")
            ?.takeUnless { it.isBlank() || it == WifiManager.UNKNOWN_SSID }
    } catch (_: SecurityException) {
        null
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun evaluate() {
        val homeSsid = settingsRepository.settings.value.homeWifiSsid
        if (homeSsid.isBlank()) {
            mutablePresence.value = PresenceState.NOT_CONFIGURED
            return
        }

        try {
            val activeNetwork = connectivityManager.activeNetwork
            val capabilities = activeNetwork?.let(connectivityManager::getNetworkCapabilities)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
                mutablePresence.value = PresenceState.AWAY
                return
            }

            val wifiInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                capabilities.transportInfo as? WifiInfo
            } else {
                wifiManager.connectionInfo
            }
            val connectedSsid = wifiInfo?.ssid
                ?.removeSurrounding("\"")
                ?.takeUnless { it == WifiManager.UNKNOWN_SSID }

            mutablePresence.value = when {
                connectedSsid == null -> PresenceState.UNKNOWN
                connectedSsid == homeSsid -> PresenceState.HOME
                else -> PresenceState.AWAY
            }
        } catch (_: SecurityException) {
            mutablePresence.value = PresenceState.UNKNOWN
        }
    }
}
