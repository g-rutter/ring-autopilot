package com.ringautopilot.app.automation

import com.ringautopilot.app.model.RingMode

data class PendingChange(val desiredMode: RingMode, val dueAtMillis: Long)

interface PendingChangeStore {
    fun pendingChange(): PendingChange?
    fun savePendingChange(change: PendingChange)
    fun clearPendingChange()
}
