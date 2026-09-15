package com.ringautopilot.app.automation

import com.ringautopilot.app.model.PresenceState
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
import kotlinx.coroutines.launch

sealed interface AutomationStatus {
    data object Idle : AutomationStatus
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
) {
    private val mutableStatus = MutableStateFlow<AutomationStatus>(AutomationStatus.Idle)
    private var transitionJob: Job? = null
    private var observationJob: Job? = null

    val status: StateFlow<AutomationStatus> = mutableStatus.asStateFlow()

    fun start() {
        if (observationJob != null) return
        presenceService.start()
        observationJob = scope.launch {
            presenceService.presence.collectLatest(::onPresenceChanged)
        }
    }

    fun stop() {
        observationJob?.cancel()
        observationJob = null
        transitionJob?.cancel()
        transitionJob = null
        presenceService.stop()
    }

    private fun onPresenceChanged(presence: PresenceState) {
        transitionJob?.cancel()
        transitionJob = null

        val settings = settingsRepository.settings.value
        val desiredMode = desiredModeFor(presence) ?: run {
            mutableStatus.value = AutomationStatus.Idle
            return
        }
        val delaySeconds = when (presence) {
            PresenceState.HOME -> settings.arrivalDelaySeconds
            PresenceState.AWAY -> settings.departureDelaySeconds
            else -> return
        }

        mutableStatus.value = AutomationStatus.Waiting(desiredMode, delaySeconds)
        transitionJob = scope.launch {
            delay(delaySeconds * 1_000)
            switchIfNeeded(desiredMode)
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
