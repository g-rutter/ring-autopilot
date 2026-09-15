package com.ringautopilot.app.notifications

import com.ringautopilot.app.model.EventSummary
import com.ringautopilot.app.model.RingMode

interface NotificationService {
    fun notifyModeChanged(mode: RingMode)
    fun notifyEventSummary(summary: EventSummary)
}
