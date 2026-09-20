package com.ringautopilot.app.presence

import com.ringautopilot.app.geofence.GeofencePresence
import com.ringautopilot.app.geofence.GeofencePresenceStore
import com.ringautopilot.app.model.AutomationSettings
import com.ringautopilot.app.model.ControlMode
import com.ringautopilot.app.model.PresenceState
import com.ringautopilot.app.storage.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CombinedPresenceServiceTest {
    @Test
    fun `home from either detector wins and all away is away`() = runTest {
        val settings = FakeSettingsRepository(AutomationSettings(
            homeWifiSsid = "home",
            wifiPresenceEnabled = true,
            geofencePresenceEnabled = true,
            homeLatitude = 1.0,
            homeLongitude = 1.0,
        ))
        val wifi = FakePresenceService(PresenceState.AWAY)
        val geofence = FakeGeofenceStore(PresenceState.HOME)
        val service = CombinedPresenceService(
            settings, wifi, geofence, this,
        )

        service.start()
        testScheduler.runCurrent()
        assertEquals(PresenceState.HOME, service.presence.value)

        geofence.update(PresenceState.AWAY)
        testScheduler.runCurrent()
        assertEquals(PresenceState.AWAY, service.presence.value)

        wifi.mutablePresence.value = PresenceState.UNKNOWN
        testScheduler.runCurrent()
        assertEquals(PresenceState.UNKNOWN, service.presence.value)
        service.stop()
    }

    private class FakePresenceService(initial: PresenceState) : PresenceService {
        val mutablePresence = MutableStateFlow(initial)
        override val presence = mutablePresence
        override fun start() = Unit
        override fun stop() = Unit
        override fun refresh() = Unit
        override fun currentWifiSsid(): String? = null
    }

    private class FakeGeofenceStore(initial: PresenceState) : GeofencePresenceStore {
        override val presence = MutableStateFlow(GeofencePresence(initial))
        override fun update(state: PresenceState, updatedAtEpochMillis: Long) {
            presence.value = GeofencePresence(state, updatedAtEpochMillis)
        }
        override fun clear(updatedAtEpochMillis: Long) {
            presence.value = GeofencePresence(PresenceState.UNKNOWN, updatedAtEpochMillis)
        }
    }

    private class FakeSettingsRepository(initial: AutomationSettings) : SettingsRepository {
        override val settings = MutableStateFlow(initial)
        override fun updateHomeWifiSsid(ssid: String) = Unit
        override fun updateWifiPresenceEnabled(enabled: Boolean) = Unit
        override fun updateGeofencePresenceEnabled(enabled: Boolean) = Unit
        override fun updateHomeGeofence(latitude: Double, longitude: Double, radiusMeters: Float) = Unit
        override fun updateRingLocationId(locationId: String) = Unit
        override fun updateControlMode(mode: ControlMode) = Unit
    }
}
