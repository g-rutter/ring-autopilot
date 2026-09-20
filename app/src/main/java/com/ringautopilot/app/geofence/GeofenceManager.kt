package com.ringautopilot.app.geofence

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import com.ringautopilot.app.logging.Diagnostics
import com.ringautopilot.app.logging.operationId
import com.ringautopilot.app.model.AutomationSettings
import com.ringautopilot.app.model.PresenceState
import com.ringautopilot.app.storage.SettingsRepository
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine

data class GeofenceRegistrationResult(
    val outcome: String,
    val reason: String,
    val retryable: Boolean = false,
)

interface GeofenceManager {
    val registrationHealth: StateFlow<GeofenceRegistration>
    suspend fun reconcile(trigger: String = "unspecified", force: Boolean = false): GeofenceRegistrationResult
}

class AndroidGeofenceManager(
    context: Context,
    private val settingsRepository: SettingsRepository,
    private val presenceStore: GeofencePresenceStore,
    private val registrationStore: GeofenceRegistrationStore,
) : GeofenceManager {
    private val appContext = context.applicationContext
    private val geofencingClient = LocationServices.getGeofencingClient(appContext)
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(appContext)

    override val registrationHealth: StateFlow<GeofenceRegistration> = registrationStore.registration

    override suspend fun reconcile(trigger: String, force: Boolean): GeofenceRegistrationResult =
        reconciliationMutex.withLock { reconcileLocked(trigger, force) }

    private suspend fun reconcileLocked(trigger: String, force: Boolean): GeofenceRegistrationResult {
        val settings = settingsRepository.settings.value
        val generation = settings.geofenceDefinitionGeneration
        val previousPresence = presenceStore.presence.value
        val previousRegistration = registrationStore.registration.value
        val attemptId = operationId()
        val now = System.currentTimeMillis()
        val finePermissionGranted = hasFineLocationPermission()
        val backgroundPermissionGranted = hasBackgroundLocationPermission()
        val locationEnabled = isLocationEnabled()
        val playServicesAvailable = isPlayServicesAvailable()
        Diagnostics.info("geofence_reconcile_start", mapOf(
            "attemptId" to attemptId, "trigger" to trigger,
            "enabled" to settings.geofencePresenceEnabled,
            "configured" to settings.hasValidGeofence,
            "finePermission" to finePermissionGranted,
            "backgroundPermission" to backgroundPermissionGranted,
            "locationEnabled" to locationEnabled,
            "playServicesAvailable" to playServicesAvailable,
            "previousPresence" to previousPresence.state,
            "stateAge" to geofenceStateAgeBucket(previousPresence),
            "definitionGeneration" to generation,
            "registeredGeneration" to previousRegistration.registeredGeneration,
        ))

        val result = when {
            !settings.geofencePresenceEnabled -> {
                removeGeofenceSafely()
                presenceStore.clearForGeneration(generation)
                saveRegistration(generation, null, GeofenceRegistrationHealth.UNREGISTERED,
                    "removed", "disabled", now, null)
                GeofenceRegistrationResult("removed", "disabled")
            }
            !settings.hasValidGeofence -> unavailable(generation, "not_configured", now)
            !finePermissionGranted || !backgroundPermissionGranted ->
                unavailable(generation, "permission_denied", now)
            !locationEnabled -> unavailable(generation, "location_disabled", now)
            !playServicesAvailable -> unavailable(generation, "play_services_unavailable", now, true)
            else -> reconcileRegistration(settings, generation, previousRegistration, force, now)
        }
        Diagnostics.info("geofence_registration", mapOf(
            "attemptId" to attemptId, "trigger" to trigger,
            "outcome" to result.outcome, "reason" to result.reason,
            "previousPresence" to previousPresence.state,
            "stateAge" to geofenceStateAgeBucket(previousPresence),
            "definitionGeneration" to generation,
            "registeredGeneration" to registrationStore.registration.value.registeredGeneration,
        ))
        return result
    }

    private suspend fun reconcileRegistration(
        settings: AutomationSettings,
        generation: Long,
        prior: GeofenceRegistration,
        force: Boolean,
        now: Long,
    ): GeofenceRegistrationResult {
        if (presenceStore.presence.value.definitionGeneration != generation) {
            presenceStore.clearForGeneration(generation)
        }
        val action = registrationAction(prior, generation, force)
        val registrationResult = if (action == RegistrationAction.NOOP) {
            saveRegistration(generation, generation, GeofenceRegistrationHealth.REGISTERED,
                "noop", "already_registered", now, prior.lastSuccessAtEpochMillis)
            GeofenceRegistrationResult("noop", "already_registered")
        } else {
            saveRegistration(generation, prior.registeredGeneration,
                GeofenceRegistrationHealth.REGISTERING, "pending", "registration", now,
                prior.lastSuccessAtEpochMillis)
            if (action == RegistrationAction.REPLACE &&
                !removeGeofenceSafely()) {
                presenceStore.clearForGeneration(generation)
                saveRegistration(generation, prior.registeredGeneration,
                    GeofenceRegistrationHealth.UNAVAILABLE, "unavailable", "remove_failed",
                    now, prior.lastSuccessAtEpochMillis)
                return GeofenceRegistrationResult("unavailable", "remove_failed", true)
            }
            register(settings, generation, now)
        }
        if (registrationResult.outcome != "registered" && registrationResult.outcome != "noop") {
            return registrationResult
        }
        if (presenceStore.presence.value.let {
                it.definitionGeneration == generation && it.state != PresenceState.UNKNOWN
            }) return registrationResult

        val initial = reconcileInitialPresence(settings, generation)
        return when (initial) {
            "home", "away", "stale_generation" -> registrationResult
            else -> registrationResult.copy(reason = initial, retryable = true)
        }
    }

    private suspend fun register(
        settings: AutomationSettings,
        generation: Long,
        now: Long,
    ): GeofenceRegistrationResult {
        val geofence = Geofence.Builder()
            .setRequestId(geofenceRequestId(generation))
            .setCircularRegion(settings.homeLatitude!!, settings.homeLongitude!!,
                settings.homeGeofenceRadiusMeters)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or
                GeofencingRequest.INITIAL_TRIGGER_EXIT)
            .addGeofence(geofence)
            .build()
        return try {
            geofencingClient.addGeofences(request, transitionPendingIntent()).awaitCompletion()
            saveRegistration(generation, generation, GeofenceRegistrationHealth.REGISTERED,
                "registered", "registered", now, System.currentTimeMillis())
            GeofenceRegistrationResult("registered", "registered")
        } catch (error: CancellationException) {
            throw error
        } catch (_: SecurityException) {
            unavailable(generation, "permission_denied", now)
        } catch (error: ApiException) {
            unavailable(generation, safeGeofenceApiReason(error.statusCode), now, true)
        } catch (_: Exception) {
            unavailable(generation, "api_error", now, true)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun reconcileInitialPresence(
        settings: AutomationSettings,
        generation: Long,
    ): String {
        val started = System.currentTimeMillis()
        val location = try {
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token,
            ).awaitValue()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        val outcome = if (location == null) {
            "location_unavailable"
        } else {
            val distance = FloatArray(1)
            Location.distanceBetween(settings.homeLatitude!!, settings.homeLongitude!!,
                location.latitude, location.longitude, distance)
            classifyInitialGeofencePresence(
                distanceMeters = distance[0], accuracyMeters = location.accuracy,
                radiusMeters = settings.homeGeofenceRadiusMeters,
                ageMillis = (System.currentTimeMillis() - location.time).coerceAtLeast(0L),
            )
        }
        val committedOutcome = when (outcome) {
            "home" -> if (presenceStore.updateInitialIfUnknown(
                PresenceState.HOME, generation, started)) "home" else "stale_generation"
            "away" -> if (presenceStore.updateInitialIfUnknown(
                PresenceState.AWAY, generation, started)) "away" else "stale_generation"
            else -> outcome
        }
        Diagnostics.info("geofence_initial_reconciliation", mapOf(
            "definitionGeneration" to generation,
            "outcome" to committedOutcome,
            "presenceSource" to "initial_reconciliation",
        ))
        return committedOutcome
    }

    private suspend fun unavailable(
        generation: Long,
        reason: String,
        now: Long,
        retryable: Boolean = false,
    ): GeofenceRegistrationResult {
        removeGeofenceSafely()
        presenceStore.clearForGeneration(generation)
        saveRegistration(generation, null, GeofenceRegistrationHealth.UNAVAILABLE,
            "unavailable", reason, now, null)
        return GeofenceRegistrationResult("unavailable", reason, retryable)
    }

    private fun saveRegistration(
        generation: Long,
        registeredGeneration: Long?,
        health: GeofenceRegistrationHealth,
        outcome: String,
        reason: String,
        attemptedAt: Long?,
        succeededAt: Long?,
    ) = registrationStore.update(GeofenceRegistration(
        generation, registeredGeneration, health, outcome, reason, attemptedAt, succeededAt,
    ))

    private suspend fun removeGeofenceSafely(): Boolean = try {
        geofencingClient.removeGeofences(transitionPendingIntent()).awaitCompletion()
        true
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    private fun hasFineLocationPermission() = ContextCompat.checkSelfPermission(
        appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun hasBackgroundLocationPermission() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    private fun isLocationEnabled() =
        appContext.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
    private fun isPlayServicesAvailable() = GoogleApiAvailability.getInstance()
        .isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS
    private fun transitionPendingIntent() = PendingIntent.getBroadcast(
        appContext, TRANSITION_REQUEST_CODE,
        Intent(appContext, GeofenceBroadcastReceiver::class.java).setAction(ACTION_GEOFENCE_TRANSITION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private companion object {
        const val TRANSITION_REQUEST_CODE = 7101
        val reconciliationMutex = Mutex()
    }
}

internal enum class RegistrationAction { NOOP, ADD, REPLACE }

internal fun registrationAction(
    registration: GeofenceRegistration,
    generation: Long,
    force: Boolean,
): RegistrationAction = when {
    registration.registeredGeneration != null &&
        registration.registeredGeneration != generation -> RegistrationAction.REPLACE
    !force && registration.health == GeofenceRegistrationHealth.REGISTERED &&
        registration.registeredGeneration == generation -> RegistrationAction.NOOP
    else -> RegistrationAction.ADD
}

internal fun classifyInitialGeofencePresence(
    distanceMeters: Float,
    accuracyMeters: Float,
    radiusMeters: Float,
    ageMillis: Long,
): String {
    if (!distanceMeters.isFinite() || !accuracyMeters.isFinite() || accuracyMeters <= 0f ||
        ageMillis > 2 * 60_000L) return "location_unavailable"
    if (accuracyMeters > radiusMeters) return "uncertain_accuracy"
    if (distanceMeters + accuracyMeters <= radiusMeters) return "home"
    val hysteresis = maxOf(25f, radiusMeters * 0.1f)
    if (distanceMeters - accuracyMeters >= radiusMeters + hysteresis) return "away"
    return "uncertain_accuracy"
}

internal fun geofenceRequestId(generation: Long) = "home_geofence_v$generation"
internal fun requestGeneration(requestId: String): Long? =
    requestId.takeIf { it.startsWith(GEOFENCE_REQUEST_ID_PREFIX) }
        ?.removePrefix(GEOFENCE_REQUEST_ID_PREFIX)?.toLongOrNull()

internal fun safeGeofenceApiReason(statusCode: Int): String =
    if (statusCode == com.google.android.gms.location.GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE)
        "not_available" else "api_error"

private suspend fun Task<*>.awaitCompletion() = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(Unit) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}

private suspend fun <T> Task<T>.awaitValue(): T? = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resume(null) }
    addOnCanceledListener { continuation.cancel() }
}

const val ACTION_GEOFENCE_TRANSITION = "com.ringautopilot.app.action.GEOFENCE_TRANSITION"
const val GEOFENCE_REQUEST_ID_PREFIX = "home_geofence_v"

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
