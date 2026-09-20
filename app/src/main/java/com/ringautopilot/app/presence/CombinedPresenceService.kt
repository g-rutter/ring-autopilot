package com.ringautopilot.app.presence

import com.ringautopilot.app.geofence.GeofencePresenceStore
import com.ringautopilot.app.model.PresenceState
import com.ringautopilot.app.storage.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Presents Wi-Fi and geofencing as one conservative presence signal. */
class CombinedPresenceService(
    private val settingsRepository: SettingsRepository,
    private val wifiService: PresenceService,
    private val geofenceStore: GeofencePresenceStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : PresenceService {
    private val mutablePresence = MutableStateFlow(aggregateNow())
    private var observationJob: Job? = null

    override val presence: StateFlow<PresenceState> = mutablePresence.asStateFlow()
    val wifiPresence: StateFlow<PresenceState> = wifiService.presence
    val geofencePresence: StateFlow<com.ringautopilot.app.geofence.GeofencePresence> =
        geofenceStore.presence

    override fun start() {
        if (observationJob != null) return
        syncWifiObservation()
        observationJob = scope.launch {
            combine(
                settingsRepository.settings,
                wifiService.presence,
                geofenceStore.presence,
            ) { settings, wifi, geofence ->
                if (settings.wifiPresenceEnabled && settings.homeWifiSsid.isNotBlank()) {
                    wifiService.start()
                } else {
                    wifiService.stop()
                }
                PresenceAggregator.aggregate(
                    settings.wifiPresenceEnabled,
                    wifi,
                    settings.geofencePresenceEnabled,
                    geofence.state,
                )
            }.collect { mutablePresence.value = it }
        }
    }

    override fun stop() {
        observationJob?.cancel()
        observationJob = null
        wifiService.stop()
    }

    override fun refresh() {
        syncWifiObservation()
        if (settingsRepository.settings.value.wifiPresenceEnabled) wifiService.refresh()
        mutablePresence.value = aggregateNow()
    }

    override fun currentWifiSsid(): String? = wifiService.currentWifiSsid()

    private fun syncWifiObservation() {
        val settings = settingsRepository.settings.value
        if (settings.wifiPresenceEnabled && settings.homeWifiSsid.isNotBlank()) {
            wifiService.start()
        } else {
            wifiService.stop()
        }
    }

    private fun aggregateNow(): PresenceState = settingsRepository.settings.value.let { settings ->
        PresenceAggregator.aggregate(
            settings.wifiPresenceEnabled,
            wifiService.presence.value,
            settings.geofencePresenceEnabled,
            geofenceStore.presence.value.state,
        )
    }
}
