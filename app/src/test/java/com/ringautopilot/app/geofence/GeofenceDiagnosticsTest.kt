package com.ringautopilot.app.geofence

import com.ringautopilot.app.model.PresenceState
import org.junit.Assert.assertEquals
import org.junit.Test

class GeofenceDiagnosticsTest {
    private val now = 2_000_000_000L

    @Test fun `state age buckets are stable and coarse`() {
        assertEquals("never", geofenceStateAgeBucket(GeofencePresence(), now))
        assertEquals("under_1m", geofenceStateAgeBucket(
            GeofencePresence(PresenceState.UNKNOWN, now - 59_999L), now))
        assertEquals("1m_15m", geofenceStateAgeBucket(
            GeofencePresence(PresenceState.UNKNOWN, now - 60_000L), now))
        assertEquals("15m_1h", geofenceStateAgeBucket(
            GeofencePresence(PresenceState.HOME, now - 15 * 60_000L), now))
        assertEquals("over_1h", geofenceStateAgeBucket(
            GeofencePresence(PresenceState.AWAY, now - 60 * 60_000L), now))
    }

    @Test fun `future timestamps are treated as fresh`() {
        assertEquals("under_1m", geofenceStateAgeBucket(
            GeofencePresence(PresenceState.UNKNOWN, now + 1_000L), now))
    }
}
