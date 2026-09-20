package com.ringautopilot.app.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.GeofencingEvent
import com.ringautopilot.app.automation.MonitoringWorkScheduler
import com.ringautopilot.app.logging.Diagnostics

class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_GEOFENCE_TRANSITION) {
            Diagnostics.warn("geofence_event_ignored", mapOf("reason" to "unknown_action"))
            return
        }
        val event = GeofencingEvent.fromIntent(intent)
        if (event == null) {
            Diagnostics.warn("geofence_event_ignored", mapOf("reason" to "malformed_event"))
            return
        }
        val settings = com.ringautopilot.app.storage.PreferencesSettingsRepository(context)
            .settings.value
        when (val interpreted = GeofenceEventInterpreter.interpret(
            hasError = event.hasError(),
            errorCode = event.errorCode,
            transition = event.geofenceTransition,
            requestIds = event.triggeringGeofences.orEmpty().map { it.requestId },
            currentGeneration = settings.geofenceDefinitionGeneration,
        )) {
            is InterpretedGeofenceEvent.Transition -> {
                val accepted = PreferencesGeofencePresenceStore(context).updateTransition(
                    interpreted.state, interpreted.generation,
                )
                if (!accepted) {
                    Diagnostics.warn("geofence_event_ignored", mapOf(
                        "reason" to "stale_generation",
                        "definitionGeneration" to settings.geofenceDefinitionGeneration,
                    ))
                    return
                }
                Diagnostics.info(
                    "geofence_transition",
                    mapOf(
                        "transition" to interpreted.transitionName,
                        "presence" to interpreted.state,
                        "definitionGeneration" to interpreted.generation,
                        "presenceSource" to "transition",
                    ),
                )
                MonitoringWorkScheduler.scheduleGeofenceTransition(context)
            }
            is InterpretedGeofenceEvent.Ignored -> {
                Diagnostics.warn("geofence_event_ignored", mapOf("reason" to interpreted.reason))
                if (interpreted.registrationUnavailable) {
                    PreferencesGeofencePresenceStore(context).clearForGeneration(
                        settings.geofenceDefinitionGeneration,
                    )
                    PreferencesGeofenceRegistrationStore(context).update(
                        GeofenceRegistration(
                            definitionGeneration = settings.geofenceDefinitionGeneration,
                            health = GeofenceRegistrationHealth.UNAVAILABLE,
                            lastOutcome = "unavailable",
                            lastReason = "not_available",
                            lastAttemptAtEpochMillis = System.currentTimeMillis(),
                        ),
                    )
                    GeofenceWorkScheduler.scheduleRegistration(
                        context, "service_unavailable", force = true,
                    )
                }
            }
        }
    }
}
