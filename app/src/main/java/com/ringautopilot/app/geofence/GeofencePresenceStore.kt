package com.ringautopilot.app.geofence

import android.content.Context
import android.content.SharedPreferences
import com.ringautopilot.app.model.PresenceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GeofencePresence(
    val state: PresenceState = PresenceState.UNKNOWN,
    val updatedAtEpochMillis: Long? = null,
)

interface GeofencePresenceStore {
    val presence: StateFlow<GeofencePresence>

    fun update(state: PresenceState, updatedAtEpochMillis: Long = System.currentTimeMillis())
    fun clear(updatedAtEpochMillis: Long = System.currentTimeMillis())
}

class PreferencesGeofencePresenceStore(context: Context) : GeofencePresenceStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val mutablePresence = MutableStateFlow(readPresence())
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_STATE || key == KEY_UPDATED_AT) mutablePresence.value = readPresence()
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    override val presence: StateFlow<GeofencePresence> = mutablePresence.asStateFlow()

    override fun update(state: PresenceState, updatedAtEpochMillis: Long) {
        require(state == PresenceState.HOME || state == PresenceState.AWAY) {
            "Only definitive geofence transitions can be stored"
        }
        write(GeofencePresence(state, updatedAtEpochMillis))
    }

    override fun clear(updatedAtEpochMillis: Long) {
        write(GeofencePresence(PresenceState.UNKNOWN, updatedAtEpochMillis))
    }

    private fun write(value: GeofencePresence) {
        preferences.edit()
            .putString(KEY_STATE, value.state.name)
            .putLong(KEY_UPDATED_AT, value.updatedAtEpochMillis ?: 0L)
            .apply()
        mutablePresence.value = value
    }

    private fun readPresence(): GeofencePresence {
        val state = runCatching {
            PresenceState.valueOf(preferences.getString(KEY_STATE, null).orEmpty())
        }.getOrDefault(PresenceState.UNKNOWN).takeIf {
            it == PresenceState.HOME || it == PresenceState.AWAY
        } ?: PresenceState.UNKNOWN
        val updatedAt = preferences.getLong(KEY_UPDATED_AT, 0L).takeIf { it > 0L }
        return GeofencePresence(state, updatedAt)
    }

    private companion object {
        const val PREFERENCES_NAME = "ring_autopilot_geofence_presence"
        const val KEY_STATE = "state"
        const val KEY_UPDATED_AT = "updated_at"
    }
}
