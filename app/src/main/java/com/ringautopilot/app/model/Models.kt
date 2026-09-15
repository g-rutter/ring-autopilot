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

data class AutomationSettings(
    val homeWifiSsid: String = "",
    val ringLocationId: String = "",
    val departureDelaySeconds: Long = 180,
    val arrivalDelaySeconds: Long = 30,
    val modeChangeMaxAttempts: Int = 4,
    val modeChangeInitialBackoffSeconds: Long = 5,
    val motionGroupingWindowSeconds: Long = 300,
    val repeatedEventCooldownSeconds: Long = 60,
)

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
