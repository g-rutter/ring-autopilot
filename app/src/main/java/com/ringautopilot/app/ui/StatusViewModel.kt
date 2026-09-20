package com.ringautopilot.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ringautopilot.app.AppContainer
import com.ringautopilot.app.logging.Diagnostics
import com.ringautopilot.app.logging.errorReason
import com.ringautopilot.app.logging.operationId
import com.ringautopilot.app.automation.AutomationController
import com.ringautopilot.app.automation.AutomationStatus
import com.ringautopilot.app.automation.CheckOrigin
import com.ringautopilot.app.automation.checkResult
import com.ringautopilot.app.model.ControlMode
import com.ringautopilot.app.model.PresenceState
import com.ringautopilot.app.model.RingMode
import com.ringautopilot.app.geofence.GeofenceRegistrationHealth
import com.ringautopilot.app.storage.LastCheck
import com.ringautopilot.app.widget.RingWidgetProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StatusUiState(
    val homeWifiSsid: String = "",
    val wifiPresenceEnabled: Boolean = false,
    val geofencePresenceEnabled: Boolean = false,
    val homeLatitude: Double? = null,
    val homeLongitude: Double? = null,
    val homeGeofenceRadiusMeters: Float = 100f,
    val isSetupComplete: Boolean = false,
    val ringLocationId: String = "",
    val presence: PresenceState = PresenceState.UNKNOWN,
    val wifiPresence: PresenceState = PresenceState.NOT_CONFIGURED,
    val geofencePresence: PresenceState = PresenceState.NOT_CONFIGURED,
    val geofenceRegistrationHealth: GeofenceRegistrationHealth =
        GeofenceRegistrationHealth.NOT_CONFIGURED,
    val ringMode: RingMode = RingMode.UNKNOWN,
    val controlMode: ControlMode = ControlMode.AUTO,
    val automationStatus: AutomationStatus = AutomationStatus.Idle,
    val ringValidationMessage: String? = null,
    val ringOperationInProgress: Boolean = false,
    val ringConnectionFailed: Boolean = false,
    val lastManualCheck: LastCheck? = null,
    val lastAutomationCheck: LastCheck? = null,
)

class StatusViewModel(private val container: AppContainer) : ViewModel() {
    private val automationController = AutomationController(
        scope = viewModelScope,
        presenceService = container.presenceService,
        ringService = container.ringService,
        settingsRepository = container.settingsRepository,
        notificationService = container.notificationService,
        pendingChangeStore = container.statusStore,
        schedulePendingWork = { delayMillis, replace ->
            com.ringautopilot.app.automation.MonitoringWorkScheduler.schedulePending(
                container.appContext, delayMillis, replace)
        },
        cancelPendingWork = {
            com.ringautopilot.app.automation.MonitoringWorkScheduler.cancelPending(
                container.appContext)
        },
        onCheckFinished = { presence, status, origin ->
            val result = checkResult(presence, status)
            container.statusStore.saveCheck(result.summary, result.problem,
                automated = origin == CheckOrigin.AUTOMATIC)
            RingWidgetProvider.updateAll(container.appContext)
        },
    )
    private val mutableRingValidation = MutableStateFlow(RingValidationState())

    init {
        viewModelScope.launch {
            container.ringService.mode.collect { mode ->
                if (mode == RingMode.AWAY || mode == RingMode.DISARMED) {
                    container.statusStore.saveCameraMode(mode)
                    RingWidgetProvider.updateAll(container.appContext)
                }
            }
        }
    }

    private data class DetectorState(
        val combined: PresenceState,
        val wifi: PresenceState,
        val geofence: PresenceState,
        val health: GeofenceRegistrationHealth,
    )

    private val detectorState = combine(
        container.presenceService.presence,
        container.presenceService.wifiPresence,
        container.presenceService.geofencePresence,
        container.geofenceManager.registrationHealth,
    ) { combined, wifi, geofence, health ->
        DetectorState(combined, wifi, geofence.state, health)
    }

    private val baseState = combine(
        container.settingsRepository.settings,
        detectorState,
        container.ringService.mode,
        automationController.status,
        mutableRingValidation,
    ) { settings, detectors, ringMode, automationStatus, validation ->
        StatusUiState(
            homeWifiSsid = settings.homeWifiSsid,
            wifiPresenceEnabled = settings.wifiPresenceEnabled,
            geofencePresenceEnabled = settings.geofencePresenceEnabled,
            homeLatitude = settings.homeLatitude,
            homeLongitude = settings.homeLongitude,
            homeGeofenceRadiusMeters = settings.homeGeofenceRadiusMeters,
            isSetupComplete = settings.hasConfiguredPresence,
            ringLocationId = settings.ringLocationId,
            presence = detectors.combined,
            wifiPresence = detectors.wifi,
            geofencePresence = detectors.geofence,
            geofenceRegistrationHealth = detectors.health,
            ringMode = ringMode,
            controlMode = settings.controlMode,
            automationStatus = automationStatus,
            ringValidationMessage = validation.message,
            ringOperationInProgress = validation.inProgress,
            ringConnectionFailed = validation.connectionFailed,
        )
    }

    val uiState: StateFlow<StatusUiState> = combine(
        baseState, container.statusStore.observeCameraMode(),
        container.statusStore.observeLastManualCheck(), container.statusStore.observeLastAutomationCheck(),
    ) { state, savedCameraMode, manualCheck, automationCheck ->
        state.copy(
            ringMode = when (state.ringMode) {
                RingMode.AWAY, RingMode.DISARMED -> state.ringMode
                RingMode.UNKNOWN, RingMode.UNAVAILABLE -> savedCameraMode
            },
            lastManualCheck = manualCheck,
            lastAutomationCheck = automationCheck,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StatusUiState(ringMode = container.statusStore.cameraMode()),
    )

    fun savePresenceConfiguration(
        wifiEnabled: Boolean,
        ssid: String,
        geofenceEnabled: Boolean,
        latitude: Double?,
        longitude: Double?,
        radiusMeters: Float,
    ) {
        val old = container.settingsRepository.settings.value
        container.settingsRepository.updateHomeWifiSsid(ssid)
        container.settingsRepository.updateWifiPresenceEnabled(wifiEnabled)
        if (latitude != null && longitude != null) {
            container.settingsRepository.updateHomeGeofence(latitude, longitude, radiusMeters)
        }
        container.settingsRepository.updateGeofencePresenceEnabled(geofenceEnabled)
        if (old.wifiPresenceEnabled != wifiEnabled) {
            Diagnostics.info("user_action_end", mapOf(
                "action" to "wifi_toggle", "outcome" to "changed", "enabled" to wifiEnabled))
        }
        if (old.geofencePresenceEnabled != geofenceEnabled) {
            Diagnostics.info("user_action_end", mapOf(
                "action" to "geofence_toggle", "outcome" to "changed", "enabled" to geofenceEnabled))
        }
        container.presenceService.refresh()
        com.ringautopilot.app.geofence.GeofenceWorkScheduler.scheduleRegistration(
            container.appContext, "configuration_saved")
        reconcileGeofence("configuration_saved")
    }

    fun saveRingLocationId(locationId: String) {
        container.settingsRepository.updateRingLocationId(locationId)
    }

    fun currentWifiSsid(): String? = container.presenceService.currentWifiSsid()

    fun saveRingCredentials(refreshToken: String, locationId: String) {
        container.settingsRepository.updateRingLocationId(locationId)
        viewModelScope.launch {
            container.tokenStore.writeRefreshToken(refreshToken.trim())
            refreshRingMode()
        }
    }

    fun refreshRingMode() {
        val opId = operationId()
        Diagnostics.info("user_action_start", mapOf("action" to "refresh_ring", "opId" to opId))
        viewModelScope.launch {
            mutableRingValidation.value = RingValidationState("Checking Ring credentials and current mode…", true)
            container.ringService.refreshMode()
                .onSuccess { mode ->
                    Diagnostics.info("user_action_end", mapOf("action" to "refresh_ring", "opId" to opId, "outcome" to "success", "mode" to mode))
                    container.statusStore.saveCameraMode(mode)
                    container.statusStore.saveCheck("Ring status · ${mode.displayName()}", false)
                    RingWidgetProvider.updateAll(container.appContext)
                    mutableRingValidation.value = RingValidationState(
                        "Connected. Ring reports ${mode.displayName()}.",
                    )
                }
                .onFailure { error ->
                    Diagnostics.warn("user_action_end", mapOf("action" to "refresh_ring", "opId" to opId, "outcome" to "failure", "reason" to errorReason(error)), error)
                    container.statusStore.saveCheck("Ring status unavailable", true)
                    RingWidgetProvider.updateAll(container.appContext)
                    mutableRingValidation.value = RingValidationState(
                        "Could not read Ring mode: ${error.userMessage()}",
                        connectionFailed = true,
                    )
                }
        }
    }

    fun setAutoEnabled(enabled: Boolean) {
        val mode = if (enabled) ControlMode.AUTO else ControlMode.MANUAL
        if (container.settingsRepository.settings.value.controlMode == mode) return
        Diagnostics.info("user_action_end", mapOf("action" to "auto_toggle", "outcome" to "changed", "enabled" to enabled))
        container.settingsRepository.updateControlMode(mode)
        if (!enabled) {
            container.statusStore.clearPendingChange()
            com.ringautopilot.app.automation.MonitoringWorkScheduler.cancelPending(
                container.appContext)
        }
        container.statusStore.saveControlMode(mode)
        RingWidgetProvider.updateAll(container.appContext)
        mutableRingValidation.value = RingValidationState(
            if (enabled) "Automatic changes are enabled." else "Automatic changes are off.",
        )
    }

    fun setRingMode(ringMode: RingMode) {
        require(ringMode == RingMode.AWAY || ringMode == RingMode.DISARMED)
        setAutoEnabled(false)
        val opId = operationId()
        Diagnostics.info("user_action_start", mapOf("action" to "force_mode", "opId" to opId, "desiredMode" to ringMode))
        viewModelScope.launch {
            mutableRingValidation.value = RingValidationState("Requesting ${ringMode.displayName()}…", true)
            container.ringService.setMode(ringMode)
                .onSuccess {
                    Diagnostics.info("user_action_end", mapOf("action" to "force_mode", "opId" to opId, "outcome" to "success", "mode" to ringMode))
                    container.statusStore.saveCameraMode(ringMode)
                    container.statusStore.saveCheck("Force ${ringMode.forceLabel()} · Confirmed", false)
                    RingWidgetProvider.updateAll(container.appContext)
                    mutableRingValidation.value = RingValidationState(
                        "Ring confirmed ${ringMode.displayName()}. Automatic changes are off.",
                    )
                }
                .onFailure { error ->
                    Diagnostics.warn("user_action_end", mapOf("action" to "force_mode", "opId" to opId, "outcome" to "failure", "reason" to errorReason(error)), error)
                    container.statusStore.saveCheck("Force ${ringMode.forceLabel()} failed: ${error.userMessage()}", true)
                    RingWidgetProvider.updateAll(container.appContext)
                    mutableRingValidation.value = RingValidationState(
                        "Could not switch to ${ringMode.displayName()}: ${error.userMessage()}",
                        connectionFailed = true,
                    )
                }
        }
    }

    fun refreshPresence() = container.presenceService.refresh()

    fun reconcileGeofence(trigger: String = "app_resume") {
        viewModelScope.launch {
            container.geofenceManager.reconcile(trigger)
            container.presenceService.refresh()
        }
    }

    fun refreshLastCheck() {
        container.statusStore.saveControlMode(container.settingsRepository.settings.value.controlMode)
        val mode = container.ringService.mode.value
        if (mode == RingMode.AWAY || mode == RingMode.DISARMED) container.statusStore.saveCameraMode(mode)
        RingWidgetProvider.updateAll(container.appContext)
    }

    fun syncNow() = automationController.syncNow()

    fun startAutomation() = automationController.start()

    fun stopAutomation() = automationController.stop()

    override fun onCleared() {
        automationController.stop()
        super.onCleared()
    }

}

private data class RingValidationState(
    val message: String? = null,
    val inProgress: Boolean = false,
    val connectionFailed: Boolean = false,
)

private fun RingMode.displayName(): String =
    name.lowercase().replaceFirstChar(Char::uppercase)

private fun RingMode.forceLabel(): String = if (this == RingMode.DISARMED) "Disarm" else "Away"

private fun Throwable.userMessage(): String =
    (message ?: "Unknown error")
        .replace(Regex("[\\r\\n]+"), " ")

class StatusViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(StatusViewModel::class.java))
        return StatusViewModel(container) as T
    }
}
