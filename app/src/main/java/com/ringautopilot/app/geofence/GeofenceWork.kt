package com.ringautopilot.app.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ringautopilot.app.storage.PreferencesSettingsRepository

class GeofenceRegistrationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val store = PreferencesGeofencePresenceStore(applicationContext)
        val manager = AndroidGeofenceManager(
            applicationContext,
            PreferencesSettingsRepository(applicationContext),
            store,
        )
        val result = manager.reconcile()
        return if (result.retryable) Result.retry() else Result.success()
    }
}

object GeofenceWorkScheduler {
    private const val REGISTRATION_WORK_NAME = "geofence-registration"

    fun scheduleRegistration(context: Context, reason: String) {
        val request = OneTimeWorkRequestBuilder<GeofenceRegistrationWorker>()
            .setInputData(Data.Builder().putString("reason", reason).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            REGISTRATION_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}

class GeofenceRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val settings = PreferencesSettingsRepository(context).settings.value
        if (settings.geofencePresenceEnabled && settings.hasValidGeofence) {
            GeofenceWorkScheduler.scheduleRegistration(context, "system_restore")
        }
    }
}
