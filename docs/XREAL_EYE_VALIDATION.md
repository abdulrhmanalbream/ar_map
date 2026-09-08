# Eye 2.3 validation — 2026-09-08

Current artifact: `output/app-debug.apk`, version `2.3-eye-preview` (versionCode 5), arm64-v8a, 39,490,354 bytes.

SHA-256: `522BBFFA10121F7D237619669610E998AF5B7685FF186F86FD23D123A9EE55C6`.

## Changes and evidence

The user's second log, `sarab-eye-20260908-113457.txt`, contains a version 2.2 session after older 2.1 events. In that session Eye recovers once at startup, then publishes 1,120 images continuously for about 85 seconds at roughly 13.2 FPS; IMU remains calibrated near 1,000 reports/sec. The user now reports that the arrow shape is unattractive and appears underfoot. The log does not contain poses or image coordinates and cannot prove a camera mounting offset.

Version 2.3 replaces solid triangles and the underfoot route line with at most two open rounded chevrons, starting at about 9 m along the remaining real route. Projected footprints entering the bottom 27% are omitted rather than shifted artificially upward. Existing route bends and image crop are preserved.

The IMU's automatic startup neutral could be captured while the user looks down at the phone. Explicit alignment previously calibrated north only. It now captures session-scoped pitch and roll references too, under an explicit instruction to look at a distant point at eye level with the head straight. Subsequent head rotation is still live; disconnect/background invalidates the complete alignment. No guessed fixed camera mounting angle was introduced.

## Checks

- Final `.\build.ps1 -Test`: passed, 336 JVM tests, zero failures/errors. Five additional tests cover underfoot exclusion across viewports/poses, short routes, startup tilt correction, subsequent head motion and angle wrapping/invalid values.
- Android lint: zero errors, 134 warnings, six information items. `git diff --check`: passed.
- Temporary emulator fixtures render the production screen and overlay with explicitly marked synthetic image/pose data. Visually inspected the open chevrons on wide and near-square viewports and a right turn. No footprint is drawn at the feet; the route line is absent.
- Final APK installed and launched on the isolated Android 17 emulator. No physical glasses were connected. The final manifest and all DEX files were checked to exclude the temporary fixture Activity.
- The copied `sarab-eye-2.3-debug.apk` is identical to the current artifact. Screenshots remain under `%LOCALAPPDATA%/SarabEyeValidation/fixture-2.3-*.png`.

The new horizon calibration and exact overlay-to-road alignment need physical verification. Floor height/FOV remain approximate; this is not full Eye positional 6DoF or detected-ground anchoring. Install as an update and use **معايرة الاتجاه والأفق** while looking straight ahead.

---
# Eye build validation — 2026-09-08

Artifact: `output/app-debug.apk`, version `2.2-eye-preview` (versionCode 4), arm64-v8a, 39,691,448 bytes.

SHA-256: `742F63419B0EB54D07942176613B5530C2CAC5944607D908245AA36700B4201D`.

## Physical evidence from version 2.1

The user supplied `sarab-eye-20260908-105849.txt` from Samsung SM-F966B / Android 16 and confirmed that Eye video appeared. Their remaining complaints were clustered arrows, a small video viewport and image glitches.

The log shows concurrent Eye MJPEG capture (1920×1080 decoded to 960×540, around 13 published frames/sec) and glasses IMU reception (around 1,000 accepted reports/sec). It also records UVC transfer failures and automatic composite USB reactivation after repeated stream errors. Publication counters do not prove display timing. The log has no image payloads, so it cannot identify the exact visual corruption.

## Version 2.2 changes

- Immersive system-bar handling and full-viewport centred camera crop, with the identical crop transform applied to route projections.
- At most three separated perspective chevrons over the nearby real route. Preserves bends and stops drawing a floor trail when the user is too far from the recorded route.
- Floating controls can be hidden by tapping the image; ambiguity and arrival information remain available.
- UVC descriptor parsing is restricted to the selected VideoStreaming interface.
- Incomplete JPEGs resynchronize at the next image; UVC error/overflow data and affected buffered prefixes are discarded.
- Existing UVC interfaces recover without resetting the whole composite glasses device. USB ownership is serialized across Activity instances.
- Peripheral/display configuration changes are handled in place. Logs identify Activity/controller instances, intentional observation shutdown, published frame rates and damaged transfers.

## Automated checks

The final `.\build.ps1 -Test` passed with Android Studio JBR 21:

- `assembleDebug`: passed.
- `testDebugUnitTest`: 331 tests, zero failures/errors. Includes 19 new camera integrity/descriptor tests and eight new fullscreen crop/route tests.
- `lintDebug`: zero errors, 134 warnings and six information items.
- `git diff --check`: passed.
- APK package metadata: versionCode 4, versionName 2.2-eye-preview, arm64-v8a.

## Android UI and export checks

Used an isolated read-only Pixel emulator, Android 17 / SDK 37 with ARM translation. No glasses hardware was attached.

- Final delivery APK installs and launches the real Eye navigation Activity successfully.
- No-device state, immersive system bars and accessible log export checked.
- Saving through Android's document picker created a readable version 2.2 diagnostic file with the new Activity lifecycle identifiers.
- The real camera screen and route overlay were additionally rendered using a temporary debug Activity with explicitly labelled synthetic image/pose data. Checked 16:9 and 4:3 source crops, wide 2992×1344 and near-square 1800×1600 viewports, a right-angle route, separated chevrons, hiding controls, settings, and visible camera content while uncalibrated.
- The temporary fixture existed only in the build mirror. The final build resynchronized the production source; both the final APK manifest and all DEX files were checked to exclude the fixture Activity.

Screenshots and the exported emulator diagnostic remain locally under `%LOCALAPPDATA%/SarabEyeValidation`. They are test fixtures, not captures from the user's glasses.

## Remaining physical checks

The new corruption/recovery paths and fullscreen composition still need verification on Fold 7 + One Pro + Eye. The previous user log confirms that the transport works; it does not prove that version 2.2 resolves every glitch, calibrates camera axes precisely or has acceptable thermal/latency behaviour.

Full Eye 6DoF positional integration is not implemented by this native IMU bridge. The hardware supports it through the appropriate official SDK. See [setup and limitations](XREAL_EYE_SETUP.md).

