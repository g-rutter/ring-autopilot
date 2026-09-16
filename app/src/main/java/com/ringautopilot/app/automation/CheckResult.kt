package com.ringautopilot.app.automation

import com.ringautopilot.app.model.PresenceState

data class CheckResult(val summary: String, val problem: Boolean)

fun checkResult(presence: PresenceState, status: AutomationStatus): CheckResult = when {
    status is AutomationStatus.Failed -> CheckResult("Apply auto failed: ${status.message}", true)
    presence == PresenceState.UNKNOWN || presence == PresenceState.NOT_CONFIGURED ->
        CheckResult("Wi-Fi unavailable", false)
    status is AutomationStatus.Waiting -> CheckResult("Apply auto · Waiting", false)
    status is AutomationStatus.Retrying -> CheckResult("Apply auto · Retrying", false)
    status is AutomationStatus.Switching -> CheckResult("Apply auto · Switching", false)
    else -> CheckResult("Apply auto · Confirmed", false)
}
