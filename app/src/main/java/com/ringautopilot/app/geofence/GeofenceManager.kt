package com.ringautopilot.app.geofence

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task
import com.ringautopilot.app.logging.Diagnostics
import com.ringautopilot.app.logging.operationId
import com.ringautopilot.app.storage.SettingsRepository
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

enum class GeofenceRegistrationHealth {
    NOT_CONFIGURED,
    REGISTERED,
    UNAVAILABLE,
}

data class GeofenceRegistrationResult(
    val outcome: String,
    val reason: String,
    val retryable: Boolean = false,
)

interface GeofenceManager {
    val registrationHealth: StateFlow<GeofenceRegistrationHealth>

    suspend fun reconcile(trigger: String = "unspecified"): GeofenceRegistrationResult
}

class AndroidGeofenceManager(
    context: Context,
    private val settingsRepository: SettingsRepository,
    private val presenceStore: GeofencePresenceStore,
) : GeofenceManager {
    private val appContext = context.applicationContext
    private val geofencingClient = LocationServices.getGeofencingClient(appContext)
    private val mutableRegistrationHealth = MutableStateFlow(GeofenceRegistrationHealth.NOT_CONFIGURED)

    override val registrationHealth: StateFlow<GeofenceRegistrationHealth> =
        mutableRegistrationHealth.asStateFlow()

    override suspend fun reconcile(trigger: String): GeofenceRegistrationResult {
        val settings = settingsRepository.settings.value
        val previousPresence = presenceStore.presence.value
        val attemptId = operationId()
        val finePermissionGranted = hasFineLocationPermission()
        val backgroundPermissionGranted = hasBackgroundLocationPermission()
        val locationEnabled = isLocationEnabled()
        val playServicesAvailable = isPlayServicesAvailable()
        Diagnostics.info(
            "geofence_reconcile_start",
            mapOf(
                "attemptId" to attemptId,
                "trigger" to trigger,
                "enabled" to settings.geofencePresenceEnabled,
                "configured" to settings.hasValidGeofence,
                "finePermission" to finePermissionGranted,
                "backgroundPermission" to backgroundPermissionGranted,
                "locationEnabled" to locationEnabled,
                "playServicesAvailable" to playServicesAvailable,
                "previousPresence" to previousPresence.state,
                "stateAge" to geofenceStateAgeBucket(previousPresence),
            ),
        )
        val result = when {
            !settings.geofencePresenceEnabled -> {
                removeGeofenceSafely()
                presenceStore.clear()
                mutableRegistrationHealth.value = GeofenceRegistrationHealth.NOT_CONFIGURED
                GeofenceRegistrationResult("removed", "removed")
            }
            !settings.hasValidGeofence -> unavailable("not_configured")
            !finePermissionGranted || !backgroundPermissionGranted -> unavailable("permission_denied")
            !locationEnabled -> unavailable("location_disabled")
            !playServicesAvailable -> unavailable("play_services_unavailable", retryable = true)
            else -> register(settings.homeLatitude!!, settings.homeLongitude!!,
                settings.homeGeofenceRadiusMeters)
        }
        Diagnostics.info(
            "geofence_registration",
            mapOf(
                "attemptId" to attemptId,
                "trigger" to trigger,
                "outcome" to result.outcome,
                "reason" to result.reason,
                "previousPresence" to previousPresence.state,
                "stateAge" to geofenceStateAgeBucket(previousPresence),
                "initialTrigger" to if (result.outcome == "registered") "enter_exit" else null,
            ),
        )
        return result
    }

    private suspend fun register(
        latitude: Double,
        longitude: Double,
        radiusMeters: Float,
    ): GeofenceRegistrationResult {
        val geofence = Geofence.Builder()
            .setRequestId(GEOFENCE_REQUEST_ID)
            .setCircularRegion(latitude, longitude, radiusMeters)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT,
            )
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(
                GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_EXIT,
            )
            .addGeofence(geofence)
            .build()
        return try {
            geofencingClient.addGeofences(request, transitionPendingIntent()).awaitCompletion()
            mutableRegistrationHealth.value = GeofenceRegistrationHealth.REGISTERED
            GeofenceRegistrationResult("registered", "registered")
        } catch (error: CancellationException) {
            throw error
        } catch (_: SecurityException) {
            unavailable("permission_denied")
        } catch (error: ApiException) {
            unavailable(safeGeofenceApiReason(error.statusCode), retryable = true)
        } catch (_: Exception) {
            unavailable("api_error", retryable = true)
        }
    }

    private fun unavailable(reason: String, retryable: Boolean = false): GeofenceRegistrationResult {
        presenceStore.clear()
        mutableRegistrationHealth.value = GeofenceRegistrationHealth.UNAVAILABLE
        return GeofenceRegistrationResult("unavailable", reason, retryable)
    }

    private suspend fun removeGeofenceSafely() {
        try {
            geofencingClient.removeGeofences(transitionPendingIntent()).awaitCompletion()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Removal is idempotent. A later reconciliation will retry if needed.
        }
    }

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    private fun hasBackgroundLocationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    private fun isLocationEnabled(): Boolean =
        appContext.getSystemService(LocationManager::class.java)?.isLocationEnabled == true

    private fun isPlayServicesAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) ==
            ConnectionResult.SUCCESS

    private fun transitionPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        TRANSITION_REQUEST_CODE,
        Intent(appContext, GeofenceBroadcastReceiver::class.java).setAction(ACTION_GEOFENCE_TRANSITION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private companion object {
        const val TRANSITION_REQUEST_CODE = 7101
    }
}

internal fun safeGeofenceApiReason(statusCode: Int): String =
    if (statusCode == com.google.android.gms.location.GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
        "not_available"
    } else {
        "api_error"
    }

private suspend fun Task<*>.awaitCompletion() = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(Unit) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}

const val ACTION_GEOFENCE_TRANSITION = "com.ringautopilot.app.action.GEOFENCE_TRANSITION"

internal fun geofenceStateAgeBucket(
    presence: GeofencePresence,
    nowEpochMillis: Long = System.currentTimeMillis(),
): String {
    if (presence.updatedAtEpochMillis == null) return "never"
    val ageMillis = (nowEpochMillis - presence.updatedAtEpochMillis).coerceAtLeast(0L)
    return when {
        ageMillis < 60_000L -> "under_1m"
        ageMillis < 15 * 60_000L -> "1m_15m"
        ageMillis < 60 * 60_000L -> "15m_1h"
        else -> "over_1h"
    }
}
