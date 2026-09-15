package com.ringautopilot.app

import android.Manifest
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ringautopilot.app.presence.PresenceMonitoringService
import com.ringautopilot.app.ui.StatusScreen
import com.ringautopilot.app.ui.StatusViewModel
import com.ringautopilot.app.ui.StatusViewModelFactory
import com.ringautopilot.app.ui.theme.RingAutopilotTheme

class MainActivity : ComponentActivity() {
    private val container by lazy { AppContainer(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RingAutopilotTheme {
                RingAutopilotApp(container)
            }
        }
    }

    override fun onStart() {
        stopService(Intent(this, PresenceMonitoringService::class.java))
        super.onStart()
    }

    override fun onStop() {
        // Starting while the Activity is visible is permitted; the service then owns
        // monitoring after the user leaves the app.
        ContextCompat.startForegroundService(
            this,
            Intent(this, PresenceMonitoringService::class.java),
        )
        super.onStop()
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
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.startAutomation()
                Lifecycle.Event.ON_STOP -> viewModel.stopAutomation()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var useWifiAfterPermissions by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { result ->
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

    StatusScreen(
        viewModel = viewModel,
        requestPermissions = {
            val missing = missingPermissions(context, includeNotifications = true)
            when {
                missing.isNotEmpty() -> permissionLauncher.launch(missing.toTypedArray())
                !isLocationEnabled(context) -> context.startActivity(
                    Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                )
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
                permissionLauncher.launch(
                    missingPermissions(context, includeNotifications = false).toTypedArray(),
                )
            }
        },
    )
}

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
