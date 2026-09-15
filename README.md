# Ring Autopilot

Android starting point for the phone-first Ring mode automation described in
[`ring_phone_automation_project_plan.md`](ring_phone_automation_project_plan.md).

## Open in Android Studio

1. Open this directory as an existing project.
2. Let Android Studio use its bundled JDK 17 and sync Gradle.
3. Install Android SDK 37 if Android Studio prompts for it.
4. Run the `app` configuration on an Android 9 (API 28) or newer device.

The first screen lets you save a home Wi-Fi SSID and shows presence, Ring mode,
and automation status. Wi-Fi monitoring and delayed transitions are wired. Ring
authentication is intentionally represented by `UnconfiguredRingService`, so
no unofficial credentials or endpoints are embedded in the starter project.

## Source layout

- `presence/` — Android Wi-Fi presence detection.
- `ring/` — isolated Ring API contract and unconfigured first implementation.
- `automation/` — grace-period controller and mode-switch decisions.
- `events/` — event grouping and bypass policy.
- `notifications/` — local mode-change notifications.
- `storage/` — settings plus the contract for future encrypted token storage.
- `ui/` — Compose status screen and view model.

## Important platform work still to do

- Add an explicit permission/onboarding flow before relying on SSID access.
- Choose a background execution design and test it against the target phone's
  battery restrictions. An Activity-owned network callback is only a foreground
  starting point.
- Implement Ring login, token refresh, and mode endpoints behind `RingService`.
- Store Ring refresh tokens with Android Keystore-backed encryption.
- Connect Ring's event stream to `EventAggregator` and schedule summary alerts.
