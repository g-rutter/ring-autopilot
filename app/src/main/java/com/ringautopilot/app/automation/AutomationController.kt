package com.ringautopilot.app.automation

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
    private val onCheckFinished: (PresenceState, AutomationStatus, CheckOrigin) -> Unit = { _, _, _ -> },
) {
    private val mutableStatus = MutableStateFlow<AutomationStatus>(AutomationStatus.Idle)
    private var transitionJob: Job? = null
    private var observationJob: Job? = null

    val status: StateFlow<AutomationStatus> = mutableStatus.asStateFlow()

    fun start() {
        if (observationJob != null) return
        presenceService.start()
        observationJob = scope.launch {
            combine(settingsRepository.settings, presenceService.presence) { settings, presence ->
                settings to presence
            }.collectLatest { (settings, presence) ->
                onStateChanged(settings.controlMode, presence)
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
        runAutomation(settingsRepository.settings.value, presence, waitForDeadline = false)
        if (settingsRepository.settings.value.controlMode == ControlMode.AUTO) {
            onCheckFinished(presence, mutableStatus.value, CheckOrigin.AUTOMATIC)
        }
    }

    /**
     * Immediately applies the mode implied by the current Wi-Fi presence.
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
            val desiredMode = desiredModeFor(presenceService.presence.value) ?: run {
                mutableStatus.value = AutomationStatus.Idle
                onCheckFinished(presenceService.presence.value, mutableStatus.value, CheckOrigin.MANUAL_APPLY)
                return@launch
            }
            switchIfNeeded(desiredMode)
            onCheckFinished(presenceService.presence.value, mutableStatus.value, CheckOrigin.MANUAL_APPLY)
        }
    }

    private fun onStateChanged(controlMode: ControlMode, presence: PresenceState) {
        transitionJob?.cancel()
        transitionJob = null

        transitionJob = scope.launch {
            runAutomation(settingsRepository.settings.value, presence, waitForDeadline = true)
            if (settingsRepository.settings.value.controlMode == ControlMode.AUTO) {
                onCheckFinished(presence, mutableStatus.value, CheckOrigin.AUTOMATIC)
            }
        }
    }

    private suspend fun runAutomation(
        settings: com.ringautopilot.app.model.AutomationSettings,
        presence: PresenceState,
        waitForDeadline: Boolean,
    ) {
        if (settings.controlMode != ControlMode.AUTO) {
            pendingChangeStore.clearPendingChange()
            mutableStatus.value = AutomationStatus.ManualOverride
            return
        }

        val desiredMode = desiredModeFor(presence) ?: run {
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
        val currentMode = refreshModeWithRetry().getOrElse {
            mutableStatus.value = AutomationStatus.Failed(
                it.message ?: "Could not read Ring mode",
            )
            return
        }
        if (currentMode == desiredMode) {
            pendingChangeStore.clearPendingChange()
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
            pendingChangeStore.clearPendingChange()
            mutableStatus.value = AutomationStatus.Idle
            return
        }
        switchIfNeeded(desiredMode)
        if (mutableStatus.value !is AutomationStatus.Failed) pendingChangeStore.clearPendingChange()
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

    private suspend fun switchIfNeeded(desiredMode: RingMode) {
        val currentMode = refreshModeWithRetry().getOrElse {
            mutableStatus.value = AutomationStatus.Failed(it.message ?: "Could not read Ring mode")
            return
        }
        if (currentMode == desiredMode) {
            mutableStatus.value = AutomationStatus.Idle
            return
        }

        val maxAttempts = settingsRepository.settings.value.modeChangeMaxAttempts.coerceAtLeast(1)
        val initialBackoff = settingsRepository.settings.value.modeChangeInitialBackoffSeconds
            .coerceAtLeast(1)
        var lastFailure: Throwable? = null

        repeat(maxAttempts) { index ->
            val attempt = index + 1
            mutableStatus.value = AutomationStatus.Switching(desiredMode)
            ringService.setMode(desiredMode).onSuccess {
                notificationService.notifyModeChanged(desiredMode)
                mutableStatus.value = AutomationStatus.Idle
                return
            }.onFailure { lastFailure = it }

            if (attempt < maxAttempts) {
                val delaySeconds = retryDelaySeconds(initialBackoff, index)
                mutableStatus.value = AutomationStatus.Retrying(
                    desiredMode,
                    attempt,
                    maxAttempts,
                    delaySeconds,
                )
                delay(delaySeconds * 1_000)
            }
        }

        mutableStatus.value = AutomationStatus.Failed(
            lastFailure?.message ?: "Could not change Ring mode after $maxAttempts attempts",
        )
    }

    private suspend fun refreshModeWithRetry(): Result<RingMode> {
        val settings = settingsRepository.settings.value
        val attempts = settings.modeChangeMaxAttempts.coerceAtLeast(1)
        repeat(attempts) { index ->
            val result = ringService.refreshMode()
            if (result.isSuccess || index == attempts - 1) return result
            delay(retryDelaySeconds(settings.modeChangeInitialBackoffSeconds, index) * 1_000)
        }
        error("No Ring status attempt was made")
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
