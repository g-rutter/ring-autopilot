# Geofencing presence plan

## Status

Approved for implementation. All material product and platform decisions below are resolved.

Implementation started 2026-09-20.

- [x] Model and pure aggregation: detector settings, validated geofence definition,
  legacy Wi-Fi migration, conservative presence aggregation, and unit tests.
- [x] Geofence infrastructure: Play services registration, persisted transition
  state, safe event parsing, transition/restore receivers, recovery work, and
  manifest wiring.
- [x] Presence integration.
- [x] Configuration and permission UX.
- [x] Dashboard, widget-facing summaries, and documentation.
- [ ] Device acceptance verification (automated verification complete; physical-device matrix pending).

The Android unit-test suite passes with 34 tests after milestones 3–5. Debug
compilation includes the Maps Compose picker. The physical-device acceptance
matrix remains required before milestone 6 can be closed.

## Objective

Add geofencing as a second, independently configurable home-presence signal alongside Wi-Fi. When both signals are enabled, Ring may be switched to **Away** only when both signals explicitly say the phone is away from home. A positive Home signal from either feature keeps or switches Ring to **Disarmed**.

Geofence enter and exit transitions should trigger an automation check instead of waiting for the existing 15-minute periodic worker. Periodic work remains as a recovery/reconciliation path; Android may still batch background geofence events by a few minutes.

Rename the existing **Set up** page and its dashboard link to **Configuration**.

## Scope

### In scope

- Independent **Use Wi-Fi** and **Use geofencing** switches.
- One circular home geofence, with a user-selected centre and radius.
- Event-driven enter and exit handling in the background.
- Safe fusion of enabled presence signals.
- Feature-specific, just-in-time permission requests.
- Registration recovery after reboot, package replacement, or a geofencing service failure.
- Dashboard visibility for both signals and their combined result.
- Concise, redacted diagnostics for setup, registration, transitions, and failures.
- Unit tests for signal fusion and automation decisions, plus device acceptance tests for Android permission and background behavior.
- README updates describing the new behavior, dependencies, permissions, and limitations.

### Out of scope

- Multiple homes or multiple geofences.
- Polygonal geofences, address search, reverse geocoding, or place-name storage.
- User-configurable arrival/departure delays or retry policy.
- Continuous GPS tracking or a foreground location service.
- Guarantees of immediate background delivery; Android can batch transition events.
- Changing Ring authentication, location selection, manual controls, or event aggregation.

## Current implementation constraints

- `AndroidWifiPresenceService` is the only `PresenceService` and publishes a single `PresenceState`.
- `AutomationController` observes that single state, applies the 1-second arrival or 30-second departure delay, and schedules WorkManager recovery.
- `MonitoringWorker` constructs a fresh `AppContainer`, performs one presence check, and is also used for delayed changes.
- Settings are held in `PreferencesSettingsRepository`; the current setup-complete check is simply a non-blank SSID.
- Foreground network callbacks are stopped when `MainActivity` stops. Background checks currently depend on periodic or delayed WorkManager work.
- The manifest already declares fine and background location, but the UI groups location and notification requests under a generic Permissions action.
- Diagnostics already redact keys containing `ssid`, `coordinates`, and `locationId`; exact geofence coordinates must never be logged.

## Presence semantics

Keep detector states separate and combine them in a small, pure `PresenceAggregator`. This avoids overloading the existing Wi-Fi service and makes the safety rule directly testable.

Each detector reports `HOME`, `AWAY`, `UNKNOWN`, or `NOT_CONFIGURED`. Disabled detectors are excluded from the calculation.

| Enabled signals | Wi-Fi | Geofence | Combined presence | Result |
|---|---|---|---|---|
| Wi-Fi only | Home | — | Home | Disarmed |
| Wi-Fi only | Away | — | Away | Away |
| Geofence only | — | Home | Home | Disarmed |
| Geofence only | — | Away | Away | Away |
| Both | Home | Home/Away/Unknown | Home | Disarmed |
| Both | Away | Home | Home | Disarmed |
| Both | Away | Away | Away | Away |
| Both | Away/Unknown | Unknown | Unknown | No mode change |
| Neither | — | — | Not configured | No mode change |

The general rule is:

1. If no detector is enabled, return `NOT_CONFIGURED`.
2. If any enabled detector reports `HOME`, return `HOME`.
3. If every enabled detector reports `AWAY`, return `AWAY`.
4. Otherwise return `UNKNOWN` and do not change Ring mode.

This intentionally favors avoiding a false Away/armed change. Existing arrival/departure delays apply after aggregation, so a geofence transition can start the check promptly without removing the current anti-flapping delay.

## Proposed architecture

### Settings and persisted state

Extend `AutomationSettings` and `SettingsRepository` with:

- `wifiPresenceEnabled: Boolean`
- `geofencePresenceEnabled: Boolean`
- `homeLatitude: Double?`
- `homeLongitude: Double?`
- `homeGeofenceRadiusMeters: Float`

Defaults and migration:

- Existing installs with a saved SSID migrate to Wi-Fi enabled and geofencing disabled, preserving current behavior.
- New installs initially have neither detector fully configured. The Configuration page prevents completion until at least one detector is enabled and valid.
- Default the radius to 100 m and constrain it to 100–1,000 m. Android recommends a minimum radius around 100–150 m for reliable geofencing; 100 m is the smallest recommended choice and avoids making the default area larger than necessary.
- Store coordinates only in private app preferences. Do not copy them into diagnostics, status summaries, notifications, intents beyond the explicit in-app `PendingIntent`, or widget text.

Persist the latest geofence signal separately from configuration, including state and update time. The timestamp is for UI/diagnostics, not a short expiry: a transition remains the best known state until a newer transition, a registration reset, or reconciliation changes it. Clear it to `UNKNOWN` whenever the geofence centre/radius changes, geofencing is disabled, permission is lost, or registration becomes unavailable.

### Services and composition

Introduce these responsibilities:

- `WifiPresenceService`: the renamed/narrowed current implementation. It observes network changes only while Wi-Fi presence is enabled and publishes `NOT_CONFIGURED` when disabled or missing an SSID.
- `GeofencePresenceStore`: persists the latest geofence state and exposes it as a flow so foreground UI/controller instances and worker-created containers see receiver updates.
- `GeofenceManager`: registers or removes the single home geofence, maps safe platform errors, and exposes registration health. Registration is idempotent and replaces the stable request ID.
- `CombinedPresenceService`: implements the `PresenceService` consumed by `AutomationController`, combines detector flows using the truth table above, refreshes the enabled detectors, and delegates current SSID lookup to Wi-Fi.
- `GeofenceBroadcastReceiver`: validates `GeofencingEvent`, maps ENTER to Home and EXIT to Away, writes the store, and enqueues an immediate unique automation check with a network constraint.
- `GeofenceRestoreReceiver`: on boot/package replacement, enqueues a small registration worker when geofencing is enabled and configured. Registration must also be reconciled when the app starts, settings change, or `GEOFENCE_NOT_AVAILABLE` is received.

Use the Google Play services Location `GeofencingClient`, which is Android's documented/recommended geofencing implementation. Configure one non-expiring circular geofence with ENTER and EXIT transitions and an initial ENTER/EXIT trigger so registration establishes a state when possible. Use an explicit mutable broadcast `PendingIntent` on Android 12+, as required by the API.

The existing periodic `MonitoringWorker` remains registered. It consumes the combined state, not Wi-Fi directly, and provides a safety-net automation check if a transition-triggered job was deferred or lost. It should also request geofence registration reconciliation, but must not start continuous location polling.

### Event flow

1. The user enables geofencing, selects a point/radius, and grants the required foreground then background location permissions.
2. Saving Configuration persists valid settings and calls `GeofenceManager.reconcile()`.
3. Play services sends ENTER or EXIT to `GeofenceBroadcastReceiver`.
4. The receiver validates the request ID/transition, stores Home or Away, records one concise diagnostic event, and enqueues unique immediate `MonitoringWorker` work tagged `geofence_transition`.
5. The worker refreshes enabled signals, aggregates them, and passes the combined state to the existing `AutomationController`.
6. The controller applies the existing grace period, rechecks combined presence before writing, and changes Ring only if the desired mode remains valid.

If the app is foregrounded, the persisted geofence flow also wakes the existing controller immediately. Unique work naming/deduplication should prevent duplicate Ring writes when foreground and background paths overlap; the existing read-before-write behavior remains the final idempotency guard.

## Configuration UI

Rename:

- Page title: **Set up** → **Configuration**
- Dashboard action: **Settings** → **Configuration**
- Internal composable names from `SetupPage`/`setupRequested` to configuration terminology.

Rework the Home presence section into two feature cards:

### Wi-Fi

- **Use Wi-Fi** switch.
- Show the SSID field and **Use current Wi-Fi** only while enabled.
- Explain that Android location access/location services are needed to read the SSID on the supported OS versions.
- Request Wi-Fi-related location permission only when the user enables Wi-Fi detection or chooses **Use current Wi-Fi**.
- A blank SSID is a validation error only when Wi-Fi detection is enabled.

### Geofencing

- **Use geofencing** switch.
- When enabled, show a compact Google Maps Compose picker with one marker, a translucent circular overlay, a current-location/recenter action, and a simple radius control (default 100 m; 100–1,000 m).
- Tapping the map moves the centre; no address lookup is required.
- Explain that the location is stored on device and background location is needed for enter/exit automation while the app is closed.
- Request fine location first and background location second, only after the user opts into geofencing. On Android 11+, direct the user to the app settings screen for “Allow all the time” after a clear rationale.
- Do not persist geofencing as operationally enabled or register it until a point, supported location settings, and required permissions are present. If permission is later revoked, retain the chosen point but mark the signal unavailable and remove/reconcile registration when possible.

### Shared validation and status

- Require at least one presence feature to be enabled before Configuration can be saved/completed.
- Keep Ring credentials/location fields unchanged.
- Replace the generic Permissions button with contextual actions and status text in each enabled feature card.
- Request notification permission separately and only in the path that enables automatic mode-change notifications; do not bundle it with location permission.
- Update the dashboard hero to show Wi-Fi and Geofence rows independently, plus a clear combined status when useful. Disabled signals read **Off**; permission/configuration problems read **Unavailable**, not Away.
- Rename “Wi-Fi automation” recent activity to “Presence automation” and remove Wi-Fi-specific failure copy from `CheckResult` and `MonitoringWorker`.
- Show the background-location warning only when an enabled detector actually requires it.

## Permissions and platform behavior

Manifest declarations are capabilities, not runtime requests, so fine/background location can remain declared while runtime requests are conditional. Add `RECEIVE_BOOT_COMPLETED` for geofence restoration and declare the explicit transition and restore receivers.

Runtime policy:

- Neither detector enabled: request no location permission and perform no location/Wi-Fi reads.
- Wi-Fi enabled: request only permissions needed for SSID access on the running Android version; request background location only if required for the app's background Wi-Fi checks.
- Geofencing enabled: require precise/fine location and, on Android 10+, background location. Approximate-only access is insufficient for registration.
- Notification permission is independent of presence setup.
- Disabling geofencing removes the registered geofence and stops geofence access. Disabling Wi-Fi unregisters/ignores network callbacks. Android-granted permissions are not automatically revoked.

The app must communicate that background events are faster than the 15-minute poll but are not real-time: Android 8+ can batch background geofence responses by a couple of minutes.

## Error and lifecycle handling

- Registration failures do not imply Away. Publish `UNKNOWN`, show a configuration/dashboard issue, and retain settings for retry.
- Invalid/missing coordinates or an out-of-range radius block save/registration.
- A revoked permission, disabled device Location setting, unavailable Play services, or `GEOFENCE_NOT_AVAILABLE` produces `UNKNOWN`, removes stale confidence, and prompts a scoped corrective action.
- Reconcile registration after configuration save, app start/resume, boot, package replacement, and recoverable service failure.
- Cancel pending Away work whenever combined presence becomes Home/Unknown, Auto is turned off, or the detector configuration changes.
- Preserve coroutine cancellation and the current Ring read-before-write/retry behavior.
- Never arm based solely on an old Away value after a geofence definition or permission change.

## Logging

Add a small diagnostic surface consistent with `DiagnosticLog`:

- `geofence_registration` with `outcome` and safe `reason` (`registered`, `removed`, `permission_denied`, `location_disabled`, `play_services_unavailable`, `not_configured`, `api_error`).
- `geofence_transition` with `transition` (`enter`/`exit`) and resulting presence.
- `geofence_event_ignored` only for actionable malformed/unknown/error events, with a stable reason.
- Include enabled-detector booleans and the combined presence in the existing `check_start`/`check_end`; do not log each unchanged aggregation.
- Log configuration toggle changes as existing `user_action_end` events.

Never log latitude, longitude, map viewport, address, exact SSID, Ring identifiers, raw platform error text, or exception messages. Keep one registration outcome per reconciliation and one transition event per delivered transition.

## Implementation sequence

1. **Model and pure aggregation**
   - Add detector enablement and geofence definition settings.
   - Add the pure aggregation function and exhaustive truth-table tests.
   - Migrate existing preferences without changing current users' Wi-Fi behavior.

2. **Geofence infrastructure**
   - Add the Play services Location dependency.
   - Implement the geofence state store, manager, transition receiver, registration/restore worker, and manifest entries.
   - Add safe error mapping and concise diagnostics.

3. **Presence integration**
   - Compose Wi-Fi and geofence services behind the existing `PresenceService` boundary.
   - Make callbacks, refresh, worker checks, delayed rechecks, and pending-work cancellation use combined presence.
   - Add transition-triggered unique work and preserve periodic recovery work.

4. **Configuration and permission UX**
   - Rename the page/link and refactor feature-specific sections.
   - Add the Google Maps Compose point/radius picker.
   - Implement staged, feature-scoped permission requests and validation.

5. **Dashboard, widget-facing summaries, and docs**
   - Display individual/combined presence and generic presence-automation activity.
   - Keep widget behavior compatible while making check summaries source-neutral.
   - Update README configuration, background behavior, test coverage, and diagnostics.

6. **Verification**
   - Run all unit tests using the repository's Android Studio JDK/temporary Gradle cache command.
   - Exercise the device acceptance matrix below and inspect redacted `RingAutopilot` logcat output.

## Test plan

### Unit tests

- All combinations in the presence truth table, including disabled and unconfigured detectors.
- Only an all-Away set produces `RingMode.AWAY`; any Home produces `DISARMED`; uncertainty produces no desired mode.
- Settings migration keeps saved-SSID users on Wi-Fi-only behavior.
- Geofence setting validation and state reset after centre/radius/permission changes.
- ENTER/EXIT parsing, irrelevant request IDs/transitions, and safe error mapping.
- Pending Away cancellation when either detector becomes Home or Unknown.
- `CheckResult` and diagnostic formatting remain source-neutral and redact sensitive keys/values.

### Android/device acceptance tests

- Fresh install with Wi-Fi only, geofence only, both, and neither selected.
- Permission grant, denial, “while using,” “allow all the time,” later revocation, and disabled Location services.
- Map point/radius save, edit, rotation/recreation, and registration replacement.
- Enter and exit while foregrounded, backgrounded, process-killed, and after reboot.
- Conflicting signals: Wi-Fi Home + geofence Away; Wi-Fi Away + geofence Home; both Away.
- Loss of Wi-Fi during an exit and reconnection during an arrival.
- Transition delivery without network, followed by network restoration.
- Rapid boundary crossing/flapping and duplicate transition delivery.
- Auto off, Force Away/Disarm, and Apply auto now with each detector combination.
- Devices with missing/outdated Play services.
- Confirm no coordinates/SSID/tokens appear in logcat.

## Acceptance criteria

- Wi-Fi and geofencing can each be enabled or disabled without enabling the other.
- No location permission prompt is shown for a detector the user has not enabled or invoked.
- With both enabled, Ring is never requested to enter Away unless both signals are explicitly Away at the final pre-write recheck.
- Home from either enabled detector requests Disarmed after the current arrival delay.
- Unknown/unavailable input can prevent arming and never masquerades as Away.
- An enter/exit event schedules a check promptly without waiting for periodic work, subject to Android batching and network availability.
- Disabling or editing geofencing removes/replaces registration and invalidates stale state.
- Geofence registration is restored after reboot/package replacement when settings and permissions permit.
- Configuration and dashboard copy no longer describes the whole feature as Wi-Fi automation.
- New logs are concise and contain no precise location or other sensitive values.

## Resolved decisions

### 1. Geofencing provider

**Decision: use Google Play services Location (`GeofencingClient`).** Android's current guidance recommends Google Play services for geofencing; the legacy framework proximity API is deprecated and is not a robust substitute. This means geofencing will require a device with functioning Google Play services. On unsupported devices the feature remains unavailable while Wi-Fi continues to work.

### 2. Point-picker map provider

**Decision: use Google Maps Compose** for the tap-to-place marker and visible circle because the app already uses Compose and it keeps the interaction small. As checked on 2026-09-19, Google lists the basic native Maps SDK SKU as unlimited with no usage charge. It still requires a Google Cloud project, restricted API key, and billing-enabled account; pricing may change, and chargeable services such as Street View must not be enabled or used by this feature.

Alternatives considered but not selected:

- MapLibre is a free, open-source rendering SDK, but it does not supply map data or hosting. Using it still requires a tile/style source. OpenStreetMap data is free; the community `tile.openstreetmap.org` servers are best-effort, policy-limited infrastructure rather than a guaranteed production backend, so the app would need compliant direct use for this small personal workload, another tile provider, or self-hosting.
- No embedded map: “Use current location” plus manual latitude/longitude entry; smallest dependency footprint, but it does not fully meet the requested point-picking experience.

### 3. Radius control

**Decision: provide a user-adjustable radius from 100–1,000 m, defaulting to 100 m.** The lower bound is primarily an accuracy/reliability constraint. Geofencing deliberately uses battery-efficient location sources, and Android documents typical Wi-Fi location accuracy around 20–50 m with potentially much worse accuracy when Wi-Fi is unavailable. A smaller fence is more likely to flap or report a false exit while the phone is still at home.

## References

- [Android: Create and monitor geofences](https://developer.android.com/develop/sensors-and-location/location/geofencing)
- [Android: Access location in the background](https://developer.android.com/develop/sensors-and-location/location/background)
- [Android: Migrate to Google Play services location APIs](https://developer.android.com/develop/sensors-and-location/location/migration)
- [Google Play services: GeofencingClient](https://developers.google.com/android/reference/com/google/android/gms/location/GeofencingClient)
- [Google Maps Compose library](https://developers.google.com/maps/documentation/android-sdk/maps-compose)
- [Google Maps SDK setup and API key](https://developers.google.com/maps/documentation/android-sdk/start)
- [Google Maps SDK for Android usage and billing](https://developers.google.com/maps/documentation/android-sdk/usage-and-billing)
- [MapLibre Native](https://maplibre.org/projects/native/)
- [OpenStreetMap tile usage policy](https://operations.osmfoundation.org/policies/tiles/)
