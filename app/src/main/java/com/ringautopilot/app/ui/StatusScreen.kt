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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.ringautopilot.app.model.ControlMode
import com.ringautopilot.app.model.PresenceState
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
            homeStatus(state),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Control mode", style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ControlMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = state.controlMode == mode,
                    onClick = { viewModel.selectControlMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = ControlMode.entries.size,
                    ),
                    enabled = !state.ringOperationInProgress,
                    label = { Text(mode.controlLabel()) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(
                onClick = viewModel::syncNow,
                enabled = !state.ringOperationInProgress,
            ) { Text("Sync now") }
            OutlinedButton(
                onClick = viewModel::refreshRingMode,
                enabled = !state.ringOperationInProgress,
            ) { Text("Check Ring") }
        }
        state.ringValidationMessage?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModeHero(state: StatusUiState) {
    val (headline, containerColor) = when {
        state.ringConnectionFailed -> Pair("CAN'T CONNECT", MaterialTheme.colorScheme.errorContainer)
        else -> when (state.ringMode) {
            RingMode.DISARMED -> Pair("DISARMED", MaterialTheme.colorScheme.primaryContainer)
            RingMode.AWAY -> Pair("AWAY", MaterialTheme.colorScheme.tertiaryContainer)
            RingMode.UNKNOWN, RingMode.UNAVAILABLE -> Pair("NOT CHECKED", MaterialTheme.colorScheme.surfaceVariant)
        }
    }
    androidx.compose.material3.Surface(
        color = containerColor,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
    ) {
        Text(
            headline,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
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
        Text("Monitoring runs periodically in the background. Android may defer checks to preserve battery.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun homeStatus(state: StatusUiState): String {
    val atHome = state.presence == PresenceState.HOME
    val pendingChange = state.automationStatus as? AutomationStatus.Waiting
    val timer = if (state.controlMode == ControlMode.AUTO && pendingChange != null) {
        " • Switching to ${pendingChange.desiredMode.displayName()} in ${formatDuration(pendingChange.delaySeconds)}"
    } else {
        ""
    }
    return "Home: ${if (atHome) "✓" else "✗"}$timer"
}

private fun formatDuration(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

private fun Enum<*>.displayName(): String = name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)

private fun ControlMode.controlLabel(): String = when (this) {
    ControlMode.AUTO -> "Auto"
    ControlMode.AWAY -> "Away"
    ControlMode.DISARMED -> "Disarm"
}
