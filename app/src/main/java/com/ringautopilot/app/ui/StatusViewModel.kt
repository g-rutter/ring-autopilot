package com.ringautopilot.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ringautopilot.app.AppContainer
import com.ringautopilot.app.automation.AutomationController
import com.ringautopilot.app.automation.AutomationStatus
import com.ringautopilot.app.model.PresenceState
import com.ringautopilot.app.model.ControlMode
import com.ringautopilot.app.model.RingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StatusUiState(
    val homeWifiSsid: String = "",
    val isSetupComplete: Boolean = false,
    val ringLocationId: String = "",
    val presence: PresenceState = PresenceState.UNKNOWN,
    val ringMode: RingMode = RingMode.UNKNOWN,
    val controlMode: ControlMode = ControlMode.AUTO,
    val automationStatus: AutomationStatus = AutomationStatus.Idle,
    val ringValidationMessage: String = "Not yet checked",
    val ringOperationInProgress: Boolean = false,
    val ringConnectionFailed: Boolean = false,
)

class StatusViewModel(private val container: AppContainer) : ViewModel() {
    private val automationController = AutomationController(
        scope = viewModelScope,
        presenceService = container.presenceService,
        ringService = container.ringService,
        settingsRepository = container.settingsRepository,
        notificationService = container.notificationService,
    )
    private val mutableRingValidation = MutableStateFlow(RingValidationState())

    val uiState: StateFlow<StatusUiState> = combine(
        container.settingsRepository.settings,
        container.presenceService.presence,
        container.ringService.mode,
        automationController.status,
        mutableRingValidation,
    ) { settings, presence, ringMode, automationStatus, validation ->
        StatusUiState(
            homeWifiSsid = settings.homeWifiSsid,
            isSetupComplete = settings.homeWifiSsid.isNotBlank(),
            ringLocationId = settings.ringLocationId,
            presence = presence,
            ringMode = ringMode,
            controlMode = settings.controlMode,
            automationStatus = automationStatus,
            ringValidationMessage = validation.message,
            ringOperationInProgress = validation.inProgress,
            ringConnectionFailed = validation.connectionFailed,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StatusUiState(),
    )

    fun startAutomation() = automationController.start()

    fun stopAutomation() = automationController.stop()

    fun saveHomeWifiSsid(ssid: String) {
        container.settingsRepository.updateHomeWifiSsid(ssid)
        container.presenceService.refresh()
    }

    fun saveRingLocationId(locationId: String) {
        container.settingsRepository.updateRingLocationId(locationId)
    }

    fun useCurrentWifi(): String {
        val ssid = container.presenceService.currentWifiSsid()
            ?: return "Could not read the connected Wi-Fi. Allow Precise location and ensure Location services are turned on."
        saveHomeWifiSsid(ssid)
        return "Home Wi-Fi saved: $ssid"
    }

    fun saveRingCredentials(refreshToken: String, locationId: String) {
        container.settingsRepository.updateRingLocationId(locationId)
        viewModelScope.launch {
            container.tokenStore.writeRefreshToken(refreshToken.trim())
            refreshRingMode()
        }
    }

    fun refreshRingMode() {
        viewModelScope.launch {
            mutableRingValidation.value = RingValidationState("Checking Ring credentials and current mode…", true)
            container.ringService.refreshMode()
                .onSuccess { mode ->
                    mutableRingValidation.value = RingValidationState(
                        "Connected. Ring reports ${mode.displayName()}.",
                    )
                }
                .onFailure { error ->
                    mutableRingValidation.value = RingValidationState(
                        "Could not read Ring mode: ${error.userMessage()}",
                        connectionFailed = true,
                    )
                }
        }
    }

    fun selectControlMode(mode: ControlMode) {
        if (container.settingsRepository.settings.value.controlMode == mode) return
        container.settingsRepository.updateControlMode(mode)
        if (mode == ControlMode.AUTO) {
            mutableRingValidation.value = RingValidationState("Automatic changes are enabled.")
            return
        }
        val ringMode = when (mode) {
            ControlMode.AWAY -> RingMode.AWAY
            ControlMode.DISARMED -> RingMode.DISARMED
            ControlMode.AUTO -> error("Auto mode does not select a Ring mode")
        }
        viewModelScope.launch {
            mutableRingValidation.value = RingValidationState("Requesting ${ringMode.displayName()}…", true)
            container.ringService.setMode(ringMode)
                .onSuccess {
                    mutableRingValidation.value = RingValidationState(
                        "Ring confirmed ${ringMode.displayName()}. Automatic changes are off.",
                    )
                }
                .onFailure { error ->
                    mutableRingValidation.value = RingValidationState(
                        "Could not switch to ${ringMode.displayName()}: ${error.userMessage()}",
                        connectionFailed = true,
                    )
                }
        }
    }

    fun refreshPresence() = container.presenceService.refresh()

    override fun onCleared() {
        automationController.stop()
        super.onCleared()
    }
}

private data class RingValidationState(
    val message: String = "Not yet checked",
    val inProgress: Boolean = false,
    val connectionFailed: Boolean = false,
)

private fun RingMode.displayName(): String =
    name.lowercase().replaceFirstChar(Char::uppercase)

private fun Throwable.userMessage(): String =
    (message ?: "Unknown error")
        .replace(Regex("[\\r\\n]+"), " ")
        .take(180)

class StatusViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(StatusViewModel::class.java))
        return StatusViewModel(container) as T
    }
}
