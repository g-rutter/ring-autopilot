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
    data class ManualOverride(val mode: ControlMode) : AutomationStatus
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

class AutomationController(
    private val scope: CoroutineScope,
    private val presenceService: PresenceService,
    private val ringService: RingService,
    private val settingsRepository: SettingsRepository,
    private val notificationService: NotificationService,
    private val onCheckFinished: (PresenceState, AutomationStatus) -> Unit = { _, _ -> },
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
        runAutomation(settingsRepository.settings.value, presence)
        if (settingsRepository.settings.value.controlMode == ControlMode.AUTO) {
            onCheckFinished(presence, mutableStatus.value)
        }
    }

    /**
     * Immediately applies the mode implied by the current Wi-Fi presence.
     *
     * Unlike scheduled automation, this is an explicit user request and is
     * therefore allowed while the persisted control mode is Away or Disarmed.
     * It deliberately leaves that control mode unchanged, so a one-off sync
     * never re-enables automatic changes.
     */
    fun syncNow() {
        transitionJob?.cancel()
        transitionJob = scope.launch {
            presenceService.refresh()
            val desiredMode = desiredModeFor(presenceService.presence.value) ?: run {
                mutableStatus.value = AutomationStatus.Idle
                return@launch
            }
            switchIfNeeded(desiredMode)
            if (settingsRepository.settings.value.controlMode == ControlMode.AUTO) {
                onCheckFinished(presenceService.presence.value, mutableStatus.value)
            }
        }
    }

    private fun onStateChanged(controlMode: ControlMode, presence: PresenceState) {
        transitionJob?.cancel()
        transitionJob = null

        transitionJob = scope.launch {
            runAutomation(settingsRepository.settings.value, presence)
            if (settingsRepository.settings.value.controlMode == ControlMode.AUTO) {
                onCheckFinished(presence, mutableStatus.value)
            }
        }
    }

    private suspend fun runAutomation(
        settings: com.ringautopilot.app.model.AutomationSettings,
        presence: PresenceState,
    ) {
        if (settings.controlMode != ControlMode.AUTO) {
            mutableStatus.value = AutomationStatus.ManualOverride(settings.controlMode)
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
        val currentMode = ringService.refreshMode().getOrElse {
            mutableStatus.value = AutomationStatus.Failed(
                it.message ?: "Could not read Ring mode",
            )
            return
        }
        if (currentMode == desiredMode) {
            mutableStatus.value = AutomationStatus.Idle
            return
        }
        countdownToSwitch(desiredMode, delaySeconds)

        // A periodic worker has no continuous network callback while it waits.
        // Re-read presence before applying a delayed background change.
        presenceService.refresh()
        val latestSettings = settingsRepository.settings.value
        if (
            latestSettings.controlMode != ControlMode.AUTO ||
            desiredModeFor(presenceService.presence.value) != desiredMode
        ) {
            mutableStatus.value = AutomationStatus.Idle
            return
        }
        switchIfNeeded(desiredMode)
    }

    /** Publishes each remaining second so the UI can show a genuine live countdown. */
    private suspend fun countdownToSwitch(desiredMode: RingMode, delaySeconds: Long) {
        var remainingSeconds = delaySeconds.coerceAtLeast(0)
        while (remainingSeconds > 0) {
            mutableStatus.value = AutomationStatus.Waiting(desiredMode, remainingSeconds)
            delay(1_000)
            remainingSeconds -= 1
        }
    }

    private suspend fun switchIfNeeded(desiredMode: RingMode) {
        val currentMode = ringService.refreshMode().getOrElse {
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
