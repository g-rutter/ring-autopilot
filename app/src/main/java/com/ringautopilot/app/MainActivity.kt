package com.ringautopilot.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
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
}

@Composable
private fun RingAutopilotApp(container: AppContainer) {
    val viewModel: StatusViewModel = viewModel(
        factory = StatusViewModelFactory(container),
    )
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { viewModel.refreshPresence() },
    )

    StatusScreen(
        viewModel = viewModel,
        requestPermissions = {
            val permissions = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.NEARBY_WIFI_DEVICES)
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            permissionLauncher.launch(permissions.toTypedArray())
        },
    )
}
