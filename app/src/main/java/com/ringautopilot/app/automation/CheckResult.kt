package com.ringautopilot.app.automation

import com.ringautopilot.app.model.PresenceState

data class CheckResult(val summary: String, val problem: Boolean)

fun checkResult(presence: PresenceState, status: AutomationStatus, origin: CheckOrigin): CheckResult = when {
    status is AutomationStatus.Failed -> CheckResult("${origin.label()} failed: ${status.message}", true)
    presence == PresenceState.UNKNOWN || presence == PresenceState.NOT_CONFIGURED ->
        CheckResult("${origin.label()} · Wi-Fi unavailable", true)
    presence == PresenceState.HOME -> CheckResult("${origin.label()} · Home", false)
    else -> CheckResult("${origin.label()} · Away", false)
}

private fun CheckOrigin.label(): String = when (this) {
    CheckOrigin.AUTOMATIC -> "Wi-Fi automation"
    CheckOrigin.MANUAL_APPLY -> "Apply Wi-Fi mode"
}
