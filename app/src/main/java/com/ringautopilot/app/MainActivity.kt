package com.ringautopilot.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ringautopilot.app.automation.MonitoringWorkScheduler
import com.ringautopilot.app.logging.Diagnostics
import com.ringautopilot.app.ui.StatusScreen
import com.ringautopilot.app.ui.StatusViewModel
import com.ringautopilot.app.ui.StatusViewModelFactory
import com.ringautopilot.app.ui.theme.RingAutopilotTheme

class MainActivity : ComponentActivity() {
    private val container by lazy { AppContainer(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diagnostics.info("diagnostic_session", mapOf("version" to packageManager.getPackageInfo(packageName, 0).versionName))
        MonitoringWorkScheduler.schedule(this)
        setContent {
            RingAutopilotTheme {
                RingAutopilotApp(container)
            }
        }
    }

}

@Composable
private fun RingAutopilotApp(container: AppContainer) {
    val viewModel: StatusViewModel = viewModel(
        factory = StatusViewModelFactory(container),
    )
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var lastToast by remember { mutableStateOf<Toast?>(null) }
    var useWifiAfterPermissions by remember { mutableStateOf(false) }
    var backgroundLocationGranted by remember { mutableStateOf(hasBackgroundLocation(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    backgroundLocationGranted = hasBackgroundLocation(context)
                    Diagnostics.info("location_services", mapOf("enabled" to isLocationEnabled(context), "backgroundGranted" to backgroundLocationGranted))
                    viewModel.refreshLastCheck()
                    viewModel.startAutomation()
                }
                Lifecycle.Event.ON_STOP -> viewModel.stopAutomation()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { result ->
            result.forEach { (permission, granted) ->
                Diagnostics.info("permission_result", mapOf("permission" to permission.substringAfterLast('.'), "granted" to granted))
            }
            viewModel.refreshPresence()
            if (useWifiAfterPermissions) {
                val locationGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val message = if (locationGranted) {
                    viewModel.useCurrentWifi()
                } else {
                    "Wi-Fi name access was denied. Allow Precise location, then try again."
                }
                lastToast?.cancel()
                lastToast = Toast.makeText(context, message, Toast.LENGTH_LONG).also { it.show() }
                useWifiAfterPermissions = false
            } else {
                val message = if (missingPermissions(context, includeNotifications = true).isEmpty()) {
                    "All required permissions are granted."
                } else {
                    "Some permissions were denied. You can allow them in Android app settings."
                }
                lastToast?.cancel()
                lastToast = Toast.makeText(context, message, Toast.LENGTH_LONG).also { it.show() }
            }
        },
    )
    val backgroundPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            backgroundLocationGranted = hasBackgroundLocation(context)
            Diagnostics.info("permission_result", mapOf("permission" to "ACCESS_BACKGROUND_LOCATION", "granted" to granted))
        },
    )

    StatusScreen(
        viewModel = viewModel,
        requestPermissions = {
            val missing = missingPermissions(context, includeNotifications = true)
            when {
                missing.isNotEmpty() -> {
                    missing.forEach { Diagnostics.info("permission_request", mapOf("permission" to it.substringAfterLast('.'))) }
                    permissionLauncher.launch(missing.toTypedArray())
                }
                !isLocationEnabled(context) -> {
                    Diagnostics.warn("location_services", mapOf("enabled" to false))
                    context.startActivity(
                    Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                    )
                }
                else -> {
                    lastToast?.cancel()
                    lastToast = Toast.makeText(
                        context,
                        "All required permissions are already granted.",
                        Toast.LENGTH_LONG,
                    ).also { it.show() }
                }
            }
        },
        useCurrentWifi = {
            if (hasWifiPermissions(context)) {
                lastToast?.cancel()
                lastToast = Toast.makeText(
                    context,
                    viewModel.useCurrentWifi(),
                    Toast.LENGTH_LONG,
                ).also { it.show() }
            } else {
                useWifiAfterPermissions = true
                val missing = missingPermissions(context, includeNotifications = false)
                missing.forEach { Diagnostics.info("permission_request", mapOf("permission" to it.substringAfterLast('.'))) }
                permissionLauncher.launch(missing.toTypedArray())
            }
        },
        backgroundLocationGranted = backgroundLocationGranted,
        requestBackgroundLocation = {
            if (!hasWifiPermissions(context)) {
                val missing = missingPermissions(context, includeNotifications = false)
                missing.forEach { Diagnostics.info("permission_request", mapOf("permission" to it.substringAfterLast('.'))) }
                permissionLauncher.launch(missing.toTypedArray())
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Diagnostics.info("permission_request", mapOf("permission" to "ACCESS_BACKGROUND_LOCATION", "via" to "settings"))
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                })
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Diagnostics.info("permission_request", mapOf("permission" to "ACCESS_BACKGROUND_LOCATION"))
                backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        },
    )
}

private fun hasBackgroundLocation(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

private fun hasWifiPermissions(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

private fun missingPermissions(
    context: android.content.Context,
    includeNotifications: Boolean,
): List<String> = buildList {
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (includeNotifications) add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    .filter {
        ContextCompat.checkSelfPermission(context, it) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

private fun isLocationEnabled(context: android.content.Context): Boolean =
    context.getSystemService(LocationManager::class.java).isLocationEnabled
