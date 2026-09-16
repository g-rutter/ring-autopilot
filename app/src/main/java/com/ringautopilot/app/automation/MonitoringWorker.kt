package com.ringautopilot.app.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import com.ringautopilot.app.AppContainer
import com.ringautopilot.app.automation.CheckOrigin
import com.ringautopilot.app.widget.RingWidgetProvider
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/** Periodically checks Wi-Fi presence and applies the configured Ring automation. */
class MonitoringWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = AppContainer(applicationContext)
        val controller = AutomationController(
            scope = CoroutineScope(kotlin.coroutines.coroutineContext),
            presenceService = container.presenceService,
            ringService = container.ringService,
            settingsRepository = container.settingsRepository,
            notificationService = container.notificationService,
            pendingChangeStore = container.statusStore,
            schedulePendingWork = { delayMillis, replace ->
                MonitoringWorkScheduler.schedulePending(applicationContext, delayMillis, replace)
            },
            onCheckFinished = { presence, status, origin ->
                val result = checkResult(presence, status)
                container.statusStore.saveCheck(result.summary, result.problem,
                    automated = origin == CheckOrigin.AUTOMATIC)
                RingWidgetProvider.updateAll(applicationContext)
            },
        )
        return try {
            controller.runOnce()
            container.statusStore.saveControlMode(container.settingsRepository.settings.value.controlMode)
            container.statusStore.saveCameraMode(container.ringService.mode.value)
            RingWidgetProvider.updateAll(applicationContext)
            if (controller.status.value is AutomationStatus.Failed) Result.retry() else Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            container.statusStore.saveCheck("Wi-Fi automation failed: ${error.message ?: "Unknown error"}", true,
                automated = true)
            RingWidgetProvider.updateAll(applicationContext)
            Result.retry()
        } finally {
            controller.stop()
        }
    }
}

object MonitoringWorkScheduler {
    private const val UNIQUE_WORK_NAME = "ring-presence-monitoring"
    private const val PENDING_WORK_NAME = "ring-pending-change"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<MonitoringWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun schedulePending(context: Context, delayMillis: Long, replace: Boolean) {
        val request = OneTimeWorkRequestBuilder<MonitoringWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            PENDING_WORK_NAME, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request,
        )
    }
}
