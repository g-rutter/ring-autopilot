package com.ringautopilot.app.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiagnosticLogTest {
    @Test fun `formats stable fields and redacts unsafe values`() {
        val output = formatEvent("work_end", mapOf("task" to "ring_monitor", "attempt" to 2,
            "endpoint" to "https://example.test/private?token=secret", "token" to "plainsecret", "outcome" to "retry"),
            IllegalStateException("Bearer secret"))
        assertEquals("event=work_end task=ring_monitor attempt=2 endpoint=redacted token=redacted outcome=retry errorType=IllegalStateException", output)
        assertFalse(output.contains("secret"))
    }
}
