package com.ringautopilot.app.presence

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ringautopilot.app.AppContainer
import com.ringautopilot.app.R
import com.ringautopilot.app.automation.AutomationController
import com.ringautopilot.app.events.DefaultEventAggregator
import com.ringautopilot.app.events.EventDecision
import com.ringautopilot.app.model.EventSummary
import com.ringautopilot.app.ring.RingEventSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keeps presence observation and automation alive after the Activity leaves the screen. */
class PresenceMonitoringService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var automationController: AutomationController

    override fun onCreate() {
        super.onCreate()
        val container = AppContainer(applicationContext)
        automationController = AutomationController(
            scope = serviceScope,
            presenceService = container.presenceService,
            ringService = container.ringService,
            settingsRepository = container.settingsRepository,
            notificationService = container.notificationService,
        )
        startForeground(NOTIFICATION_ID, foregroundNotification())
        automationController.start()
        startEventPolling(container)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onDestroy() {
        automationController.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startEventPolling(container: AppContainer) {
        val source = container.ringService as? RingEventSource ?: return
        serviceScope.launch {
            val settings = container.settingsRepository.settings.value
            val aggregator = DefaultEventAggregator(
                groupingWindowMillis = settings.motionGroupingWindowSeconds * 1_000,
                cooldownMillis = settings.repeatedEventCooldownSeconds * 1_000,
            )
            var lastEventTime = System.currentTimeMillis() - 60_000
            while (true) {
                source.pollEvents(lastEventTime).getOrDefault(emptyList()).forEach { event ->
                    when (val decision = aggregator.accept(event)) {
                        is EventDecision.NotifyImmediately -> container.notificationService
                            .notifyEventSummary(
                                EventSummary(
                                    cameraName = decision.event.cameraName,
                                    type = decision.event.type,
                                    count = 1,
                                    windowStartEpochMillis = decision.event.occurredAtEpochMillis,
                                    windowEndEpochMillis = decision.event.occurredAtEpochMillis,
                                ),
                            )
                        EventDecision.Batched,
                        EventDecision.SuppressedByCooldown -> Unit
                    }
                    lastEventTime = maxOf(lastEventTime, event.occurredAtEpochMillis)
                }
                aggregator.drainSummaries(System.currentTimeMillis())
                    .forEach(container.notificationService::notifyEventSummary)
                delay(EVENT_POLL_INTERVAL_MILLIS)
            }
        }
    }

    private fun foregroundNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Ring Autopilot active")
        .setContentText("Watching home Wi-Fi presence")
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private companion object {
        const val CHANNEL_ID = "ring_automation"
        const val NOTIFICATION_ID = 1002
        const val EVENT_POLL_INTERVAL_MILLIS = 60_000L
    }
}
