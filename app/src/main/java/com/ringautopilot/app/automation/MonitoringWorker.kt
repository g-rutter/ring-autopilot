package com.ringautopilot.app.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import com.ringautopilot.app.AppContainer
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
            onCheckFinished = { presence, status ->
                val result = checkResult(presence, status)
                container.statusStore.saveCheck(result.summary, result.problem)
                RingWidgetProvider.updateAll(applicationContext)
            },
        )
        return try {
            controller.runOnce()
            container.statusStore.saveControlMode(container.settingsRepository.settings.value.controlMode)
            container.statusStore.saveCameraMode(container.ringService.mode.value)
            RingWidgetProvider.updateAll(applicationContext)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            container.statusStore.saveCheck("Check failed: ${error.message ?: "Unknown error"}", true)
            RingWidgetProvider.updateAll(applicationContext)
            Result.retry()
        } finally {
            controller.stop()
        }
    }
}

object MonitoringWorkScheduler {
    private const val UNIQUE_WORK_NAME = "ring-presence-monitoring"

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
}
