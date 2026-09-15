package com.ringautopilot.app

import android.content.Context
import com.ringautopilot.app.notifications.AndroidNotificationService
import com.ringautopilot.app.notifications.NotificationService
import com.ringautopilot.app.presence.AndroidWifiPresenceService
import com.ringautopilot.app.presence.PresenceService
import com.ringautopilot.app.ring.RingService
import com.ringautopilot.app.ring.HttpRingService
import com.ringautopilot.app.storage.PreferencesSettingsRepository
import com.ringautopilot.app.storage.EncryptedTokenStore
import com.ringautopilot.app.storage.SettingsRepository
import com.ringautopilot.app.storage.TokenStore

class AppContainer(context: Context) {
    val settingsRepository: SettingsRepository = PreferencesSettingsRepository(context)
    val tokenStore: TokenStore = EncryptedTokenStore(context)
    val presenceService: PresenceService = AndroidWifiPresenceService(context, settingsRepository)
    val ringService: RingService = HttpRingService(context, settingsRepository, tokenStore)
    val notificationService: NotificationService = AndroidNotificationService(context)
}
