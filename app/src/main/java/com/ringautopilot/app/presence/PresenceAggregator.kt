package com.ringautopilot.app.presence

import com.ringautopilot.app.model.PresenceState

/** Combines independent presence detectors while conservatively avoiding false Away changes. */
object PresenceAggregator {
    fun aggregate(
        wifiEnabled: Boolean,
        wifiPresence: PresenceState,
        geofenceEnabled: Boolean,
        geofencePresence: PresenceState,
    ): PresenceState {
        val enabledStates = buildList {
            if (wifiEnabled) add(wifiPresence)
            if (geofenceEnabled) add(geofencePresence)
        }

        return when {
            enabledStates.isEmpty() -> PresenceState.NOT_CONFIGURED
            enabledStates.any { it == PresenceState.HOME } -> PresenceState.HOME
            enabledStates.all { it == PresenceState.AWAY } -> PresenceState.AWAY
            else -> PresenceState.UNKNOWN
        }
    }
}
