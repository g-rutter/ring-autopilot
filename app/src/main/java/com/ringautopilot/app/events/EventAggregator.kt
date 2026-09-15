package com.ringautopilot.app.events

import com.ringautopilot.app.model.EventSummary
import com.ringautopilot.app.model.RingEvent
import com.ringautopilot.app.model.RingEventType

sealed interface EventDecision {
    data class NotifyImmediately(val event: RingEvent) : EventDecision
    data object Batched : EventDecision
    data object SuppressedByCooldown : EventDecision
}

interface EventAggregator {
    fun accept(event: RingEvent): EventDecision
    fun drainSummaries(nowEpochMillis: Long): List<EventSummary>
}

class DefaultEventAggregator(
    private val groupingWindowMillis: Long,
    private val cooldownMillis: Long,
    private val immediateTypes: Set<RingEventType> = setOf(RingEventType.DOORBELL),
) : EventAggregator {
    private val pending = mutableListOf<RingEvent>()
    private val lastSeenByCameraAndType = mutableMapOf<Pair<String, RingEventType>, Long>()

    override fun accept(event: RingEvent): EventDecision {
        if (event.type in immediateTypes) return EventDecision.NotifyImmediately(event)

        val key = event.cameraId to event.type
        val lastSeen = lastSeenByCameraAndType[key]
        lastSeenByCameraAndType[key] = event.occurredAtEpochMillis
        if (lastSeen != null && event.occurredAtEpochMillis - lastSeen < cooldownMillis) {
            pending += event
            return EventDecision.SuppressedByCooldown
        }

        pending += event
        return EventDecision.Batched
    }

    override fun drainSummaries(nowEpochMillis: Long): List<EventSummary> {
        val cutoff = nowEpochMillis - groupingWindowMillis
        val ready = pending.filter { it.occurredAtEpochMillis <= cutoff }
        pending.removeAll(ready.toSet())
        return ready
            .groupBy { it.cameraId to it.type }
            .values
            .map { events ->
                EventSummary(
                    cameraName = events.first().cameraName,
                    type = events.first().type,
                    count = events.size,
                    windowStartEpochMillis = events.minOf(RingEvent::occurredAtEpochMillis),
                    windowEndEpochMillis = events.maxOf(RingEvent::occurredAtEpochMillis),
                )
            }
    }
}
