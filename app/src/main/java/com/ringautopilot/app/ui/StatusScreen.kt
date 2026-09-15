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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    viewModel: StatusViewModel,
    requestPermissions: () -> Unit,
    useCurrentWifi: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var ssid by remember(state.homeWifiSsid) { mutableStateOf(state.homeWifiSsid) }
    var refreshToken by remember { mutableStateOf("") }
    var locationId by remember(state.ringLocationId) { mutableStateOf(state.ringLocationId) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Ring Autopilot") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Phone presence controls the Ring location mode after a safety delay.",
                style = MaterialTheme.typography.bodyLarge,
            )

            StatusCard(
                title = "Presence",
                value = state.presence.displayName(),
                detail = "Home Wi-Fi: ${state.homeWifiSsid.ifBlank { "Not set" }}",
            )
            StatusCard(
                title = "Ring mode",
                value = state.ringMode.displayName(),
                detail = "Connect Ring credentials to enable automatic mode changes.",
            )
            StatusCard(
                title = "Automation",
                value = state.automationStatus.displayName(),
                detail = "Away delay: 3 min · Arrival delay: 30 sec",
            )

            Text("Setup", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = ssid,
                onValueChange = { ssid = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Home Wi-Fi name (SSID)") },
                singleLine = true,
            )
            OutlinedTextField(
                value = refreshToken,
                onValueChange = { refreshToken = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Ring refresh token") },
                supportingText = { Text("Generate with ring-auth-cli; it is encrypted on this device.") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            OutlinedTextField(
                value = locationId,
                onValueChange = { locationId = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Ring location ID (optional)") },
                supportingText = { Text("Leave blank to use the first location on the account.") },
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { viewModel.saveHomeWifiSsid(ssid) }) {
                    Text("Save Wi-Fi")
                }
                Button(onClick = useCurrentWifi) {
                    Text("Use current Wi-Fi")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = requestPermissions) {
                    Text("Grant permissions")
                }
            }
            Button(
                onClick = {
                    viewModel.saveRingCredentials(refreshToken, locationId)
                    refreshToken = ""
                },
                enabled = refreshToken.isNotBlank(),
            ) {
                Text("Save Ring credentials")
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Monitoring runs as a foreground service so Android can keep it active " +
                    "when the app is not open. Battery-saving settings may still need an exception.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusCard(title: String, value: String, detail: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun Enum<*>.displayName(): String =
    name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)

private fun AutomationStatus.displayName(): String = when (this) {
    AutomationStatus.Idle -> "Idle"
    is AutomationStatus.Waiting -> "Waiting ${delaySeconds}s to switch to ${desiredMode.displayName()}"
    is AutomationStatus.Switching -> "Switching to ${desiredMode.displayName()}"
    is AutomationStatus.Retrying -> "Retrying ${desiredMode.displayName()} " +
        "(${attempt + 1}/$maxAttempts) in ${delaySeconds}s"
    is AutomationStatus.Failed -> "Needs attention: $message"
}
