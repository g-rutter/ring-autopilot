package com.ringautopilot.app.storage

import android.content.Context
import com.ringautopilot.app.model.AutomationSettings
import com.ringautopilot.app.model.ControlMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface SettingsRepository {
    val settings: StateFlow<AutomationSettings>

    fun updateHomeWifiSsid(ssid: String)
    fun updateRingLocationId(locationId: String)
    fun updateControlMode(mode: ControlMode)
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

    override fun updateRingLocationId(locationId: String) {
        val normalized = locationId.trim()
        preferences.edit().putString(KEY_RING_LOCATION_ID, normalized).apply()
        mutableSettings.value = mutableSettings.value.copy(ringLocationId = normalized)
    }

    override fun updateControlMode(mode: ControlMode) {
        preferences.edit().putString(KEY_CONTROL_MODE, mode.name).apply()
        mutableSettings.value = mutableSettings.value.copy(controlMode = mode)
    }

    private fun readSettings() = AutomationSettings(
        homeWifiSsid = preferences.getString(KEY_HOME_WIFI_SSID, "").orEmpty(),
        ringLocationId = preferences.getString(KEY_RING_LOCATION_ID, "").orEmpty(),
        controlMode = when (preferences.getString(KEY_CONTROL_MODE, null)) {
            null, ControlMode.AUTO.name -> ControlMode.AUTO
            else -> ControlMode.MANUAL // Migrate old Away/Disarmed selections to Auto off.
        },
    )

    private companion object {
        const val PREFERENCES_NAME = "ring_autopilot_settings"
        const val KEY_HOME_WIFI_SSID = "home_wifi_ssid"
        const val KEY_RING_LOCATION_ID = "ring_location_id"
        const val KEY_CONTROL_MODE = "control_mode"
    }
}
