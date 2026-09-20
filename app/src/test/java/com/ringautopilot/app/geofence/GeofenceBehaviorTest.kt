package com.ringautopilot.app.geofence

import com.ringautopilot.app.model.PresenceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceBehaviorTest {
    @Test fun `same healthy generation is a no-op`() {
        val registration = GeofenceRegistration(
            registeredGeneration = 7,
            health = GeofenceRegistrationHealth.REGISTERED,
        )
        assertEquals(RegistrationAction.NOOP, registrationAction(registration, 7, false))
    }

    @Test fun `changed generation replaces old registration`() {
        val registration = GeofenceRegistration(
            registeredGeneration = 7,
            health = GeofenceRegistrationHealth.REGISTERED,
        )
        assertEquals(RegistrationAction.REPLACE, registrationAction(registration, 8, false))
    }

    @Test fun `forced restore adds current generation once`() {
        val registration = GeofenceRegistration(
            registeredGeneration = 7,
            health = GeofenceRegistrationHealth.REGISTERED,
        )
        assertEquals(RegistrationAction.ADD, registrationAction(registration, 7, true))
    }

    @Test fun `fresh fix wholly inside seeds home`() {
        assertEquals("home", classifyInitialGeofencePresence(20f, 10f, 100f, 1_000L))
    }

    @Test fun `fresh fix clearly outside hysteresis seeds away`() {
        assertEquals("away", classifyInitialGeofencePresence(160f, 10f, 100f, 1_000L))
    }

    @Test fun `boundary overlap and inaccurate or stale fixes remain uncertain`() {
        assertEquals("uncertain_accuracy",
            classifyInitialGeofencePresence(100f, 10f, 100f, 1_000L))
        assertEquals("uncertain_accuracy",
            classifyInitialGeofencePresence(250f, 101f, 100f, 1_000L))
        assertEquals("location_unavailable",
            classifyInitialGeofencePresence(20f, 10f, 100f, 120_001L))
    }

    @Test fun `newer transition prevents initial reconciliation commit`() {
        val transition = GeofencePresence(
            PresenceState.HOME, updatedAtEpochMillis = 2_000L,
            definitionGeneration = 3, source = GeofencePresenceSource.TRANSITION,
        )
        assertFalse(canCommitInitialPresence(transition, 3, 1_000L))
        assertTrue(canCommitInitialPresence(
            GeofencePresence(PresenceState.UNKNOWN, 1_000L, 3), 3, 1_000L,
        ))
        assertFalse(canCommitInitialPresence(
            GeofencePresence(PresenceState.UNKNOWN, 1_000L, 4), 3, 1_000L,
        ))
    }
}
