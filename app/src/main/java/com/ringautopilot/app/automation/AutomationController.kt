package com.ringautopilot.app.automation

import com.ringautopilot.app.logging.Diagnostics
import com.ringautopilot.app.logging.errorReason
import com.ringautopilot.app.logging.operationId
import com.ringautopilot.app.model.PresenceState
import com.ringautopilot.app.model.ControlMode
import com.ringautopilot.app.model.RingMode
import com.ringautopilot.app.notifications.NotificationService
import com.ringautopilot.app.presence.PresenceService
import com.ringautopilot.app.ring.RingService
import com.ringautopilot.app.storage.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

sealed interface AutomationStatus {
    data object Idle : AutomationStatus
    data object ManualOverride : AutomationStatus
    data class Waiting(val desiredMode: RingMode, val delaySeconds: Long) : AutomationStatus
    data class Switching(val desiredMode: RingMode) : AutomationStatus
    data class Retrying(
        val desiredMode: RingMode,
        val attempt: Int,
        val maxAttempts: Int,
        val delaySeconds: Long,
    ) : AutomationStatus
    data class Failed(val message: String) : AutomationStatus
}

enum class CheckOrigin { AUTOMATIC, MANUAL_APPLY }

class AutomationController(
    private val scope: CoroutineScope,
    private val presenceService: PresenceService,
    private val ringService: RingService,
    private val settingsRepository: SettingsRepository,
    private val notificationService: NotificationService,
    private val pendingChangeStore: PendingChangeStore,
    private val schedulePendingWork: (Long, Boolean) -> Unit,
    private val cancelPendingWork: () -> Unit = {},
    private val onCheckFinished: (PresenceState, AutomationStatus, CheckOrigin) -> Unit = { _, _, _ -> },
) {
    private val mutableStatus = MutableStateFlow<AutomationStatus>(AutomationStatus.Idle)
    private var transitionJob: Job? = null
    private var observationJob: Job? = null
    private var lastDecision = "unavailable"
    private var currentOpId = "none"

    val status: StateFlow<AutomationStatus> = mutableStatus.asStateFlow()

    fun start() {
        if (observationJob != null) return
        presenceService.start()
        observationJob = scope.launch {
            combine(settingsRepository.settings, presenceService.presence) { settings, presence ->
                settings to presence
            }.collectLatest { (settings, presence) ->
                onStateChanged(settings, presence)
            }
        }
    }

    fun stop() {
        observationJob?.cancel()
        observationJob = null
        transitionJob?.cancel()
        transitionJob = null
        presenceService.stop()
    }

    /** Performs one complete presence check and automation decision for a background worker. */
    suspend fun runOnce() {
        presenceService.refresh()
        val presence = presenceService.presence.value
        lastDecision = "unavailable"
        currentOpId = operationId()
        Diagnostics.info("check_start", checkFields("automatic", presence) +
            mapOf("auto" to (settingsRepository.settings.value.controlMode == ControlMode.AUTO)))
        runAutomation(settingsRepository.settings.value, presence, waitForDeadline = false)
        if (settingsRepository.settings.value.controlMode == ControlMode.AUTO) {
            onCheckFinished(presence, mutableStatus.value, CheckOrigin.AUTOMATIC)
        }
        logDecision("automatic", presence)
    }

    /**
     * Immediately applies the mode implied by the current combined presence.
     *
     * Unlike scheduled automation, this is an explicit user request and is
     * therefore allowed while Auto is off.
     * It deliberately leaves that control mode unchanged, so a one-off sync
     * never re-enables automatic changes.
     */
    fun syncNow() {
        transitionJob?.cancel()
        transitionJob = scope.launch {
            presenceService.refresh()
            lastDecision = "unavailable"
            currentOpId = operationId()
            Diagnostics.info("check_start", checkFields("manual_apply", presenceService.presence.value))
            val desiredMode = desiredModeFor(presenceService.presence.value) ?: run {
                mutableStatus.value = AutomationStatus.Idle
                onCheckFinished(presenceService.presence.value, mutableStatus.value, CheckOrigin.MANUAL_APPLY)
                logDecision("manual_apply", presenceService.presence.value)
                return@launch
            }
            switchIfNeeded(desiredMode, RetryPolicy.manual())
            onCheckFinished(presenceService.presence.value, mutableStatus.value, CheckOrigin.MANUAL_APPLY)
            logDecision("manual_apply", presenceService.presence.value)
        }
    }

    private var lastPresenceConfiguration: List<Any?>? = null

    private fun onStateChanged(
        settings: com.ringautopilot.app.model.AutomationSettings,
        presence: PresenceState,
    ) {
        transitionJob?.cancel()
        transitionJob = null

        val configuration = listOf(
            settings.wifiPresenceEnabled,
            settings.homeWifiSsid,
            settings.geofencePresenceEnabled,
            settings.homeLatitude,
            settings.homeLongitude,
            settings.homeGeofenceRadiusMeters,
        )
        if (lastPresenceConfiguration != null && lastPresenceConfiguration != configuration) {
            clearPendingChange()
        }
        lastPresenceConfiguration = configuration

        transitionJob = scope.launch {
            lastDecision = "unavailable"
            currentOpId = operationId()
            Diagnostics.info("check_start", checkFields("automatic", presence))
            runAutomation(settingsRepository.settings.value, presence, waitForDeadline = true)
            if (settingsRepository.settings.value.controlMode == ControlMode.AUTO) {
                onCheckFinished(presence, mutableStatus.value, CheckOrigin.AUTOMATIC)
            }
            logDecision("automatic", presence)
        }
    }

    private fun logDecision(origin: String, presence: PresenceState) {
        val reason = lastDecision
        Diagnostics.info("check_end", checkFields(origin, presence) + mapOf("currentMode" to ringService.mode.value,
            "desiredMode" to desiredModeFor(presence), "auto" to (settingsRepository.settings.value.controlMode == ControlMode.AUTO),
            "pendingDeadline" to pendingChangeStore.pendingChange()?.dueAtMillis, "outcome" to reason))
    }

    private fun checkFields(origin: String, presence: PresenceState): Map<String, Any?> {
        val settings = settingsRepository.settings.value
        return mapOf(
            "opId" to currentOpId,
            "origin" to origin,
            "presence" to presence,
            "wifiEnabled" to settings.wifiPresenceEnabled,
            "geofenceEnabled" to settings.geofencePresenceEnabled,
        )
    }

    private suspend fun runAutomation(
        settings: com.ringautopilot.app.model.AutomationSettings,
        presence: PresenceState,
        waitForDeadline: Boolean,
    ) {
        if (settings.controlMode != ControlMode.AUTO) {
            clearPendingChange()
            lastDecision = "auto_off"
            mutableStatus.value = AutomationStatus.ManualOverride
            return
        }

        val desiredMode = desiredModeFor(presence) ?: run {
            clearPendingChange()
            lastDecision = "unavailable"
            mutableStatus.value = AutomationStatus.Idle
            return
        }
        val delaySeconds = when (presence) {
            PresenceState.HOME -> settings.arrivalDelaySeconds
            PresenceState.AWAY -> settings.departureDelaySeconds
            else -> return
        }

        // Do not present a pending change when Ring is already in the desired
        // mode (or when its status cannot yet be read).
        val retryPolicy = RetryPolicy.automatic(settings)
        val currentMode = refreshModeWithRetry(retryPolicy).getOrElse {
            lastDecision = "failed"
            mutableStatus.value = AutomationStatus.Failed(
                it.message ?: "Could not read Ring mode",
            )
            return
        }
        if (currentMode == desiredMode) {
            clearPendingChange()
            lastDecision = "already_correct"
            mutableStatus.value = AutomationStatus.Idle
            return
        }
        val now = System.currentTimeMillis()
        val previous = pendingChangeStore.pendingChange()
        val isNewPending = previous?.desiredMode != desiredMode
        val pending = if (!isNewPending) previous else {
            PendingChange(desiredMode, now + delaySeconds.coerceAtLeast(0) * 1_000).also {
                pendingChangeStore.savePendingChange(it)
            }
        }
        if (isNewPending || now < pending.dueAtMillis) {
            schedulePendingWork((pending.dueAtMillis - now).coerceAtLeast(0), isNewPending)
        }
        if (waitForDeadline) countdownToSwitch(pending)
        else if (now < pending.dueAtMillis) {
            lastDecision = "waiting"
            mutableStatus.value = AutomationStatus.Waiting(desiredMode,
                ((pending.dueAtMillis - now + 999) / 1_000))
            return
        }

        // A periodic worker has no continuous network callback while it waits.
        // Re-read presence before applying a delayed background change.
        presenceService.refresh()
        val latestSettings = settingsRepository.settings.value
        if (
            latestSettings.controlMode != ControlMode.AUTO ||
            desiredModeFor(presenceService.presence.value) != desiredMode
        ) {
            clearPendingChange()
            lastDecision = "state_changed"
            mutableStatus.value = AutomationStatus.Idle
            return
        }
        switchIfNeeded(desiredMode, retryPolicy)
        if (mutableStatus.value !is AutomationStatus.Failed) clearPendingChange()
    }

    private fun clearPendingChange() {
        pendingChangeStore.clearPendingChange()
        cancelPendingWork()
    }

    /** Publishes each remaining second so the UI can show a genuine live countdown. */
    private suspend fun countdownToSwitch(pending: PendingChange) {
        while (true) {
            val remainingMillis = pending.dueAtMillis - System.currentTimeMillis()
            if (remainingMillis <= 0) return
            mutableStatus.value = AutomationStatus.Waiting(pending.desiredMode,
                (remainingMillis + 999) / 1_000)
            delay(remainingMillis.coerceAtMost(1_000))
        }
    }

    private suspend fun switchIfNeeded(desiredMode: RingMode, retryPolicy: RetryPolicy) {
        val currentMode = refreshModeWithRetry(retryPolicy).getOrElse {
            lastDecision = "failed"
            mutableStatus.value = AutomationStatus.Failed(it.message ?: "Could not read Ring mode")
            return
        }
        if (currentMode == desiredMode) {
            lastDecision = "already_correct"
            mutableStatus.value = AutomationStatus.Idle
            return
        }

        val maxAttempts = retryPolicy.maxAttempts
        val initialBackoff = retryPolicy.initialBackoffSeconds
        var lastFailure: Throwable? = null

        repeat(maxAttempts) { index ->
            val attempt = index + 1
            mutableStatus.value = AutomationStatus.Switching(desiredMode)
            Diagnostics.info("mode_write_start", mapOf("desiredMode" to desiredMode, "attempt" to attempt))
            ringService.setMode(desiredMode).onSuccess {
                Diagnostics.info("mode_write_end", mapOf("desiredMode" to desiredMode, "attempt" to attempt, "outcome" to "changed"))
                notificationService.notifyModeChanged(desiredMode)
                lastDecision = "changed"
                mutableStatus.value = AutomationStatus.Idle
                return
            }.onFailure { lastFailure = it
                Diagnostics.warn("mode_write_end", mapOf("attempt" to attempt, "outcome" to "retry", "reason" to errorReason(it)))
            }

            if (attempt < maxAttempts) {
                val delaySeconds = retryDelaySeconds(initialBackoff, index)
                mutableStatus.value = AutomationStatus.Retrying(
                    desiredMode,
                    attempt,
                    maxAttempts,
                    delaySeconds,
                )
                Diagnostics.warn("check_retry", mapOf("operation" to "mode_write", "attempt" to attempt, "backoffSeconds" to delaySeconds))
                delay(delaySeconds * 1_000)
            }
        }

        lastDecision = "failed"
        mutableStatus.value = AutomationStatus.Failed(
            lastFailure?.message ?: "Could not change Ring mode after $maxAttempts attempts",
        )
    }

    private suspend fun refreshModeWithRetry(retryPolicy: RetryPolicy): Result<RingMode> {
        val attempts = retryPolicy.maxAttempts
        repeat(attempts) { index ->
            val result = ringService.refreshMode()
            if (result.isSuccess || index == attempts - 1) return result
            Diagnostics.warn("check_retry", mapOf("operation" to "mode_read", "attempt" to index + 1,
                "backoffSeconds" to retryDelaySeconds(retryPolicy.initialBackoffSeconds, index),
                "reason" to errorReason(result.exceptionOrNull()!!)))
            delay(retryDelaySeconds(retryPolicy.initialBackoffSeconds, index) * 1_000)
        }
        error("No Ring status attempt was made")
    }

    private data class RetryPolicy(val maxAttempts: Int, val initialBackoffSeconds: Long) {
        companion object {
            fun automatic(settings: com.ringautopilot.app.model.AutomationSettings) = RetryPolicy(
                settings.modeChangeMaxAttempts.coerceAtLeast(1),
                settings.modeChangeInitialBackoffSeconds.coerceAtLeast(1),
            )

            // Explicit user actions should report back promptly.
            fun manual() = RetryPolicy(maxAttempts = 2, initialBackoffSeconds = 2)
        }
    }

    companion object {
        fun retryDelaySeconds(initialBackoffSeconds: Long, attemptIndex: Int): Long =
            (initialBackoffSeconds.coerceAtLeast(1) * (1L shl attemptIndex.coerceAtMost(6)))
                .coerceAtMost(300)

        fun desiredModeFor(presence: PresenceState): RingMode? = when (presence) {
            PresenceState.HOME -> RingMode.DISARMED
            PresenceState.AWAY -> RingMode.AWAY
            PresenceState.NOT_CONFIGURED,
            PresenceState.UNKNOWN,
            -> null
        }
    }
}
