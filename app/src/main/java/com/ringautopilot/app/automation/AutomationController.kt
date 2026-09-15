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

        mutableStatus.value = AutomationStatus.Switching(desiredMode)
        ringService.setMode(desiredMode).fold(
            onSuccess = {
                notificationService.notifyModeChanged(desiredMode)
                mutableStatus.value = AutomationStatus.Idle
            },
            onFailure = {
                mutableStatus.value = AutomationStatus.Failed(
                    it.message ?: "Could not change Ring mode",
                )
            },
        )
    }

    companion object {
        fun desiredModeFor(presence: PresenceState): RingMode? = when (presence) {
            PresenceState.HOME -> RingMode.DISARMED
            PresenceState.AWAY -> RingMode.AWAY
            PresenceState.NOT_CONFIGURED,
            PresenceState.UNKNOWN,
            -> null
        }
    }
}
