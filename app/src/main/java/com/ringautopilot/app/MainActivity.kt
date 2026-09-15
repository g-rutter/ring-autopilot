package com.ringautopilot.app

import android.Manifest
import android.content.Intent
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
                val locationGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
                val nearbyGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    result[Manifest.permission.NEARBY_WIFI_DEVICES] == true
                val message = if (locationGranted && nearbyGranted) {
                    viewModel.useCurrentWifi()
                } else {
                    "Wi-Fi name access was denied. Allow Precise location and Nearby devices, then try again."
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                useWifiAfterPermissions = false
            }
        },
    )

    StatusScreen(
        viewModel = viewModel,
        requestPermissions = {
            launchWifiPermissions(permissionLauncher, includeNotifications = true)
        },
        useCurrentWifi = {
            useWifiAfterPermissions = true
            launchWifiPermissions(permissionLauncher, includeNotifications = false)
        },
    )
}

private fun launchWifiPermissions(
    launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    includeNotifications: Boolean,
) {
    val permissions = buildList {
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            if (includeNotifications) add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    launcher.launch(permissions.toTypedArray())
}
