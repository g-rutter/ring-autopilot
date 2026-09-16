package com.ringautopilot.app.automation

import com.ringautopilot.app.model.PresenceState
import com.ringautopilot.app.model.RingMode
import com.ringautopilot.app.model.AutomationSettings
import com.ringautopilot.app.model.ControlMode
import com.ringautopilot.app.notifications.NotificationService
import com.ringautopilot.app.model.EventSummary
import com.ringautopilot.app.presence.PresenceService
import com.ringautopilot.app.ring.RingService
import com.ringautopilot.app.storage.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutomationControllerTest {
    @Test
    fun `background checks retain the original deadline and apply overdue change`() = runBlocking {
        val settings = object : SettingsRepository {
            override val settings = MutableStateFlow(AutomationSettings(
                controlMode = ControlMode.AUTO, departureDelaySeconds = 30))
            override fun updateHomeWifiSsid(ssid: String) = Unit
            override fun updateRingLocationId(locationId: String) = Unit
            override fun updateControlMode(mode: ControlMode) = Unit
        }
        val presence = object : PresenceService {
            override val presence = MutableStateFlow(PresenceState.AWAY)
            override fun start() = Unit
            override fun stop() = Unit
            override fun refresh() = Unit
            override fun currentWifiSsid(): String? = null
        }
        val ring = object : RingService {
            override val mode = MutableStateFlow(RingMode.DISARMED)
            var changes = 0
            override suspend fun refreshMode() = Result.success(mode.value)
            override suspend fun setMode(mode: RingMode): Result<Unit> {
                changes++
                this.mode.value = mode
                return Result.success(Unit)
            }
        }
        val pendingStore = object : PendingChangeStore {
            var saved: PendingChange? = null
            override fun pendingChange() = saved
            override fun savePendingChange(change: PendingChange) { saved = change }
            override fun clearPendingChange() { saved = null }
        }
        val controller = AutomationController(this, presence, ring, settings,
            object : NotificationService {
                override fun notifyModeChanged(mode: RingMode) = Unit
                override fun notifyEventSummary(summary: EventSummary) = Unit
            }, pendingStore, { _, _ -> })

        controller.runOnce()
        val firstDeadline = pendingStore.saved!!.dueAtMillis
        controller.runOnce()
        assertEquals(firstDeadline, pendingStore.saved!!.dueAtMillis)
        assertEquals(0, ring.changes)

        pendingStore.saved = PendingChange(RingMode.AWAY, System.currentTimeMillis() - 1)
        controller.runOnce()
        assertEquals(1, ring.changes)
        assertNull(pendingStore.saved)
    }

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

    @Test
    fun `retry delay backs off and is capped`() {
        assertEquals(5, AutomationController.retryDelaySeconds(5, 0))
        assertEquals(20, AutomationController.retryDelaySeconds(5, 2))
        assertEquals(300, AutomationController.retryDelaySeconds(5, 10))
    }
}
