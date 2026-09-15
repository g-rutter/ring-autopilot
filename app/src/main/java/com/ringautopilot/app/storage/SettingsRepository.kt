package com.ringautopilot.app.storage

import android.content.Context
import com.ringautopilot.app.model.AutomationSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface SettingsRepository {
    val settings: StateFlow<AutomationSettings>

    fun updateHomeWifiSsid(ssid: String)
}

class PreferencesSettingsRepository(context: Context) : SettingsRepository {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(readSettings())

    override val settings: StateFlow<AutomationSettings> = mutableSettings.asStateFlow()

    override fun updateHomeWifiSsid(ssid: String) {
        val normalized = ssid.trim().removeSurrounding("\"")
        preferences.edit().putString(KEY_HOME_WIFI_SSID, normalized).apply()
        mutableSettings.value = mutableSettings.value.copy(homeWifiSsid = normalized)
    }

    private fun readSettings() = AutomationSettings(
        homeWifiSsid = preferences.getString(KEY_HOME_WIFI_SSID, "").orEmpty(),
    )

    private companion object {
        const val PREFERENCES_NAME = "ring_autopilot_settings"
        const val KEY_HOME_WIFI_SSID = "home_wifi_ssid"
    }
}
