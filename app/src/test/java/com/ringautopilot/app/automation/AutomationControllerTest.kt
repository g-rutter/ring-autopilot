package com.ringautopilot.app.automation

import com.ringautopilot.app.model.PresenceState
import com.ringautopilot.app.model.RingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutomationControllerTest {
    @Test
    fun `home requests disarmed mode`() {
        assertEquals(
            RingMode.DISARMED,
            AutomationController.desiredModeFor(PresenceState.HOME),
        )
    }

    @Test
    fun `away requests away mode`() {
        assertEquals(
            RingMode.AWAY,
            AutomationController.desiredModeFor(PresenceState.AWAY),
        )
    }

    @Test
    fun `uncertain presence does not request a mode`() {
        assertNull(AutomationController.desiredModeFor(PresenceState.UNKNOWN))
        assertNull(AutomationController.desiredModeFor(PresenceState.NOT_CONFIGURED))
    }
}
