package com.ringautopilot.app.geofence

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.BackoffPolicy
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.ringautopilot.app.logging.Diagnostics
import com.ringautopilot.app.storage.PreferencesSettingsRepository
import java.util.concurrent.TimeUnit

class GeofenceRegistrationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val store = PreferencesGeofencePresenceStore(applicationContext)
        val registrationStore = PreferencesGeofenceRegistrationStore(applicationContext)
        val manager = AndroidGeofenceManager(
            applicationContext,
            PreferencesSettingsRepository(applicationContext),
            store,
            registrationStore,
        )
        val trigger = inputData.getString("trigger") ?: "worker_unspecified"
        val force = inputData.getBoolean("force", false)
        Diagnostics.info(
            "geofence_registration_work_start",
            mapOf("trigger" to trigger, "attempt" to runAttemptCount, "workId" to id),
        )
        val result = manager.reconcile(trigger, force && runAttemptCount == 0)
        val boundedInitialRetry = result.reason == "location_unavailable" ||
            result.reason == "uncertain_accuracy"
        return if (result.retryable && (!boundedInitialRetry || runAttemptCount < 2)) {
            Result.retry()
        } else Result.success()
    }
}

object GeofenceWorkScheduler {
    private const val REGISTRATION_WORK_NAME = "geofence-registration"

    fun scheduleRegistration(context: Context, reason: String, force: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<GeofenceRegistrationWorker>()
            .setInputData(Data.Builder().putString("trigger", reason)
                .putBoolean("force", force).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        val settings = PreferencesSettingsRepository(context).settings.value
        val registrationStore = PreferencesGeofenceRegistrationStore(context)
        val registration = registrationStore.registration.value
        val unavailableReason = geofenceUnavailableReason(context, settings.hasValidGeofence)
        if (!settings.geofencePresenceEnabled) {
            PreferencesGeofencePresenceStore(context).clearForGeneration(
                settings.geofenceDefinitionGeneration,
            )
            registrationStore.update(registration.copy(
                definitionGeneration = settings.geofenceDefinitionGeneration,
                registeredGeneration = null,
                health = GeofenceRegistrationHealth.UNREGISTERED,
                lastOutcome = "pending",
                lastReason = "disabled",
                lastAttemptAtEpochMillis = System.currentTimeMillis(),
            ))
        } else if (unavailableReason != null) {
            // Clear stale definitive state before callers can run automation while work is queued.
            PreferencesGeofencePresenceStore(context).clearForGeneration(
                settings.geofenceDefinitionGeneration,
            )
            registrationStore.update(registration.copy(
                definitionGeneration = settings.geofenceDefinitionGeneration,
                registeredGeneration = null,
                health = GeofenceRegistrationHealth.UNAVAILABLE,
                lastOutcome = "unavailable",
                lastReason = unavailableReason,
                lastAttemptAtEpochMillis = System.currentTimeMillis(),
            ))
        } else if (registration.health != GeofenceRegistrationHealth.REGISTERED ||
            registration.registeredGeneration != settings.geofenceDefinitionGeneration) {
            registrationStore.update(registration.copy(
                definitionGeneration = settings.geofenceDefinitionGeneration,
                health = GeofenceRegistrationHealth.REGISTERING,
                lastOutcome = "pending",
                lastReason = reason,
                lastAttemptAtEpochMillis = System.currentTimeMillis(),
            ))
        }
        Diagnostics.info(
            "geofence_registration_enqueue",
            mapOf(
                "trigger" to reason,
                "policy" to "append_or_replace",
                "workId" to request.id,
            ),
        )
        WorkManager.getInstance(context).enqueueUniqueWork(
            REGISTRATION_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }
}

private fun geofenceUnavailableReason(context: Context, hasValidGeofence: Boolean): String? = when {
    !hasValidGeofence -> "not_configured"
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
        PackageManager.PERMISSION_GRANTED -> "permission_denied"
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) !=
        PackageManager.PERMISSION_GRANTED -> "permission_denied"
    context.getSystemService(LocationManager::class.java)?.isLocationEnabled != true ->
        "location_disabled"
    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) !=
        ConnectionResult.SUCCESS -> "play_services_unavailable"
    else -> null
}

class GeofenceRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val settings = PreferencesSettingsRepository(context).settings.value
        if (settings.geofencePresenceEnabled && settings.hasValidGeofence) {
            GeofenceWorkScheduler.scheduleRegistration(context, "system_restore", force = true)
        }
    }
}
