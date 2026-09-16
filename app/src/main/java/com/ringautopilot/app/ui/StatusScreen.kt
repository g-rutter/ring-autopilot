package com.ringautopilot.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
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
    backgroundLocationGranted: Boolean,
    requestBackgroundLocation: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var setupRequested by remember { mutableStateOf(false) }
    val showSetup = setupRequested || !state.isSetupComplete

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (showSetup) "Set up" else "Ring Autopilot", fontWeight = FontWeight.SemiBold) },
                actions = {
                    if (!showSetup) TextButton(onClick = { setupRequested = true }) { Text("Settings") }
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
                backgroundLocationGranted = backgroundLocationGranted,
                requestBackgroundLocation = requestBackgroundLocation,
                onDone = { setupRequested = false },
                modifier = Modifier.padding(padding),
            )
        } else {
            DashboardPage(state, viewModel, backgroundLocationGranted,
                requestBackgroundLocation, Modifier.padding(padding))
        }
    }
}

@Composable
private fun DashboardPage(
    state: StatusUiState,
    viewModel: StatusViewModel,
    backgroundLocationGranted: Boolean,
    requestBackgroundLocation: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ModeHero(state)
        if (state.controlMode == ControlMode.AUTO && !backgroundLocationGranted) {
            Text("Auto needs Location set to Allow all the time to identify home Wi-Fi while the app is closed.")
            OutlinedButton(onClick = requestBackgroundLocation) {
                Text("Open app permissions")
            }
        }
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("CONTROL", style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(if (state.controlMode == ControlMode.AUTO) "Automatic" else "Manual",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(if (state.controlMode == ControlMode.AUTO) "Follows home Wi-Fi" else "Set until you choose Auto",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ControlMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(selected = state.controlMode == mode,
                            onClick = { viewModel.selectControlMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = ControlMode.entries.size),
                            enabled = !state.ringOperationInProgress,
                            label = { Text(mode.controlLabel()) })
                    }
                }
            }
        }
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("AUTOMATION", style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(homeStatus(state), style = MaterialTheme.typography.bodyMedium)
                val pending = state.automationStatus as? AutomationStatus.Waiting
                if (state.controlMode == ControlMode.AUTO && pending != null) {
                    Text("Switching to ${pending.desiredMode.displayName()} in ${formatDuration(pending.delaySeconds)}",
                        style = MaterialTheme.typography.bodyMedium)
                }
                val activity = when (val status = state.automationStatus) {
                    is AutomationStatus.Failed -> status.message
                    is AutomationStatus.Switching -> "Switching to ${status.desiredMode.displayName()}…"
                    is AutomationStatus.Retrying -> "Retrying ${status.desiredMode.displayName()} (${status.attempt}/${status.maxAttempts})"
                    else -> null
                }
                if (activity != null) Text(activity, style = MaterialTheme.typography.bodySmall,
                    color = if (state.automationStatus is AutomationStatus.Failed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                val check = state.lastAutomationCheck
                HorizontalDivider()
                Text("Last Wi-Fi automation", style = MaterialTheme.typography.titleSmall)
                Text(check?.summary ?: "No runs yet", style = MaterialTheme.typography.bodyMedium,
                    color = if (check?.problem == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                if (check != null) Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(check.timeMillis)), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val latest = state.lastCheck
                if (latest != null && latest.timeMillis != check?.timeMillis) {
                    HorizontalDivider()
                    Text("Last manual action", style = MaterialTheme.typography.titleSmall)
                    Text(latest.summary, style = MaterialTheme.typography.bodyMedium,
                        color = if (latest.problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(latest.timeMillis)), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(
                onClick = viewModel::syncNow,
                enabled = !state.ringOperationInProgress,
            ) { Text("Apply Wi-Fi mode") }
            OutlinedButton(
                onClick = viewModel::refreshRingMode,
                enabled = !state.ringOperationInProgress,
            ) { Text("Refresh Ring status") }
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
        state.ringConnectionFailed -> Pair("Connection issue", MaterialTheme.colorScheme.errorContainer)
        else -> when (state.ringMode) {
            RingMode.DISARMED -> Pair("Disarmed", MaterialTheme.colorScheme.primaryContainer)
            RingMode.AWAY -> Pair("Away", MaterialTheme.colorScheme.primaryContainer)
            RingMode.UNKNOWN, RingMode.UNAVAILABLE -> Pair("Unknown", MaterialTheme.colorScheme.surfaceVariant)
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = containerColor,
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("CAMERAS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text(headline, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(if (state.ringConnectionFailed) "Ring connection unavailable" else "Current Ring mode",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SetupPage(
    state: StatusUiState,
    viewModel: StatusViewModel,
    requestPermissions: () -> Unit,
    useCurrentWifi: () -> Unit,
    backgroundLocationGranted: Boolean,
    requestBackgroundLocation: () -> Unit,
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
        if (!backgroundLocationGranted) {
            Text("For Auto while the app is closed, set Location to Allow all the time in app permissions.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = requestBackgroundLocation) {
                Text("Open app permissions")
            }
        }
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
        Text("Wi-Fi automation runs periodically. Android may defer it to preserve battery.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun homeStatus(state: StatusUiState): String {
    return when (state.presence) {
        PresenceState.HOME -> "Connected to home Wi-Fi: ✓"
        PresenceState.AWAY -> "Connected to home Wi-Fi: ✗"
        PresenceState.UNKNOWN, PresenceState.NOT_CONFIGURED -> "Home Wi-Fi status unknown"
    }
}

private fun formatDuration(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

private fun Enum<*>.displayName(): String = name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)

private fun ControlMode.controlLabel(): String = when (this) {
    ControlMode.AUTO -> "Auto"
    ControlMode.AWAY -> "Away"
    ControlMode.DISARMED -> "Disarm"
}
