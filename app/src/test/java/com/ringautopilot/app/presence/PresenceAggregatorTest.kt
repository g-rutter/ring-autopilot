package com.ringautopilot.app.presence

import com.ringautopilot.app.model.PresenceState
import org.junit.Assert.assertEquals
import org.junit.Test

class PresenceAggregatorTest {
    private val states = PresenceState.entries

    @Test
    fun `neither detector enabled is not configured for every input`() {
        for (wifi in states) for (geofence in states) {
            assertCombined(PresenceState.NOT_CONFIGURED, false, wifi, false, geofence)
        }
    }

    @Test
    fun `a single enabled detector passes through known states and maps uncertainty`() {
        for (enabledState in states) for (disabledState in states) {
            val expected = when (enabledState) {
                PresenceState.HOME -> PresenceState.HOME
                PresenceState.AWAY -> PresenceState.AWAY
                PresenceState.UNKNOWN, PresenceState.NOT_CONFIGURED -> PresenceState.UNKNOWN
            }
            assertCombined(expected, true, enabledState, false, disabledState)
            assertCombined(expected, false, disabledState, true, enabledState)
        }
    }

    @Test
    fun `home from either enabled detector always wins`() {
        for (other in states) {
            assertCombined(PresenceState.HOME, true, PresenceState.HOME, true, other)
            assertCombined(PresenceState.HOME, true, other, true, PresenceState.HOME)
        }
    }

    @Test
    fun `both enabled detectors must be away to report away`() {
        for (wifi in states) for (geofence in states) {
            val expected = when {
                wifi == PresenceState.HOME || geofence == PresenceState.HOME -> PresenceState.HOME
                wifi == PresenceState.AWAY && geofence == PresenceState.AWAY -> PresenceState.AWAY
                else -> PresenceState.UNKNOWN
            }
            assertCombined(expected, true, wifi, true, geofence)
        }
    }

    private fun assertCombined(
        expected: PresenceState,
        wifiEnabled: Boolean,
        wifi: PresenceState,
        geofenceEnabled: Boolean,
        geofence: PresenceState,
    ) = assertEquals(
        "wifi=$wifiEnabled/$wifi geofence=$geofenceEnabled/$geofence",
        expected,
        PresenceAggregator.aggregate(wifiEnabled, wifi, geofenceEnabled, geofence),
    )
}
