# Ring Phone Automation – Project Plan

## Goal

Build a phone-first app that automatically switches Ring between **Away** and **Disarmed** based on whether the phone is home, confirms mode changes with a notification, and groups or throttles noisy Ring notifications.

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

1. Create the basic mobile app and a simple screen showing detected presence and current Ring mode.
2. Implement Ring authentication and `RingService`; verify that the app can read the current mode and manually switch Away/Disarmed.
3. Add home Wi-Fi presence detection with an away grace period and a shorter arrival confirmation period.
4. Connect presence changes to Ring mode changes and show a local notification after each successful automatic switch.
5. Add Ring event intake and notification grouping/throttling.
6. Tune the delays and notification rules from real use.

## First-version settings

- Home Wi-Fi identifier.
- Delay before treating Wi-Fi loss as leaving home, for example a few minutes.
- Delay before treating reconnection as returning home, for example around 30 seconds.
- Motion grouping window and repeat cooldown.
- Which event types should bypass grouping and notify immediately.

## Platform note

The target platform is Android. Use Android's background networking, Wi-Fi connectivity, and local notification APIs, accounting for Android background-execution and battery-optimization restrictions.
