package com.ringautopilot.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ringautopilot.app.AppContainer
import com.ringautopilot.app.automation.AutomationController
import com.ringautopilot.app.automation.AutomationStatus
import com.ringautopilot.app.model.PresenceState
import com.ringautopilot.app.model.RingMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class StatusUiState(
    val homeWifiSsid: String = "",
    val presence: PresenceState = PresenceState.UNKNOWN,
    val ringMode: RingMode = RingMode.UNKNOWN,
    val automationStatus: AutomationStatus = AutomationStatus.Idle,
)

class StatusViewModel(private val container: AppContainer) : ViewModel() {
    private val automationController = AutomationController(
        scope = viewModelScope,
        presenceService = container.presenceService,
        ringService = container.ringService,
        settingsRepository = container.settingsRepository,
        notificationService = container.notificationService,
    )

    val uiState: StateFlow<StatusUiState> = combine(
        container.settingsRepository.settings,
        container.presenceService.presence,
        container.ringService.mode,
        automationController.status,
    ) { settings, presence, ringMode, automationStatus ->
        StatusUiState(
            homeWifiSsid = settings.homeWifiSsid,
            presence = presence,
            ringMode = ringMode,
            automationStatus = automationStatus,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StatusUiState(),
    )

    init {
        automationController.start()
    }

    fun saveHomeWifiSsid(ssid: String) {
        container.settingsRepository.updateHomeWifiSsid(ssid)
        container.presenceService.refresh()
    }

    fun refreshPresence() = container.presenceService.refresh()

    override fun onCleared() {
        automationController.stop()
        super.onCleared()
    }
}

class StatusViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(StatusViewModel::class.java))
        return StatusViewModel(container) as T
    }
}
