# المطوف الذكي: Wear OS bridge

The `:wear` APK displays the product name **المطوف الذكي** and is a native Wear OS application for the Galaxy Watch 8 Classic (minimum API 30). Version 3.0.1-watch-preview uses version code 7. It retains application ID `com.sarab.vision`, the same debug signing identity as `:app`, and no ABI split or AR/camera libraries. Both APKs must be signed by the same key. The watch counts manual confirmations independently for Tawaf and Sai, preserves both sessions, caps at seven, supports undo, and confirms a reset. It does not claim automatic lap detection, GPS precision, or religious completion.

## Root phone integration

Add `implementation("com.google.android.gms:play-services-wearable:20.0.1")` to `app/build.gradle.kts`. Coroutines StateFlow is already supplied by the phone's lifecycle dependencies. Add inside the phone application manifest:

```xml
<service android:name=".wear.PhoneWearListenerService" android:exported="true">
    <intent-filter>
        <action android:name="com.google.android.gms.wearable.DATA_CHANGED" />
        <action android:name="com.google.android.gms.wearable.MESSAGE_RECEIVED" />
        <data android:scheme="wear" android:host="*" android:pathPrefix="/sarab/watch/" />
    </intent-filter>
    <intent-filter>
        <action android:name="com.google.android.gms.wearable.CAPABILITY_CHANGED" />
        <data android:scheme="wear" android:host="*" />
    </intent-filter>
</service>
```

No phone Bluetooth/location permissions are required by this bridge. Initialize `PhoneWearBridge.get(context)` when the platform integration starts; this advertises the dynamic `sarab_phone` capability. The watch advertises `sarab_watch` in its resource array. A paired watch without المطوف الذكي installed does not count as connected. Internal identifiers retain their existing names so updating the display name preserves pairing, saved counters, and pending messages.

* `state` (also `snapshot`) is `StateFlow<PhoneWearSnapshot>`: `connected`, nullable `batteryPercent`, nullable `lap: JSONObject`, `lastSeenAt: Long` (phone receipt epoch milliseconds), `pendingEventCount`.
* `refreshConnection()` is asynchronous; call during platform polling. It also replays durable watch DataItems and retries pending alerts.
* `drainPendingEvents()` returns a **non-destructive** list of `WatchEvent(id, type, payload: JSONObject)`. For `help`, POST device alerts with the event ID as `clientId`. For `ack`, POST `payload.alertId` to the acknowledgement endpoint. A `status` with `deliveredAlertId` confirms durable watch receipt, without user acknowledgement. Ordinary `status` and `lap` are already stored for telemetry. Call `completeEvent(id)` only after processing succeeds. The optional `onEventsAvailable` hook and package-only `com.sarab.vision.wear.EVENT_AVAILABLE` broadcast announce pending work; the hook may run off the main thread.
* `sendAlert(id, kind, message, sourceName, createdAt): Boolean` queues a server alert durably; true means accepted locally, not delivered to the watch. False means invalid input or full queue. The incoming status event above confirms watch delivery.
* `sendState(groupName, connected, lastSyncedAt)` supplies group/cloud status, where `connected` means the phone platform service can reach the server. Update this on polling failures as well as successes. Watch cloud status expires after 60 seconds without a new state.
* `clearCloudBinding()` must run when a login is invalidated and after a new enrollment. It persists a new cutoff, discards queued help/ack/delivery events and outgoing cloud alerts, and preserves lap counts plus received-ID deduplication. Stop any in-flight platform sync before replacing its account token: clearing a queue cannot retract an event already taken by a worker.

New watch events include `createdAtMillis`. Following a binding cutoff, group commands created before it (or legacy commands with no valid creation timestamp) are discarded before they can reach platform callbacks. Their transport receipt explicitly says `discarded_binding_changed`; the watch stops retries and explains that the old help request was cancelled, without claiming cloud submission. Lap and plain battery events remain independent of account binding. This comparison uses the paired devices' wall clocks; keep phone/watch clocks synchronized.

Lap messages add `revision` and `startedAt` (epoch milliseconds) to the documented contract. These disambiguate out-of-order messages and restarted sessions. The phone rejects malformed/out-of-range JSON before persistence and rejects older lap snapshots. No device location, auth token, or camera bytes are transmitted by this module.

## Delivery behavior

Messages use the contract paths `/sarab/watch/event`, `/sarab/watch/alert`, and `/sarab/watch/state`. Because MessageClient has no retry guarantee, small JSON copies are also persisted as DataItems at `/sarab/watch/outbox/{id}` and `/sarab/watch/alerts/{id}`. The receiver persists before sending `/sarab/watch/received` plus `/sarab/watch/receipts/{id}`. The watch removes its outbox item only after phone receipt. Each queue is capped at 64; latest telemetry coalesces while help/ack are never evicted. Pending messages retry during foreground checks and capability changes; Play services synchronizes DataItems after reconnection even while the UI is closed. No app can guarantee delivery while its device is powered off or Play services unavailable.

Alert IDs are persisted before the vibration and retained in a bounded set of 2,048 IDs; unread alerts stay on the watch until the user presses “وصلني التنبيه”. Incoming alerts use a dedicated high-importance notification with an open/confirm action; the UI updates when foreground. There is no background activity launch or full-screen-intent override. Notification permission is requested on first opening and can be enabled later from the counter screen. The watch uses a distinct three-pulse notification haptic, subject to device/DND policy. Counter taps use the system confirmation haptic.

## Build and device acceptance

Root owns the build mirror. Build `:wear:assembleDebug :wear:testDebugUnitTest :wear:lintDebug` with the same JBR 21 environment as the phone and copy `wear/build/outputs/apk/debug/wear-debug.apk`. Install the phone and watch APKs on their respective devices, using the Galaxy Watch's wireless ADB for its APK. Pair through Samsung's normal watch setup before testing.

Physical acceptance: increment/undo/restart each mode; confirm reset and retain the other mode; close/reopen the watch app; enqueue help while disconnected and confirm one server alert after reconnection; send an admin alert twice and observe one vibration; read the notification without pressing confirm and verify no user acknowledgement; press confirm and verify eventual server acknowledgement; deny notification permission and verify the in-app alert remains; restart both devices with pending work. Hardware receipt and vibration have not been verified without the actual watch.

Official sources checked: [Data Layer setup and matching package/signature](https://developer.android.com/training/wearables/data/overview), [MessageClient has no built-in retries](https://developer.android.com/training/wearables/data/client-types), [Manifest listener filters and background callbacks](https://developer.android.com/training/wearables/data/events), [Durable DataItem synchronization](https://developer.android.com/training/wearables/data/sync). The protocol/UI code in this module is original project code.
