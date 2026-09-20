package com.ringautopilot.app.geofence

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GeofenceRegistrationHealth { UNREGISTERED, REGISTERING, REGISTERED, UNAVAILABLE }

data class GeofenceRegistration(
    val definitionGeneration: Long = 0L,
    val registeredGeneration: Long? = null,
    val health: GeofenceRegistrationHealth = GeofenceRegistrationHealth.UNREGISTERED,
    val lastOutcome: String = "none",
    val lastReason: String = "none",
    val lastAttemptAtEpochMillis: Long? = null,
    val lastSuccessAtEpochMillis: Long? = null,
)

interface GeofenceRegistrationStore {
    val registration: StateFlow<GeofenceRegistration>
    fun update(value: GeofenceRegistration)
}

class PreferencesGeofenceRegistrationStore(context: Context) : GeofenceRegistrationStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME, Context.MODE_PRIVATE,
    )
    private val mutableRegistration = MutableStateFlow(read())
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        mutableRegistration.value = read()
    }

    init { preferences.registerOnSharedPreferenceChangeListener(listener) }
    override val registration: StateFlow<GeofenceRegistration> = mutableRegistration.asStateFlow()

    override fun update(value: GeofenceRegistration) {
        synchronized(writeLock) {
            preferences.edit()
                .putLong(KEY_DEFINITION_GENERATION, value.definitionGeneration)
                .putLong(KEY_REGISTERED_GENERATION, value.registeredGeneration ?: -1L)
                .putString(KEY_HEALTH, value.health.name)
                .putString(KEY_LAST_OUTCOME, value.lastOutcome)
                .putString(KEY_LAST_REASON, value.lastReason)
                .putLong(KEY_LAST_ATTEMPT, value.lastAttemptAtEpochMillis ?: 0L)
                .putLong(KEY_LAST_SUCCESS, value.lastSuccessAtEpochMillis ?: 0L)
                .commit()
            mutableRegistration.value = value
        }
    }

    private fun read() = GeofenceRegistration(
        definitionGeneration = preferences.getLong(KEY_DEFINITION_GENERATION, 0L),
        registeredGeneration = preferences.getLong(KEY_REGISTERED_GENERATION, -1L)
            .takeIf { it >= 0L },
        health = runCatching {
            GeofenceRegistrationHealth.valueOf(preferences.getString(KEY_HEALTH, null).orEmpty())
        }.getOrDefault(GeofenceRegistrationHealth.UNREGISTERED),
        lastOutcome = preferences.getString(KEY_LAST_OUTCOME, "none").orEmpty(),
        lastReason = preferences.getString(KEY_LAST_REASON, "none").orEmpty(),
        lastAttemptAtEpochMillis = preferences.getLong(KEY_LAST_ATTEMPT, 0L).takeIf { it > 0L },
        lastSuccessAtEpochMillis = preferences.getLong(KEY_LAST_SUCCESS, 0L).takeIf { it > 0L },
    )

    private companion object {
        const val PREFERENCES_NAME = "ring_autopilot_geofence_registration"
        const val KEY_DEFINITION_GENERATION = "definition_generation"
        const val KEY_REGISTERED_GENERATION = "registered_generation"
        const val KEY_HEALTH = "health"
        const val KEY_LAST_OUTCOME = "last_outcome"
        const val KEY_LAST_REASON = "last_reason"
        const val KEY_LAST_ATTEMPT = "last_attempt"
        const val KEY_LAST_SUCCESS = "last_success"
        val writeLock = Any()
    }
}
