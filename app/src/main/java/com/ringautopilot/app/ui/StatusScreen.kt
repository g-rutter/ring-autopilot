package com.ringautopilot.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.ringautopilot.app.automation.AutomationStatus
import com.ringautopilot.app.geofence.GeofenceRegistrationHealth
import com.ringautopilot.app.model.AutomationSettings
import com.ringautopilot.app.model.ControlMode
import com.ringautopilot.app.model.PresenceState
import com.ringautopilot.app.model.RingMode
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    viewModel: StatusViewModel,
    foregroundLocationGranted: Boolean,
    backgroundLocationGranted: Boolean,
    locationServicesEnabled: Boolean,
    notificationGranted: Boolean,
    requestForegroundLocation: () -> Unit,
    useCurrentWifi: ((String) -> Unit) -> Unit,
    requestBackgroundLocation: () -> Unit,
    requestNotificationPermission: () -> Unit,
    requestCurrentLocation: ((Double, Double) -> Unit) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var configurationRequested by rememberSaveable { mutableStateOf(false) }
    val showConfiguration = configurationRequested || !state.isSetupComplete

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (showConfiguration) "Configuration" else "Ring Autopilot",
                    fontWeight = FontWeight.SemiBold) },
                actions = {
                    if (!showConfiguration) TextButton(onClick = { configurationRequested = true }) {
                        Text("Configuration")
                    }
                    if (showConfiguration && state.isSetupComplete) {
                        TextButton(onClick = { configurationRequested = false }) { Text("Done") }
                    }
                },
            )
        },
    ) { padding ->
        if (showConfiguration) {
            ConfigurationPage(
                state, viewModel, foregroundLocationGranted, backgroundLocationGranted,
                locationServicesEnabled, requestForegroundLocation, useCurrentWifi,
                requestBackgroundLocation, requestCurrentLocation,
                onDone = { configurationRequested = false },
                modifier = Modifier.padding(padding),
            )
        } else {
            DashboardPage(
                state, viewModel, backgroundLocationGranted, notificationGranted,
                requestBackgroundLocation, requestNotificationPermission,
                Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun DashboardPage(
    state: StatusUiState,
    viewModel: StatusViewModel,
    backgroundLocationGranted: Boolean,
    notificationGranted: Boolean,
    requestBackgroundLocation: () -> Unit,
    requestNotificationPermission: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ModeHero(state)
        if (state.controlMode == ControlMode.AUTO &&
            (state.wifiPresenceEnabled || state.geofencePresenceEnabled) &&
            !backgroundLocationGranted) {
            Text("Background presence needs Location set to Allow all the time while the app is closed.")
            OutlinedButton(onClick = requestBackgroundLocation) { Text("Open app permissions") }
        }
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("CONTROL", style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Switch(checked = state.controlMode == ControlMode.AUTO,
                        onCheckedChange = viewModel::setAutoEnabled,
                        enabled = !state.ringOperationInProgress)
                }
                if (!notificationGranted) {
                    Text("Allow notifications to be told when Auto changes Ring mode.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = requestNotificationPermission) { Text("Allow notifications") }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { viewModel.setRingMode(RingMode.AWAY) },
                        modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp, 8.dp),
                        enabled = !state.ringOperationInProgress) { Text("Force\nAway", textAlign = TextAlign.Center) }
                    OutlinedButton(onClick = { viewModel.setRingMode(RingMode.DISARMED) },
                        modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp, 8.dp),
                        enabled = !state.ringOperationInProgress) { Text("Force\nDisarm", textAlign = TextAlign.Center) }
                    FilledTonalButton(onClick = viewModel::syncNow,
                        modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp, 8.dp),
                        enabled = !state.ringOperationInProgress) { Text("Apply auto\nnow", textAlign = TextAlign.Center) }
                }
                val pending = state.automationStatus as? AutomationStatus.Waiting
                if (state.controlMode == ControlMode.AUTO && pending != null) {
                    Text("Switching to ${pending.desiredMode.displayName()} in ${formatDuration(pending.delaySeconds)}")
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
                state.ringValidationMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("RECENT ACTIVITY", style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f))
                    FilledTonalButton(onClick = viewModel::refreshRingMode,
                        enabled = !state.ringOperationInProgress,
                        contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Refresh Ring status") }
                }
                val check = state.lastAutomationCheck
                ActivityEntry("Presence automation", check?.summary ?: "No runs yet",
                    check?.timeMillis, check?.problem == true)
                state.lastManualCheck?.let {
                    HorizontalDivider()
                    ActivityEntry("Manual action", it.summary, it.timeMillis, it.problem)
                }
            }
        }
    }
}

@Composable
private fun ModeHero(state: StatusUiState) {
    val (headline, containerColor) = when {
        state.ringConnectionFailed -> "Connection issue" to MaterialTheme.colorScheme.errorContainer
        state.ringMode == RingMode.DISARMED -> "Disarmed" to MaterialTheme.colorScheme.primaryContainer
        state.ringMode == RingMode.AWAY -> "Away" to MaterialTheme.colorScheme.primaryContainer
        else -> "Unknown" to MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = containerColor) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("CAMERAS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text(headline, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            if (state.ringConnectionFailed) Text("Ring connection unavailable", style = MaterialTheme.typography.bodySmall)
            PresenceRow("Wi-Fi", detectorLabel(state.wifiPresenceEnabled, state.wifiPresence, "Connected", "Not connected"))
            PresenceRow("Geofence", when {
                !state.geofencePresenceEnabled -> "Off"
                state.geofenceRegistrationHealth == GeofenceRegistrationHealth.UNAVAILABLE -> "Unavailable"
                state.geofencePresence == PresenceState.HOME -> "Home"
                state.geofencePresence == PresenceState.AWAY -> "Away"
                else -> "Waiting"
            })
            PresenceRow("Combined", presenceLabel(state.presence))
        }
    }
}

@Composable
private fun PresenceRow(name: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, modifier = Modifier.weight(1f))
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface) {
            Text(value, Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ConfigurationPage(
    state: StatusUiState,
    viewModel: StatusViewModel,
    foregroundLocationGranted: Boolean,
    backgroundLocationGranted: Boolean,
    locationServicesEnabled: Boolean,
    requestForegroundLocation: () -> Unit,
    useCurrentWifi: ((String) -> Unit) -> Unit,
    requestBackgroundLocation: () -> Unit,
    requestCurrentLocation: ((Double, Double) -> Unit) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier,
) {
    var wifiEnabled by rememberSaveable(state.wifiPresenceEnabled) { mutableStateOf(state.wifiPresenceEnabled) }
    var geofenceEnabled by rememberSaveable(state.geofencePresenceEnabled) { mutableStateOf(state.geofencePresenceEnabled) }
    var ssid by rememberSaveable(state.homeWifiSsid) { mutableStateOf(state.homeWifiSsid) }
    var latitude by rememberSaveable(state.homeLatitude) { mutableStateOf(state.homeLatitude) }
    var longitude by rememberSaveable(state.homeLongitude) { mutableStateOf(state.homeLongitude) }
    var radius by rememberSaveable(state.homeGeofenceRadiusMeters) { mutableFloatStateOf(state.homeGeofenceRadiusMeters) }
    var refreshToken by rememberSaveable { mutableStateOf("") }
    var locationId by rememberSaveable(state.ringLocationId) { mutableStateOf(state.ringLocationId) }
    var mapGestureActive by remember { mutableStateOf(false) }
    val valid = (wifiEnabled || geofenceEnabled) && (!wifiEnabled || ssid.isNotBlank()) &&
        (!geofenceEnabled || (latitude != null && longitude != null && foregroundLocationGranted &&
            backgroundLocationGranted && locationServicesEnabled))

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState(), enabled = !mapGestureActive)
        .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Choose one or both ways to decide whether this phone is home.",
            style = MaterialTheme.typography.bodyLarge)
        FeatureCard("Wi-Fi", wifiEnabled, { enabled ->
            wifiEnabled = enabled
            if (enabled && !foregroundLocationGranted) requestForegroundLocation()
        }) {
            if (wifiEnabled) {
                OutlinedTextField(ssid, { ssid = it }, Modifier.fillMaxWidth(),
                    label = { Text("Home Wi-Fi name (SSID)") }, singleLine = true,
                    isError = ssid.isBlank())
                FilledTonalButton(onClick = { useCurrentWifi { ssid = it } }) {
                    Text("Use current Wi-Fi")
                }
                Text("Android needs Precise location and Location services to read the Wi-Fi name.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!foregroundLocationGranted || !locationServicesEnabled) {
                    OutlinedButton(onClick = requestForegroundLocation) { Text("Fix Wi-Fi access") }
                }
            }
        }
        FeatureCard("Geofencing", geofenceEnabled, { enabled ->
            geofenceEnabled = enabled
            if (enabled && !foregroundLocationGranted) requestForegroundLocation()
        }) {
            if (geofenceEnabled) {
                Text("Your selected point stays on this device. Background location is required for enter/exit automation while the app is closed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                GeofenceMapPicker(latitude, longitude, radius, foregroundLocationGranted,
                    onPointChanged = { lat, lon -> latitude = lat; longitude = lon },
                    requestCurrentLocation = requestCurrentLocation,
                    onGestureActiveChanged = { mapGestureActive = it })
                Text("Radius: ${radius.roundToInt()} m")
                Slider(radius, { radius = (it / 100f).roundToInt() * 100f },
                    valueRange = AutomationSettings.MIN_GEOFENCE_RADIUS_METERS..
                        AutomationSettings.MAX_GEOFENCE_RADIUS_METERS,
                    steps = 8)
                when {
                    !foregroundLocationGranted -> OutlinedButton(onClick = requestForegroundLocation) {
                        Text("Allow Precise location")
                    }
                    !locationServicesEnabled -> OutlinedButton(onClick = requestForegroundLocation) {
                        Text("Turn on Location services")
                    }
                    !backgroundLocationGranted -> OutlinedButton(onClick = requestBackgroundLocation) {
                        Text("Allow all the time")
                    }
                }
                if (latitude == null || longitude == null) Text("Tap the map to choose home.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!wifiEnabled && !geofenceEnabled) Text("Enable at least one presence feature.",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Text("Ring connection", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(refreshToken, { refreshToken = it }, Modifier.fillMaxWidth(),
            label = { Text("Ring refresh token") },
            supportingText = { Text("Generated with ring-auth-cli; encrypted on this device.") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true)
        OutlinedTextField(locationId, { locationId = it }, Modifier.fillMaxWidth(),
            label = { Text("Ring location ID (optional)") },
            supportingText = { Text("Leave blank to use the first account location.") }, singleLine = true)
        Button(onClick = {
            viewModel.savePresenceConfiguration(wifiEnabled, ssid, geofenceEnabled,
                latitude, longitude, radius)
            if (refreshToken.isNotBlank()) {
                viewModel.saveRingCredentials(refreshToken, locationId)
                refreshToken = ""
            } else if (locationId != state.ringLocationId) viewModel.saveRingLocationId(locationId)
            onDone()
        }, enabled = valid, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.isSetupComplete) "Save changes" else "Finish configuration")
        }
        Spacer(Modifier.height(4.dp))
        Text("Geofence events are usually faster than the 15-minute recovery check, but Android may batch background delivery by a few minutes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FeatureCard(
    title: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(enabled, onEnabledChange)
            }
            content()
        }
    }
}

@Composable
private fun GeofenceMapPicker(
    latitude: Double?,
    longitude: Double?,
    radiusMeters: Float,
    locationGranted: Boolean,
    onPointChanged: (Double, Double) -> Unit,
    requestCurrentLocation: ((Double, Double) -> Unit) -> Unit,
    onGestureActiveChanged: (Boolean) -> Unit,
) {
    val fallback = LatLng(51.5074, -0.1278)
    val initial = if (latitude != null && longitude != null) LatLng(latitude, longitude) else fallback
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(initial, 15f) }
    val scope = rememberCoroutineScope()
    val point = if (latitude != null && longitude != null) LatLng(latitude, longitude) else null
    GoogleMap(
        modifier = Modifier.fillMaxWidth().height(260.dp).pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                onGestureActiveChanged(true)
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                } finally {
                    onGestureActiveChanged(false)
                }
            }
        },
        cameraPositionState = camera,
        properties = MapProperties(isMyLocationEnabled = locationGranted),
        uiSettings = MapUiSettings(myLocationButtonEnabled = false),
        onMapClick = { onPointChanged(it.latitude, it.longitude) },
    ) {
        point?.let {
            Marker(state = rememberUpdatedMarkerState(it), title = "Home")
            Circle(center = it, radius = radiusMeters.toDouble(),
                fillColor = Color(0x332196F3), strokeColor = Color(0xFF1976D2))
        }
    }
    OutlinedButton(onClick = {
        requestCurrentLocation { lat, lon ->
            onPointChanged(lat, lon)
            scope.launch { camera.animate(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 16f)) }
        }
    }) { Text("Use current location") }
}

@Composable
private fun ActivityEntry(title: String, summary: String, timeMillis: Long?, problem: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        timeMillis?.let { Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(it)), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    Text(summary, style = MaterialTheme.typography.bodySmall,
        color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun detectorLabel(enabled: Boolean, state: PresenceState, home: String, away: String): String = when {
    !enabled -> "Off"
    state == PresenceState.HOME -> home
    state == PresenceState.AWAY -> away
    else -> "Unavailable"
}

private fun presenceLabel(state: PresenceState): String = when (state) {
    PresenceState.HOME -> "Home"
    PresenceState.AWAY -> "Away"
    PresenceState.UNKNOWN -> "Unavailable"
    PresenceState.NOT_CONFIGURED -> "Off"
}

private fun formatDuration(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)
private fun Enum<*>.displayName(): String = name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
