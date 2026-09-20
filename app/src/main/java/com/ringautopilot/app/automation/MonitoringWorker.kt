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
import androidx.work.Data
import com.ringautopilot.app.logging.Diagnostics
import com.ringautopilot.app.logging.errorReason
import com.ringautopilot.app.AppContainer
import com.ringautopilot.app.automation.CheckOrigin
import com.ringautopilot.app.widget.RingWidgetProvider
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/** Checks combined presence and applies the configured Ring automation. */
class MonitoringWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val started = System.currentTimeMillis()
        val task = inputData.getString("task") ?: "ring_monitor"
        Diagnostics.info("work_start", mapOf("task" to task, "workId" to id, "attempt" to runAttemptCount))
        var outcome = "retry"
        var reason = "unexpected_error"
        var container: AppContainer? = null
        var controller: AutomationController? = null
        return try {
            val activeContainer = AppContainer(applicationContext)
            container = activeContainer
            val activeController = AutomationController(
            scope = CoroutineScope(kotlin.coroutines.coroutineContext),
            presenceService = activeContainer.presenceService,
            ringService = activeContainer.ringService,
            settingsRepository = activeContainer.settingsRepository,
            notificationService = activeContainer.notificationService,
            pendingChangeStore = activeContainer.statusStore,
            schedulePendingWork = { delayMillis, replace ->
                MonitoringWorkScheduler.schedulePending(applicationContext, delayMillis, replace)
            },
            onCheckFinished = { presence, status, origin ->
                val result = checkResult(presence, status)
                activeContainer.statusStore.saveCheck(result.summary, result.problem,
                    automated = origin == CheckOrigin.AUTOMATIC)
                RingWidgetProvider.updateAll(applicationContext)
            },
        )
            controller = activeController
            com.ringautopilot.app.geofence.GeofenceWorkScheduler.scheduleRegistration(
                applicationContext, "monitoring_worker",
            )
            activeController.runOnce()
            activeContainer.statusStore.saveControlMode(activeContainer.settingsRepository.settings.value.controlMode)
            activeContainer.statusStore.saveCameraMode(activeContainer.ringService.mode.value)
            RingWidgetProvider.updateAll(applicationContext)
            if (activeController.status.value is AutomationStatus.Failed) {
                reason = "check_failed"
                Result.retry()
            } else {
                val presence = activeContainer.presenceService.presence.value
                outcome = if (activeController.status.value is AutomationStatus.Waiting ||
                    activeController.status.value is AutomationStatus.ManualOverride ||
                    presence == com.ringautopilot.app.model.PresenceState.UNKNOWN ||
                    presence == com.ringautopilot.app.model.PresenceState.NOT_CONFIGURED) "skipped" else "success"
                reason = when (activeController.status.value) {
                    is AutomationStatus.Waiting -> "waiting"
                    AutomationStatus.ManualOverride -> "auto_off"
                    else -> if (outcome == "skipped") "presence_unavailable" else "completed"
                }
                Result.success()
            }
        } catch (error: CancellationException) {
            outcome = "skipped"
            reason = "cancelled"
            throw error
        } catch (error: Exception) {
            reason = errorReason(error)
            Diagnostics.error("work_exception", mapOf("task" to task, "workId" to id, "reason" to reason), error)
            container?.statusStore?.saveCheck("Presence automation failed", true,
                automated = true)
            RingWidgetProvider.updateAll(applicationContext)
            Result.retry()
        } finally {
            controller?.stop()
            Diagnostics.info("work_end", mapOf("task" to task, "workId" to id,
                "outcome" to outcome, "reason" to reason,
                "durationMs" to System.currentTimeMillis() - started))
        }
    }
}

object MonitoringWorkScheduler {
    private const val UNIQUE_WORK_NAME = "ring-presence-monitoring"
    private const val PENDING_WORK_NAME = "ring-pending-change"
    private const val GEOFENCE_TRANSITION_WORK_NAME = "ring-geofence-transition"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<MonitoringWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInputData(Data.Builder().putString("task", "periodic").build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        Diagnostics.info("work_enqueue", mapOf("task" to "periodic", "workName" to UNIQUE_WORK_NAME, "policy" to "keep", "delayMs" to 0, "constraints" to "connected", "workId" to request.id))
    }

    fun schedulePending(context: Context, delayMillis: Long, replace: Boolean) {
        val request = OneTimeWorkRequestBuilder<MonitoringWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder().putString("task", "pending").build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            PENDING_WORK_NAME, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request,
        )
        Diagnostics.info("work_enqueue", mapOf("task" to "pending", "workName" to PENDING_WORK_NAME, "policy" to if (replace) "replace" else "keep", "delayMs" to delayMillis, "constraints" to "connected", "workId" to request.id))
    }

    fun cancelPending(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PENDING_WORK_NAME)
    }

    fun scheduleGeofenceTransition(context: Context) {
        val request = OneTimeWorkRequestBuilder<MonitoringWorker>()
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder().putString("task", "geofence_transition").build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            GEOFENCE_TRANSITION_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        Diagnostics.info("work_enqueue", mapOf(
            "task" to "geofence_transition",
            "workName" to GEOFENCE_TRANSITION_WORK_NAME,
            "policy" to "replace",
            "delayMs" to 0,
            "constraints" to "connected",
            "workId" to request.id,
        ))
    }
}
