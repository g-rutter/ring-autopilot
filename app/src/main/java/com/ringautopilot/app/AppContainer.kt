package com.ringautopilot.app

import android.content.Context
import com.ringautopilot.app.notifications.AndroidNotificationService
import com.ringautopilot.app.notifications.NotificationService
import com.ringautopilot.app.presence.CombinedPresenceService
import com.ringautopilot.app.presence.WifiPresenceService
import com.ringautopilot.app.ring.RingService
import com.ringautopilot.app.ring.HttpRingService
import com.ringautopilot.app.storage.PreferencesSettingsRepository
import com.ringautopilot.app.storage.EncryptedTokenStore
import com.ringautopilot.app.storage.SettingsRepository
import com.ringautopilot.app.storage.TokenStore
import com.ringautopilot.app.storage.StatusStore
import com.ringautopilot.app.geofence.AndroidGeofenceManager
import com.ringautopilot.app.geofence.GeofenceManager
import com.ringautopilot.app.geofence.GeofencePresenceStore
import com.ringautopilot.app.geofence.PreferencesGeofencePresenceStore
import com.ringautopilot.app.geofence.GeofenceWorkScheduler
import com.ringautopilot.app.logging.Diagnostics

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val statusStore = StatusStore(context)
    val geofencePresenceStore: GeofencePresenceStore = PreferencesGeofencePresenceStore(context)
    val settingsRepository: SettingsRepository = PreferencesSettingsRepository(
        context = context,
        onGeofenceInvalidated = {
            Diagnostics.info(
                "geofence_state_reset",
                mapOf(
                    "reason" to "settings_changed",
                    "previousPresence" to geofencePresenceStore.presence.value.state,
                ),
            )
            geofencePresenceStore.clear()
        },
        onGeofenceReconcileRequested = {
            GeofenceWorkScheduler.scheduleRegistration(context, "settings_changed")
        },
    )
    val geofenceManager: GeofenceManager = AndroidGeofenceManager(
        context,
        settingsRepository,
        geofencePresenceStore,
    )
    val tokenStore: TokenStore = EncryptedTokenStore(context)
    private val wifiPresenceService = WifiPresenceService(context, settingsRepository)
    val presenceService = CombinedPresenceService(
        settingsRepository,
        wifiPresenceService,
        geofencePresenceStore,
    )
    val ringService: RingService = HttpRingService(context, settingsRepository, tokenStore)
    val notificationService: NotificationService = AndroidNotificationService(context)
}
