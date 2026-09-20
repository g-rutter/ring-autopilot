package com.ringautopilot.app.model

enum class PresenceState {
    NOT_CONFIGURED,
    UNKNOWN,
    HOME,
    AWAY,
}

enum class RingMode {
    UNKNOWN,
    UNAVAILABLE,
    AWAY,
    DISARMED,
}

/** Whether presence automation is enabled; Ring's current mode is tracked separately. */
enum class ControlMode {
    AUTO,
    MANUAL,
}

data class AutomationSettings(
    val homeWifiSsid: String = "",
    val wifiPresenceEnabled: Boolean = false,
    val geofencePresenceEnabled: Boolean = false,
    val homeLatitude: Double? = null,
    val homeLongitude: Double? = null,
    val homeGeofenceRadiusMeters: Float = DEFAULT_GEOFENCE_RADIUS_METERS,
    val ringLocationId: String = "",
    val controlMode: ControlMode = ControlMode.AUTO,
    val departureDelaySeconds: Long = 30,
    val arrivalDelaySeconds: Long = 1,
    val modeChangeMaxAttempts: Int = 4,
    val modeChangeInitialBackoffSeconds: Long = 5,
    val motionGroupingWindowSeconds: Long = 300,
    val repeatedEventCooldownSeconds: Long = 60,
) {
    val hasValidGeofence: Boolean
        get() = homeLatitude != null && homeLatitude in -90.0..90.0 &&
            homeLongitude != null && homeLongitude in -180.0..180.0 &&
            isValidGeofenceRadius(homeGeofenceRadiusMeters)

    val hasConfiguredPresence: Boolean
        get() = (wifiPresenceEnabled && homeWifiSsid.isNotBlank()) ||
            (geofencePresenceEnabled && hasValidGeofence)

    companion object {
        const val DEFAULT_GEOFENCE_RADIUS_METERS = 100f
        const val MIN_GEOFENCE_RADIUS_METERS = 50f
        const val MAX_GEOFENCE_RADIUS_METERS = 500f
        const val GEOFENCE_RADIUS_STEP_METERS = 50f

        fun isValidGeofenceRadius(radiusMeters: Float): Boolean =
            radiusMeters in MIN_GEOFENCE_RADIUS_METERS..MAX_GEOFENCE_RADIUS_METERS &&
                radiusMeters % GEOFENCE_RADIUS_STEP_METERS == 0f
    }
}

enum class RingEventType {
    MOTION,
    PERSON,
    DOORBELL,
}

data class RingEvent(
    val cameraId: String,
    val cameraName: String,
    val type: RingEventType,
    val occurredAtEpochMillis: Long,
)

data class EventSummary(
    val cameraName: String,
    val type: RingEventType,
    val count: Int,
    val windowStartEpochMillis: Long,
    val windowEndEpochMillis: Long,
)
