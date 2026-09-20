package com.ringautopilot.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.ringautopilot.app.automation.MonitoringWorkScheduler
import com.ringautopilot.app.geofence.GeofenceWorkScheduler
import com.ringautopilot.app.logging.Diagnostics
import com.ringautopilot.app.ui.StatusScreen
import com.ringautopilot.app.ui.StatusViewModel
import com.ringautopilot.app.ui.StatusViewModelFactory
import com.ringautopilot.app.ui.theme.RingAutopilotTheme

class MainActivity : ComponentActivity() {
    private val container by lazy { AppContainer(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diagnostics.info("diagnostic_session", mapOf(
            "version" to packageManager.getPackageInfo(packageName, 0).versionName))
        MonitoringWorkScheduler.schedule(this)
        GeofenceWorkScheduler.scheduleRegistration(this, "app_start")
        setContent { RingAutopilotTheme { RingAutopilotApp(container) } }
    }
}

@Composable
private fun RingAutopilotApp(container: AppContainer) {
    val viewModel: StatusViewModel = viewModel(factory = StatusViewModelFactory(container))
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var foregroundLocationGranted by remember { mutableStateOf(hasFineLocation(context)) }
    var backgroundLocationGranted by remember { mutableStateOf(hasBackgroundLocation(context)) }
    var notificationGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var locationEnabled by remember { mutableStateOf(isLocationEnabled(context)) }
    var selectWifiAfterPermission by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var lastToast by remember { mutableStateOf<Toast?>(null) }

    fun toast(message: String) {
        lastToast?.cancel()
        lastToast = Toast.makeText(context, message, Toast.LENGTH_LONG).also { it.show() }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    foregroundLocationGranted = hasFineLocation(context)
                    backgroundLocationGranted = hasBackgroundLocation(context)
                    notificationGranted = hasNotificationPermission(context)
                    locationEnabled = isLocationEnabled(context)
                    Diagnostics.info("location_services", mapOf(
                        "enabled" to locationEnabled,
                        "backgroundGranted" to backgroundLocationGranted,
                    ))
                    viewModel.refreshLastCheck()
                    viewModel.reconcileGeofence()
                    viewModel.startAutomation()
                }
                Lifecycle.Event.ON_STOP -> viewModel.stopAutomation()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val foregroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        result.forEach { (permission, granted) ->
            Diagnostics.info("permission_result", mapOf(
                "permission" to permission.substringAfterLast('.'), "granted" to granted))
        }
        foregroundLocationGranted = hasFineLocation(context)
        viewModel.refreshPresence()
        selectWifiAfterPermission?.let { onSsid ->
            val ssid = if (foregroundLocationGranted) viewModel.currentWifiSsid() else null
            if (ssid == null) {
                toast("Could not read the connected Wi-Fi. Allow Precise location and turn on Location services.")
            } else {
                onSsid(ssid)
                toast("Current Wi-Fi selected.")
            }
            selectWifiAfterPermission = null
        }
    }
    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        backgroundLocationGranted = hasBackgroundLocation(context)
        Diagnostics.info("permission_result", mapOf(
            "permission" to "ACCESS_BACKGROUND_LOCATION", "granted" to granted))
        viewModel.reconcileGeofence()
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationGranted = hasNotificationPermission(context)
        Diagnostics.info("permission_result", mapOf(
            "permission" to "POST_NOTIFICATIONS", "granted" to granted))
    }

    val requestForegroundLocation = {
        if (foregroundLocationGranted) {
            if (!locationEnabled) context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        } else {
            val permissions = arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
            permissions.forEach { Diagnostics.info("permission_request", mapOf(
                "permission" to it.substringAfterLast('.'))) }
            foregroundLauncher.launch(permissions)
        }
    }

    StatusScreen(
        viewModel = viewModel,
        foregroundLocationGranted = foregroundLocationGranted,
        backgroundLocationGranted = backgroundLocationGranted,
        locationServicesEnabled = locationEnabled,
        notificationGranted = notificationGranted,
        requestForegroundLocation = requestForegroundLocation,
        useCurrentWifi = { onSsid ->
            if (foregroundLocationGranted) {
                val ssid = viewModel.currentWifiSsid()
                if (ssid == null) toast("Could not read the connected Wi-Fi. Check Precise location and Location services.")
                else {
                    onSsid(ssid)
                    toast("Current Wi-Fi selected.")
                }
            }
            else {
                selectWifiAfterPermission = onSsid
                requestForegroundLocation()
            }
        },
        requestBackgroundLocation = {
            when {
                !foregroundLocationGranted -> requestForegroundLocation()
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    Diagnostics.info("permission_request", mapOf(
                        "permission" to "ACCESS_BACKGROUND_LOCATION", "via" to "settings"))
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    })
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    Diagnostics.info("permission_request", mapOf(
                        "permission" to "ACCESS_BACKGROUND_LOCATION"))
                    backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            }
        },
        requestNotificationPermission = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Diagnostics.info("permission_request", mapOf("permission" to "POST_NOTIFICATIONS"))
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        requestCurrentLocation = { onLocation ->
            if (!foregroundLocationGranted) {
                requestForegroundLocation()
                toast("Grant Precise location, then tap Use current location again.")
            } else if (!locationEnabled) {
                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } else {
                readCurrentLocation(context, onLocation) {
                    toast("Current location is not available yet. Try again outdoors or after opening Maps.")
                }
            }
        },
    )
}

@SuppressLint("MissingPermission")
private fun readCurrentLocation(
    context: Context,
    onLocation: (Double, Double) -> Unit,
    onUnavailable: () -> Unit,
) {
    val cancellation = CancellationTokenSource()
    LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(
        Priority.PRIORITY_HIGH_ACCURACY,
        cancellation.token,
    )
        .addOnSuccessListener { location ->
            if (location == null) onUnavailable() else onLocation(location.latitude, location.longitude)
        }
        .addOnFailureListener { onUnavailable() }
}

private fun hasFineLocation(context: Context): Boolean = ContextCompat.checkSelfPermission(
    context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun hasBackgroundLocation(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(
        context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun isLocationEnabled(context: Context): Boolean =
    context.getSystemService(LocationManager::class.java).isLocationEnabled
