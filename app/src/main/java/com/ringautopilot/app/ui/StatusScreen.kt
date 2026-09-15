package com.ringautopilot.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ringautopilot.app.automation.AutomationStatus
import com.ringautopilot.app.model.RingMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    viewModel: StatusViewModel,
    requestPermissions: () -> Unit,
    useCurrentWifi: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var setupRequested by remember { mutableStateOf(false) }
    val showSetup = setupRequested || !state.isSetupComplete

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (showSetup) "Set up Ring Autopilot" else "Ring Autopilot") },
                actions = {
                    if (!showSetup) TextButton(onClick = { setupRequested = true }) { Text("Setup") }
                    if (showSetup && state.isSetupComplete) {
                        TextButton(onClick = { setupRequested = false }) { Text("Done") }
                    }
                },
            )
        },
    ) { padding ->
        if (showSetup) {
            SetupPage(
                state = state,
                viewModel = viewModel,
                requestPermissions = requestPermissions,
                useCurrentWifi = useCurrentWifi,
                onDone = { setupRequested = false },
                modifier = Modifier.padding(padding),
            )
        } else {
            DashboardPage(state, viewModel, Modifier.padding(padding))
        }
    }
}

@Composable
private fun DashboardPage(state: StatusUiState, viewModel: StatusViewModel, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ModeHero(state)
        Text(
            "Presence: ${state.presence.displayName()}  •  ${automationSummary(state.automationStatus)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { viewModel.setRingMode(RingMode.AWAY) }, enabled = !state.ringOperationInProgress) {
                Text("Away")
            }
            FilledTonalButton(onClick = { viewModel.setRingMode(RingMode.DISARMED) }, enabled = !state.ringOperationInProgress) {
                Text("Disarm")
            }
            OutlinedButton(onClick = viewModel::refreshRingMode, enabled = !state.ringOperationInProgress) {
                Text("Check")
            }
        }
        Text(state.ringValidationMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "Automatic changes use your home Wi-Fi after the safety delay.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModeHero(state: StatusUiState) {
    val (headline, label, colors) = when {
        state.ringConnectionFailed -> Triple("CAN'T CONNECT", "Ring status is unavailable", CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer))
        else -> when (state.ringMode) {
            RingMode.DISARMED -> Triple("DISARMED", "Ring is not armed", CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer))
            RingMode.AWAY -> Triple("AWAY", "Ring is armed", CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer))
            RingMode.UNKNOWN, RingMode.UNAVAILABLE -> Triple("NOT CHECKED", "Check Ring to get its current status", CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant))
        }
    }
    Card(modifier = Modifier.fillMaxWidth(), colors = colors) {
        Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(headline, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SetupPage(
    state: StatusUiState,
    viewModel: StatusViewModel,
    requestPermissions: () -> Unit,
    useCurrentWifi: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier,
) {
    var ssid by remember(state.homeWifiSsid) { mutableStateOf(state.homeWifiSsid) }
    var refreshToken by remember { mutableStateOf("") }
    var locationId by remember(state.ringLocationId) { mutableStateOf(state.ringLocationId) }
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            if (state.isSetupComplete) "Update the connection and home-presence settings."
            else "Connect Ring and choose the Wi-Fi network that means you are home.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text("Home presence", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = ssid, onValueChange = { ssid = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Home Wi-Fi name (SSID)") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = requestPermissions) { Text("Permissions") }
            FilledTonalButton(onClick = useCurrentWifi) { Text("Use current Wi-Fi") }
        }
        Text("Reading Wi-Fi requires Precise location permission and Location services.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Ring connection", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = refreshToken, onValueChange = { refreshToken = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Ring refresh token") }, supportingText = { Text("Generated with ring-auth-cli; encrypted on this device.") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true,
        )
        OutlinedTextField(
            value = locationId, onValueChange = { locationId = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Ring location ID (optional)") }, supportingText = { Text("Leave blank to use the first account location.") }, singleLine = true,
        )
        Button(
            onClick = {
                viewModel.saveHomeWifiSsid(ssid)
                if (refreshToken.isNotBlank()) {
                    viewModel.saveRingCredentials(refreshToken, locationId)
                    refreshToken = ""
                } else if (locationId != state.ringLocationId) {
                    viewModel.saveRingLocationId(locationId)
                }
                onDone()
            },
            enabled = ssid.isNotBlank(), modifier = Modifier.fillMaxWidth(),
        ) { Text(if (state.isSetupComplete) "Save changes" else "Finish setup") }
        Spacer(Modifier.height(4.dp))
        Text("Monitoring continues in the background. Android battery settings may need an exception.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun automationSummary(status: AutomationStatus): String = when (status) {
    AutomationStatus.Idle -> "Automation ready"
    is AutomationStatus.Waiting -> "Switching to ${status.desiredMode.displayName()} in ${formatDuration(status.delaySeconds)}"
    is AutomationStatus.Switching -> "Switching to ${status.desiredMode.displayName()}"
    is AutomationStatus.Retrying -> "Retry ${status.attempt + 1}/${status.maxAttempts} in ${formatDuration(status.delaySeconds)}"
    is AutomationStatus.Failed -> "Automation needs attention"
}

private fun formatDuration(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

private fun Enum<*>.displayName(): String = name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
