package com.ringautopilot.app.geofence

import android.content.Context
import android.content.SharedPreferences
import com.ringautopilot.app.model.PresenceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GeofencePresenceSource { TRANSITION, INITIAL_RECONCILIATION }

/** Serializes definition changes with event and initial-state commits in this app process. */
internal val geofenceStateLock = Any()

data class GeofencePresence(
    val state: PresenceState = PresenceState.UNKNOWN,
    val updatedAtEpochMillis: Long? = null,
    val definitionGeneration: Long = 0L,
    val source: GeofencePresenceSource? = null,
)

interface GeofencePresenceStore {
    val presence: StateFlow<GeofencePresence>

    fun update(state: PresenceState, updatedAtEpochMillis: Long = System.currentTimeMillis())
    fun clear(updatedAtEpochMillis: Long = System.currentTimeMillis())
    fun clearForGeneration(generation: Long, updatedAtEpochMillis: Long = System.currentTimeMillis()) =
        clear(updatedAtEpochMillis)
    fun updateTransition(
        state: PresenceState,
        generation: Long,
        updatedAtEpochMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        update(state, updatedAtEpochMillis)
        return true
    }
    fun updateInitialIfUnknown(
        state: PresenceState,
        generation: Long,
        startedAtEpochMillis: Long,
        updatedAtEpochMillis: Long = System.currentTimeMillis(),
    ): Boolean = false
}

class PreferencesGeofencePresenceStore(context: Context) : GeofencePresenceStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val mutablePresence = MutableStateFlow(readPresence())
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in observedKeys) mutablePresence.value = readPresence()
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    override val presence: StateFlow<GeofencePresence> = mutablePresence.asStateFlow()

    override fun update(state: PresenceState, updatedAtEpochMillis: Long) {
        updateTransition(state, presence.value.definitionGeneration, updatedAtEpochMillis)
    }

    override fun clear(updatedAtEpochMillis: Long) {
        clearForGeneration(presence.value.definitionGeneration, updatedAtEpochMillis)
    }

    override fun clearForGeneration(generation: Long, updatedAtEpochMillis: Long) {
        synchronized(geofenceStateLock) {
            write(GeofencePresence(PresenceState.UNKNOWN, updatedAtEpochMillis, generation))
        }
    }

    override fun updateTransition(
        state: PresenceState,
        generation: Long,
        updatedAtEpochMillis: Long,
    ): Boolean = synchronized(geofenceStateLock) {
        require(state == PresenceState.HOME || state == PresenceState.AWAY) {
            "Only definitive geofence states can be stored"
        }
        val current = readPresence()
        if (current.definitionGeneration > generation) return@synchronized false
        write(GeofencePresence(
            state, updatedAtEpochMillis, generation, GeofencePresenceSource.TRANSITION,
        ))
        true
    }

    override fun updateInitialIfUnknown(
        state: PresenceState,
        generation: Long,
        startedAtEpochMillis: Long,
        updatedAtEpochMillis: Long,
    ): Boolean = synchronized(geofenceStateLock) {
        require(state == PresenceState.HOME || state == PresenceState.AWAY)
        val current = readPresence()
        if (!canCommitInitialPresence(current, generation, startedAtEpochMillis)) {
            return@synchronized false
        }
        write(GeofencePresence(
            state, updatedAtEpochMillis, generation,
            GeofencePresenceSource.INITIAL_RECONCILIATION,
        ))
        true
    }

    private fun write(value: GeofencePresence) {
        preferences.edit()
            .putString(KEY_STATE, value.state.name)
            .putLong(KEY_UPDATED_AT, value.updatedAtEpochMillis ?: 0L)
            .putLong(KEY_GENERATION, value.definitionGeneration)
            .putString(KEY_SOURCE, value.source?.name)
            .commit()
        mutablePresence.value = value
    }

    private fun readPresence(): GeofencePresence {
        val state = runCatching {
            PresenceState.valueOf(preferences.getString(KEY_STATE, null).orEmpty())
        }.getOrDefault(PresenceState.UNKNOWN).takeIf {
            it == PresenceState.HOME || it == PresenceState.AWAY
        } ?: PresenceState.UNKNOWN
        val updatedAt = preferences.getLong(KEY_UPDATED_AT, 0L).takeIf { it > 0L }
        val source = runCatching {
            GeofencePresenceSource.valueOf(preferences.getString(KEY_SOURCE, null).orEmpty())
        }.getOrNull()
        return GeofencePresence(
            state, updatedAt, preferences.getLong(KEY_GENERATION, 0L), source,
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "ring_autopilot_geofence_presence"
        const val KEY_STATE = "state"
        const val KEY_UPDATED_AT = "updated_at"
        const val KEY_GENERATION = "definition_generation"
        const val KEY_SOURCE = "source"
        val observedKeys = setOf(KEY_STATE, KEY_UPDATED_AT, KEY_GENERATION, KEY_SOURCE)
    }
}

internal fun canCommitInitialPresence(
    current: GeofencePresence,
    generation: Long,
    startedAtEpochMillis: Long,
): Boolean = current.definitionGeneration == generation &&
    current.state == PresenceState.UNKNOWN &&
    !(current.source == GeofencePresenceSource.TRANSITION &&
        (current.updatedAtEpochMillis ?: 0L) >= startedAtEpochMillis)
