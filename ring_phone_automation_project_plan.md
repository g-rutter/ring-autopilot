# Ring Phone Automation – Project Plan

## Goal

Build a phone-first app that automatically switches Ring between **Away** and **Disarmed** based on whether the phone is home, confirms mode changes with a notification, and groups or throttles noisy Ring notifications.

## Current status

The Android implementation now contains the main app, Wi-Fi presence monitor,
foreground monitoring service, Ring HTTP client, encrypted refresh-token store,
mode-change controller, local notifications, and a polling event aggregator.
The checked-in unit tests passed on 2026-09-15 (6 tests, 0 failures). On the
same date, a physical Android device successfully authenticated to a live Ring
test location, discovered its location ID, read its Away mode, manually switched
to Away and Disarmed, and re-authenticated after a force-stop/relaunch. Event
polling and automatic presence transitions have not yet been live-validated.

| Area | Status | Notes |
|---|---|---|
| Setup UI and Wi-Fi permission flow | Implemented | Saves an SSID or reads the currently connected SSID after the required Android permission and Location-services checks. |
| Wi-Fi HOME/AWAY detection and grace periods | Implemented | Defaults are 30 seconds on arrival and 3 minutes on departure. |
| Background monitoring | Implemented | A foreground service owns monitoring when the activity is not visible. |
| Ring mode read/change and token storage | Implemented, live-validated | On 2026-09-15, a physical device verified refresh-token authentication/rotation, location discovery, mode reads, manual Away/Disarmed changes, and token persistence across force-stop/relaunch. |
| Mode-change retry and success notification | Implemented, with a gap | `setMode` retries with bounded exponential backoff; a failed preliminary mode read does not yet retry. |
| Event polling, grouping, and notification | Implemented, unverified | Doorbells notify immediately; other events are polled and summarized. Poll progress and deduplication do not survive a service restart. |
| User-configurable settings | Partial | Only SSID and optional location ID are persisted through the UI; other values currently use code defaults. |

## Approach

- Run the automation entirely on the phone. No Home Assistant or separate always-on device.
- Use phone presence as the trigger. Start with home Wi-Fi connection state and add a short delay so brief Wi-Fi drops do not change Ring mode accidentally.
- Control Ring through an isolated `RingService` layer. Because the official developer API is not currently suitable for this non-US setup, use the established unofficial/private Ring API route for the first version.
- Keep presence logic, Ring control, and notification policy separate so any future change in Ring access only affects one part of the app.
- Target Android. Ring mode changes must use standard retrying with backoff: when leaving home Wi-Fi, mobile data may not be available immediately, so a temporarily failed mode change is an expected connectivity state rather than an exception.

## Core behaviour

| Situation | Action | Confirmation |
|---|---|---|
| Phone leaves home Wi-Fi and remains away for a short grace period | Set Ring Location Mode to **Away** | Local notification: Ring switched to Away |
| Phone reconnects to home Wi-Fi and remains connected briefly | Set Ring Location Mode to **Disarmed** | Local notification: Ring switched to Disarmed |
| Short Wi-Fi dropout | Do nothing unless the away grace period expires | None |

If applying a requested Ring mode change fails because network connectivity is temporarily unavailable, retry automatically with backoff until it succeeds or the configured retry policy is exhausted. This is expected behavior during the transition from home Wi-Fi to mobile data and must not be treated as an exceptional path. Only show the success confirmation after the mode change is confirmed; report a final failure if the retry policy is exhausted.

### Ring Location Modes

**Away** and **Disarmed** are location-wide Ring states. The Ring app decides what each camera does in those states, so the custom app only needs to switch the mode rather than reconfigure every camera individually.

## Notification handling

- Mode-change notifications are immediate so it is obvious when the automation has armed or disarmed Ring.
- Ordinary motion events can be collected into a short time window and summarized instead of producing repeated alerts.
- Important events, such as a doorbell press or selected person-detection events, can bypass batching and notify immediately.
- Use simple cooldown rules for repeated events from the same camera to reduce spam.

Example: instead of six front-door motion alerts in five minutes, send one notification such as:

> Front door: 6 motion events in the last 5 minutes.

## App structure

### `PresenceService`
Detect home Wi-Fi connection/disconnection and apply the arrival/departure delay.

### `RingService`
Authenticate to Ring, read the current location mode, and switch between Away and Disarmed.

### `AutomationController`
Own the HOME/AWAY state and decide when a Ring mode change is required.

### `EventAggregator`
Collect Ring events, group duplicates, apply cooldowns, and decide what should notify immediately.

### `NotificationService`
Show local mode-change confirmations and grouped Ring event summaries.

### Local storage
Store tokens securely plus small amounts of state such as current mode, timestamps, and recent event counters.

## Build order

1. [x] Create the basic mobile app and a status/setup screen.
2. [~] Implement Ring authentication and `RingService`. A physical-device test verified authentication, location discovery, current-mode reads, manual Away/Disarmed controls, and refresh-token persistence on 2026-09-15; live event polling and automatic mode transitions remain.
3. [x] Add home Wi-Fi presence detection with an away grace period and a shorter arrival confirmation period.
4. [~] Connect presence changes to Ring mode changes and show a local notification after each successful automatic switch. The flow is implemented; it still needs device and live-account validation.
5. [~] Add Ring event intake and notification grouping/throttling. Polling and aggregation are implemented; restart-safe intake and live verification remain.
6. [ ] Tune the delays and notification rules from real use.

## Remaining release work

1. Continue validating the private Ring integration on a non-production/test
   location: refresh-token authentication/rotation, location discovery, current
   mode reads, manual Away/Disarmed changes, and token persistence were verified
   on a physical device on 2026-09-15. Poll each supported event type, including
   person detection. The private API is not stable or supported, so this remains
   the release gate with the highest risk.
2. Retry the entire mode transition, including a transient `refreshMode()`
   failure, and display a final actionable failure state after the retry budget
   is exhausted.
3. Add a master automation toggle, clear last-success/last-failure status, and
   manual Away/Disarmed actions. Include an override/cooldown policy so the
   app does not promptly undo a deliberate change made in the Ring app.
4. Persist and expose the remaining first-version settings: arrival and
   departure delays, retry policy, grouping window, event cooldown, and
   immediate event types. Require a deliberate selection when an account has
   more than one Ring location instead of silently using the first one.
5. Make event intake restart-safe: persist an event cursor and deduplication
   identifiers, recover visibly from polling failures, and confirm the
   person-detection fields returned by the live API.
6. Complete physical-device acceptance testing: first-run permissions,
   Location services off, Wi-Fi loss and recovery, mobile-data handoff, denied
   notifications, battery optimization, reboot, and foreground-service
   survival on the target Android versions.
7. Expand automated tests to cover controller timing/cancellation and retries,
   settings and token persistence, Ring HTTP request/response mapping, and
   event checkpoint recovery.

## Recommended improvements

- Use separate Android notification channels for mode changes, batched events,
  and the foreground service, so users can tune their urgency independently.
- Surface credential-validation errors and refresh the displayed Ring mode when
  monitoring starts; saving credentials currently begins a refresh without
  reporting its result in the UI.
- Make selected person events configurable as immediate notifications. At
  present, only doorbells bypass batching.
- Keep the private Ring client behind the existing `RingService` boundary and
  add concise diagnostics, because endpoint and response changes are expected.

## First-version settings

- [x] Home Wi-Fi identifier.
- [ ] Delay before treating Wi-Fi loss as leaving home, for example a few minutes.
- [ ] Delay before treating reconnection as returning home, for example around 30 seconds.
- [ ] Motion grouping window and repeat cooldown.
- [ ] Which event types should bypass grouping and notify immediately.

The unchecked settings exist as defaults in `AutomationSettings`, but are not
yet persisted or editable in the app.

## Platform note

The target platform is Android. Use Android's background networking, Wi-Fi
connectivity, and local notification APIs, accounting for Android
background-execution and battery-optimization restrictions. The foreground
service and permission flow are implemented, but must be validated on the
target devices before release.
