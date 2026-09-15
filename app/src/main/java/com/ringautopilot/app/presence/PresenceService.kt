package com.ringautopilot.app.presence

import com.ringautopilot.app.model.PresenceState
import kotlinx.coroutines.flow.StateFlow

interface PresenceService {
    val presence: StateFlow<PresenceState>

    fun start()
    fun stop()
    fun refresh()

    /** Returns the currently connected Wi-Fi SSID, or null when unavailable/permissionless. */
    fun currentWifiSsid(): String?
}
