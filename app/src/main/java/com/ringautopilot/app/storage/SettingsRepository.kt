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
    fun updateWifiPresenceEnabled(enabled: Boolean)
    fun updateGeofencePresenceEnabled(enabled: Boolean)
    fun updateHomeGeofence(latitude: Double, longitude: Double, radiusMeters: Float)
    fun updateRingLocationId(locationId: String)
    fun updateControlMode(mode: ControlMode)
}

class PreferencesSettingsRepository(context: Context) : SettingsRepository {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(readSettings())

    init {
        if (!preferences.contains(KEY_WIFI_PRESENCE_ENABLED)) {
            preferences.edit()
                .putBoolean(KEY_WIFI_PRESENCE_ENABLED, mutableSettings.value.wifiPresenceEnabled)
                .apply()
        }
    }

    override val settings: StateFlow<AutomationSettings> = mutableSettings.asStateFlow()

    override fun updateHomeWifiSsid(ssid: String) {
        val normalized = ssid.trim().removeSurrounding("\"")
        preferences.edit().putString(KEY_HOME_WIFI_SSID, normalized).apply()
        mutableSettings.value = mutableSettings.value.copy(homeWifiSsid = normalized)
    }

    override fun updateWifiPresenceEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_WIFI_PRESENCE_ENABLED, enabled).apply()
        mutableSettings.value = mutableSettings.value.copy(wifiPresenceEnabled = enabled)
    }

    override fun updateGeofencePresenceEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_GEOFENCE_PRESENCE_ENABLED, enabled).apply()
        mutableSettings.value = mutableSettings.value.copy(geofencePresenceEnabled = enabled)
    }

    override fun updateHomeGeofence(
        latitude: Double,
        longitude: Double,
        radiusMeters: Float,
    ) {
        require(latitude in -90.0..90.0) { "Latitude is out of range" }
        require(longitude in -180.0..180.0) { "Longitude is out of range" }
        require(radiusMeters in AutomationSettings.MIN_GEOFENCE_RADIUS_METERS..
            AutomationSettings.MAX_GEOFENCE_RADIUS_METERS) { "Geofence radius is out of range" }
        preferences.edit()
            .putLong(KEY_HOME_LATITUDE, latitude.toBits())
            .putLong(KEY_HOME_LONGITUDE, longitude.toBits())
            .putFloat(KEY_HOME_GEOFENCE_RADIUS_METERS, radiusMeters)
            .apply()
        mutableSettings.value = mutableSettings.value.copy(
            homeLatitude = latitude,
            homeLongitude = longitude,
            homeGeofenceRadiusMeters = radiusMeters,
        )
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

    private fun readSettings(): AutomationSettings {
        val homeWifiSsid = preferences.getString(KEY_HOME_WIFI_SSID, "").orEmpty()
        return AutomationSettings(
            homeWifiSsid = homeWifiSsid,
            wifiPresenceEnabled = migratedWifiPresenceEnabled(
                hasPersistedValue = preferences.contains(KEY_WIFI_PRESENCE_ENABLED),
                persistedValue = preferences.getBoolean(KEY_WIFI_PRESENCE_ENABLED, false),
                homeWifiSsid = homeWifiSsid,
            ),
            geofencePresenceEnabled = preferences.getBoolean(KEY_GEOFENCE_PRESENCE_ENABLED, false),
            homeLatitude = preferences.readDoubleOrNull(KEY_HOME_LATITUDE),
            homeLongitude = preferences.readDoubleOrNull(KEY_HOME_LONGITUDE),
            homeGeofenceRadiusMeters = preferences.getFloat(
                KEY_HOME_GEOFENCE_RADIUS_METERS,
                AutomationSettings.DEFAULT_GEOFENCE_RADIUS_METERS,
            ),
            ringLocationId = preferences.getString(KEY_RING_LOCATION_ID, "").orEmpty(),
            controlMode = when (preferences.getString(KEY_CONTROL_MODE, null)) {
                null, ControlMode.AUTO.name -> ControlMode.AUTO
                else -> ControlMode.MANUAL // Migrate old Away/Disarmed selections to Auto off.
            },
        )
    }

    private fun android.content.SharedPreferences.readDoubleOrNull(key: String): Double? =
        if (contains(key)) Double.fromBits(getLong(key, 0L)) else null

    private companion object {
        const val PREFERENCES_NAME = "ring_autopilot_settings"
        const val KEY_HOME_WIFI_SSID = "home_wifi_ssid"
        const val KEY_WIFI_PRESENCE_ENABLED = "wifi_presence_enabled"
        const val KEY_GEOFENCE_PRESENCE_ENABLED = "geofence_presence_enabled"
        const val KEY_HOME_LATITUDE = "home_latitude"
        const val KEY_HOME_LONGITUDE = "home_longitude"
        const val KEY_HOME_GEOFENCE_RADIUS_METERS = "home_geofence_radius_meters"
        const val KEY_RING_LOCATION_ID = "ring_location_id"
        const val KEY_CONTROL_MODE = "control_mode"
    }
}

internal fun migratedWifiPresenceEnabled(
    hasPersistedValue: Boolean,
    persistedValue: Boolean,
    homeWifiSsid: String,
): Boolean = if (hasPersistedValue) persistedValue else homeWifiSsid.isNotBlank()
