package com.ringautopilot.app.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationSettingsTest {
    @Test
    fun `presence requires at least one enabled and valid detector`() {
        assertFalse(AutomationSettings().hasConfiguredPresence)
        assertFalse(AutomationSettings(wifiPresenceEnabled = true).hasConfiguredPresence)
        assertTrue(AutomationSettings(
            wifiPresenceEnabled = true,
            homeWifiSsid = "Home",
        ).hasConfiguredPresence)
        assertTrue(AutomationSettings(
            geofencePresenceEnabled = true,
            homeLatitude = 51.5,
            homeLongitude = -0.1,
        ).hasConfiguredPresence)
    }

    @Test
    fun `geofence definition validates coordinates and radius`() {
        assertTrue(AutomationSettings(homeLatitude = 90.0, homeLongitude = 180.0).hasValidGeofence)
        assertFalse(AutomationSettings(homeLatitude = 90.1, homeLongitude = 0.0).hasValidGeofence)
        assertFalse(AutomationSettings(homeLatitude = 0.0, homeLongitude = 180.1).hasValidGeofence)
        assertFalse(AutomationSettings(
            homeLatitude = 0.0,
            homeLongitude = 0.0,
            homeGeofenceRadiusMeters = 99f,
        ).hasValidGeofence)
    }
}
