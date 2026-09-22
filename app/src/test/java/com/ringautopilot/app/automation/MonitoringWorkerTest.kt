package com.ringautopilot.app.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringWorkerTest {
    @Test
    fun `automatic failures become a widget issue only after four worker runs`() {
        assertFalse(MonitoringWorker.isPersistentFailure(0))
        assertFalse(MonitoringWorker.isPersistentFailure(1))
        assertFalse(MonitoringWorker.isPersistentFailure(2))
        assertTrue(MonitoringWorker.isPersistentFailure(3))
    }
}
