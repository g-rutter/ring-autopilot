package com.ringautopilot.app.geofence

import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.ringautopilot.app.model.PresenceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceEventInterpreterTest {
    @Test fun `enter maps to home`() {
        val result = GeofenceEventInterpreter.interpret(
            false, -1, Geofence.GEOFENCE_TRANSITION_ENTER, listOf(GEOFENCE_REQUEST_ID),
        ) as InterpretedGeofenceEvent.Transition
        assertEquals(PresenceState.HOME, result.state)
        assertEquals("enter", result.transitionName)
    }

    @Test fun `exit maps to away`() {
        val result = GeofenceEventInterpreter.interpret(
            false, -1, Geofence.GEOFENCE_TRANSITION_EXIT, listOf(GEOFENCE_REQUEST_ID),
        ) as InterpretedGeofenceEvent.Transition
        assertEquals(PresenceState.AWAY, result.state)
        assertEquals("exit", result.transitionName)
    }

    @Test fun `irrelevant request id is ignored`() {
        val result = GeofenceEventInterpreter.interpret(
            false, -1, Geofence.GEOFENCE_TRANSITION_ENTER, listOf("another_geofence"),
        ) as InterpretedGeofenceEvent.Ignored
        assertEquals("irrelevant_request", result.reason)
        assertFalse(result.registrationUnavailable)
    }

    @Test fun `unsupported transition is ignored`() {
        val result = GeofenceEventInterpreter.interpret(
            false, -1, Geofence.GEOFENCE_TRANSITION_DWELL, listOf(GEOFENCE_REQUEST_ID),
        ) as InterpretedGeofenceEvent.Ignored
        assertEquals("unknown_transition", result.reason)
    }

    @Test fun `service unavailable requests registration recovery`() {
        val result = GeofenceEventInterpreter.interpret(
            true,
            GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE,
            -1,
            emptyList(),
        ) as InterpretedGeofenceEvent.Ignored
        assertEquals("not_available", result.reason)
        assertTrue(result.registrationUnavailable)
    }

    @Test fun `other platform errors are safely generalized`() {
        val result = GeofenceEventInterpreter.interpret(
            true, 98765, -1, emptyList(),
        ) as InterpretedGeofenceEvent.Ignored
        assertEquals("api_error", result.reason)
        assertFalse(result.registrationUnavailable)
        assertEquals("api_error", safeGeofenceApiReason(98765))
    }
}
