package com.ringautopilot.app.events

import com.ringautopilot.app.model.RingEvent
import com.ringautopilot.app.model.RingEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultEventAggregatorTest {
    private val aggregator = DefaultEventAggregator(
        groupingWindowMillis = 300_000,
        cooldownMillis = 60_000,
    )

    @Test
    fun `doorbell bypasses batching`() {
        val decision = aggregator.accept(event(type = RingEventType.DOORBELL, at = 1_000))

        assertTrue(decision is EventDecision.NotifyImmediately)
    }

    @Test
    fun `motion events are summarized after grouping window`() {
        aggregator.accept(event(type = RingEventType.MOTION, at = 1_000))
        aggregator.accept(event(type = RingEventType.MOTION, at = 2_000))

        val summaries = aggregator.drainSummaries(nowEpochMillis = 302_000)

        assertEquals(1, summaries.size)
        assertEquals(2, summaries.single().count)
        assertEquals("Front door", summaries.single().cameraName)
    }

    private fun event(type: RingEventType, at: Long) = RingEvent(
        cameraId = "front-door",
        cameraName = "Front door",
        type = type,
        occurredAtEpochMillis = at,
    )
}
