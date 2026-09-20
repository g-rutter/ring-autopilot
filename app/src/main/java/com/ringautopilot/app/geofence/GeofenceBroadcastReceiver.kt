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
        when (val interpreted = GeofenceEventInterpreter.interpret(
            hasError = event.hasError(),
            errorCode = event.errorCode,
            transition = event.geofenceTransition,
            requestIds = event.triggeringGeofences.orEmpty().map { it.requestId },
        )) {
            is InterpretedGeofenceEvent.Transition -> {
                PreferencesGeofencePresenceStore(context).update(interpreted.state)
                Diagnostics.info(
                    "geofence_transition",
                    mapOf(
                        "transition" to interpreted.transitionName,
                        "presence" to interpreted.state,
                    ),
                )
                MonitoringWorkScheduler.scheduleGeofenceTransition(context)
            }
            is InterpretedGeofenceEvent.Ignored -> {
                Diagnostics.warn("geofence_event_ignored", mapOf("reason" to interpreted.reason))
                if (interpreted.registrationUnavailable) {
                    PreferencesGeofencePresenceStore(context).clear()
                    GeofenceWorkScheduler.scheduleRegistration(context, "service_unavailable")
                }
            }
        }
    }
}
