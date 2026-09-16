package com.ringautopilot.app.automation

import com.ringautopilot.app.model.PresenceState
import com.ringautopilot.app.model.RingMode
import org.junit.Assert.assertEquals
import org.junit.Test

class CheckResultTest {
    @Test
    fun `completed check is confirmed`() {
        assertEquals(
            CheckResult("Apply auto · Confirmed", false),
            checkResult(PresenceState.HOME, AutomationStatus.Idle),
        )
    }

    @Test
    fun `pending change is not confirmed yet`() {
        assertEquals(
            CheckResult("Apply auto · Waiting", false),
            checkResult(PresenceState.AWAY, AutomationStatus.Waiting(RingMode.AWAY, 30)),
        )
    }

    @Test
    fun `failed check displays the error`() {
        assertEquals(
            CheckResult("Apply auto failed: Connection lost", true),
            checkResult(PresenceState.HOME, AutomationStatus.Failed("Connection lost")),
        )
    }
}
