package com.ringautopilot.app.geofence

import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.ringautopilot.app.model.PresenceState

sealed interface InterpretedGeofenceEvent {
    data class Transition(
        val state: PresenceState,
        val transitionName: String,
        val generation: Long,
    ) : InterpretedGeofenceEvent

    data class Ignored(
        val reason: String,
        val registrationUnavailable: Boolean = false,
    ) : InterpretedGeofenceEvent
}

object GeofenceEventInterpreter {
    fun interpret(
        hasError: Boolean,
        errorCode: Int,
        transition: Int,
        requestIds: Collection<String>,
        currentGeneration: Long,
    ): InterpretedGeofenceEvent {
        if (hasError) {
            val unavailable = errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE
            return InterpretedGeofenceEvent.Ignored(
                reason = if (unavailable) "not_available" else "api_error",
                registrationUnavailable = unavailable,
            )
        }
        val generations = requestIds.mapNotNull(::requestGeneration)
        if (generations.isEmpty()) {
            return InterpretedGeofenceEvent.Ignored("irrelevant_request")
        }
        if (currentGeneration !in generations) {
            return InterpretedGeofenceEvent.Ignored("stale_generation")
        }
        return when (transition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> InterpretedGeofenceEvent.Transition(
                PresenceState.HOME,
                "enter",
                currentGeneration,
            )
            Geofence.GEOFENCE_TRANSITION_EXIT -> InterpretedGeofenceEvent.Transition(
                PresenceState.AWAY,
                "exit",
                currentGeneration,
            )
            else -> InterpretedGeofenceEvent.Ignored("unknown_transition")
        }
    }
}
