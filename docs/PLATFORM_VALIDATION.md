# Sarab companion 3.0 verification — 2026-09-08

## 3.0.1 visible product rename

Product branding is now **المطوف الذكي / Smart Mutawwif**. Both APKs have the Arabic launcher label, version code 7, unchanged package ID/signing certificate, and preserve existing application data and Wear Data Layer identifiers. `build.ps1 -Test` passed phone/watch builds, existing JVM tests and lint. Phone permission dialog and companion header were visually verified on the isolated emulator. The longer web brand passed 14 local desktop/mobile viewport checks and live authenticated Chrome checks without overflow, browser errors or CSP errors; 10 web tests and 8 assistant tests passed.

The deployed Gemini model identified itself as «أنا المطوف الذكي، رفيقك المساعد في التنقل الإرشادي.» in a synthetic identity probe. A guarded transaction changed only the original default group's and administrator's display names; fingerprints confirmed credentials, IDs, enrollment codes, sessions and all other rows unchanged. The group had no existing enrolled devices. Live dashboard login confirmed both names. Only the platform's own API container was recreated; every other container ID, host nginx file hash and nginx master uptime matched the pre-update baseline.

New APK SHA256 values:

- `smart-mutawwif-phone-3.0.1-debug.apk`: `9C0922F5C723A1D3BA6080773F5EB22D4E8A4FF5A31D0C78EE151E9A100774B0`
- `smart-mutawwif-watch-3.0.1-debug.apk`: `5AF8C998F0AC6123D7642EE0BC299F4F536FD3E80C860875A5581E02EFBCDC2D`

The remaining sections record the earlier 3.0 functional verification; earlier filenames and brand names are historical.

This records the companion, server and Wear OS checks, separately from earlier Eye camera work in `XREAL_EYE_VALIDATION.md`.

## Build and protocol checks

- `build.ps1 -Test`: both debug APKs built; phone and watch Android lint passed.
- Phone JVM: **342 tests**, zero failures/errors. Includes lap-envelope normalization and durable outbox partial-delivery, throttling, invalid receipt and expired-session cases.
- Watch JVM: **15 tests**, zero failures/errors. Includes manual counter behavior, protocol validation and group-binding isolation.
- Node 24 backend: **20 tests** passed, covering authentication/authorization, enrollment, privacy, telemetry validation, alert delivery/acknowledgment/idempotency, limits and assistant behavior.
- Web: **10 tests** passed; production Vite build passed; npm audit reported zero vulnerabilities at verification time.
- Gemini was exercised on the deployed server with actual provider requests. REST JSON output requires the MIME enum `APPLICATION_JSON` for the configured response format. Valid Arabic navigation responses selected only catalog IDs. Empty-catalog responses proposed no invented destination.

## Phone runtime

An isolated Android emulator, serial `emulator-5580`, was used. This is not a physical Fold 7 / Eye / Galaxy Watch test.

- Enrolled a synthetic QA user through the phone UI against the deployed server.
- A typed English destination request produced an Arabic Gemini reply. Confirming its proposed catalog destination returned to Eye navigation with that destination selected.
- Injected a synthetic persisted Wear lap event, including transport metadata, into the emulator's own application preferences. Its Tawaf count of 3/7 reached the server and UI. This verifies phone normalization, not Bluetooth/Data Layer delivery on real hardware.
- A supervisor alert targeted only the synthetic QA device. The phone displayed it, and pressing acknowledgment updated the server's confirmed-recipient count.
- The phone's help button created a group help event and `needs_help` state.
- With location sharing enabled, synthetic coordinates reached the server. The stationary-location regression was fixed by requesting zero minimum displacement at a five-second interval; timestamped location samples continued from 09:41:14 UTC to 09:42:39 UTC at the same position, exceeding the 60-second freshness cutoff.
- Turning location sharing off in the UI yielded `sharingEnabled: false` and `location: null` on the server.
- Deleting only the temporary QA group's records invalidated its device session. The running phone returned to enrollment, cleared group credentials and stopped the foreground service.

The guarded cleanup checked exact group name and allowed QA member names inside a transaction. It removed 2 test devices, 2 device sessions, 2 alerts, 2 recipients, 1 enrollment code and the 1 test group. Other groups and the administrator remained unchanged.

After adding the domain, the final APK was installed with fresh emulator app data. The enrollment form contained `https://sm.hsaie.com` without a port. Enrollment through the Android UI succeeded against that address with normal platform TLS validation. This second temporary group, its one test device/session and enrollment code were then removed using the same guarded cleanup.

Final artifact SHA256:

| APK | SHA256 |
| --- | --- |
| sarab-phone-3.0-debug.apk | `91701F16DF465DB2CF6ECC2E96B57AC0B29067C84ABFE7CA7ECC20B67936DB7F` |
| sarab-watch-3.0-debug.apk | `3EBC9ADD343586CF906687605A6CDE296637CB5F18AC410147267AB9089182D9` |

Both APK signatures verified and use the same signing certificate, application ID `com.sarab.vision` and version code 6. The phone package is ARM64 with min API 24/target 35; watch min API 30/target 35, required watch hardware feature and no native/ARCore libraries. Both are debug builds. Scans found no source/environment/private files or known secret patterns in either APK, deployed frontend bundle or nonignored source files; pattern scanning is not a proof against every possible arbitrary secret.

## Watch UI and browser

- Round 450×450 emulator layout checked counter increment/undo, persistence, independent Tawaf/Sa'i modes, reset confirmation/cancel, offline help, denied-notification recovery, alert navigation and explicit acknowledgment. The UI smoke environment was a phone-system emulator configured to a watch-sized display, not a paired Wear OS device.
- Production dashboard checked in Chrome with normal certificate validation: login/logout, assets/API responses, Arabic layout, desktop/mobile rendering and health view. No page errors, CSP violations or failed same-origin requests; no mobile horizontal overflow.
- Browser screenshots are under `platform/web/playwright-results/production/` and are not deployed. Test credentials are stored separately outside the repository.

## Domain and server isolation

- `https://sm.hsaie.com` serves the dashboard on standard 443 with a trusted domain certificate, valid through 2026-12-07. Actual Chrome login/logout, desktop/mobile and API checks passed at this domain with normal certificate verification.
- The HTTPS IP address on 9443 and domain on 9443 remain compatible. HTTP domain requests redirect to HTTPS.
- A new `/etc/nginx/conf.d/sm.hsaie.com.conf` forwards only this hostname to Sarab API at `127.0.0.1:9087`. That API port is not publicly bound. Existing nginx virtual-host files retain their baseline SHA256 hashes.
- Host nginx passed configuration validation before graceful reload. Its master PID and service start time remained unchanged. The 14 original container IDs remained unchanged; Sarab's own edge also retained its ID. Only the new Sarab API container was recreated for its loopback port binding.
- Sarab uses its own Compose project, SQLite database, environment file and certificate renewal state. API and edge resource limits remain in place.
- The private certificate renewal check completed successfully for both IP and domain certificates. Since copies were unchanged, it did not reload the edge. The renewal timer is active. On future domain certificate changes, validation precedes the graceful host nginx reload.
- Both temporary QA groups were removed, leaving the intended real group and no test devices. The temporary administrator QA session was explicitly logged out and then returned 401 when checked.

## Limits and housekeeping

Lap counting in this version is manual, with persistence, undo and reset confirmation. It does not automatically infer a completed Tawaf/Sa'i lap. Actual Watch 8 Classic pairing, background delivery and vibration remain to be checked with the user's devices, including notification permission and Do Not Disturb settings.

The bundled route catalog is the project's existing surveyed catalog. Haram routes and meeting points require their own approved map data and field testing. Eye video is processed locally and not uploaded to the assistant.

Automatic approval review rejected deletion of a throwaway local UI-test folder, `C:/Users/TechTroniX/AppData/Local/Temp/sarab-ui-test-Nhgzxl`, as blocked by policy. The test server was stopped and the directory was left intact; it contains dummy test data, not the production Gemini configuration. Production QA records were cleaned separately with the guarded script described above.
