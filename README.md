## المطوف الذكي — Smart Mutawwif 3.3.6

Religious visual companion for XREAL Eye + phone: optional Gemini landmark recognition, sign translation and questions about a selected snapshot, with a local Tawaf/Sa'i HUD counter. Galaxy Watch 8 Classic, navigation, group tracking and alerts remain additional features. Public project website: https://sm.hsaie.com with an illustrated interactive demo. [Public dashboard](https://sm.hsaie.com/dashboard) shows live general-group totals without login; [administrator workspace](https://sm.hsaie.com/dashboard/admin) retains individual details and management behind sign-in. The phone can join the general group by name or use an optional private invitation. Setup and limits: [religious companion guide](docs/RELIGIOUS_COMPANION_AR.md), [platform guide](docs/SARAB_PLATFORM_AR.md), [public dashboard](docs/PUBLIC_DASHBOARD_AR.md). Build both APKs with `.\build.ps1 -Test`.

**Current source (2026-09-10): `3.3.5-phone-framing`, code 21.** Visible Gemini labels are renamed Smart Assistant in all five languages; the underlying provider is unchanged. Recognition preview returns to the 3.3.4 fullscreen center-crop and matching markers; imported images remain FIT. The navigation camera initializes UVs on its first frame, maintains session/display geometry and GL texture binding, uses actual display rotation and requests ARCore autofocus. Bounded geometry/request diagnostics contain no images. Network routing, existing audio assets and prior timeout allowances are retained. Image upload and Gemini each have a 50-second deadline. Queue 8 s + upload 50 s + provider 50 s gives a 108-second application budget, with a 120-second response allowance for the client/proxy. The user confirmed that the previously installed version works again with Eye; the earlier connection failure cause remains unproven. Slow uploads receive a distinct error; the phone exposes request-phase diagnostics and retry countdown. [3.3.1 guide](docs/TIMEOUT_FIX_3.3.1_AR.md). First run asks for an explicit choice among Arabic, English, Urdu, Indonesian and Turkish before camera content or permissions; upgrades without a confirmed choice see it once. The system image picker lets the companion describe and answer questions about a selected image without glasses or camera permission. Import remains local until existing Gemini consent is enabled, and live frames do not replace the selected image. Recognized landmarks can receive an automatic spoken description with available curated remembrance and its qualification. A visible, persisted mute stops narration, reply and manual playback while leaving tap-to-talk recognition available. Gemini cloud speech can fall back to the available Android voice; actual availability and sound quality need separate evidence. Eye remains the default camera, with optional phone capture and the existing hologram HUD. [3.3.0 guide](docs/DEMO_COMPANION_3.3.0_AR.md), [validation and hardware limits](docs/DEVICE_VALIDATION_3.3.0.md).

<!-- release-verification:start -->
**Final phone build: `3.3.6-navigation-fix`, code 22.** 494 phone + 15 watch unit tests passed; lint 0 errors. Separate final-APK offline GLES-background and navigation-audio reports document their scope. Synthetic OES rendering does not establish ARCore tracking or physical camera availability. The 36 WAVs remain identical to 3.3.4. [3.3.6 guide](docs/NAVIGATION_CAMERA_3.3.6_AR.md).
<!-- release-verification:end -->

Navigation now uses 36 bundled Sulafat Arabic WAVs: 11 base cues including six directions, plus 25 distance buckets through 2000m. These cues play locally with no runtime synthesis/network request. Arbitrary destination names and uncovered distances retain installed offline Android Arabic TTS; their pronunciation remains device-dependent. Manual repeat uses the typed speech plan rather than display abbreviations. Phone navigation can speak automatically while foreground, targeted and unmuted; Eye keeps its connection requirement. Nonurgent cues wait for the current direction and distance to finish, even beyond the 6-second minimum interval. Arrival and manual repeat may interrupt. Phone navigation exposes mute/repeat controls; mute, onPause and shutdown cancel playback. The 50-second timeout allowances from 3.3.3 are retained. [Release guide](docs/ARABIC_NAVIGATION_3.3.4_AR.md).

**Previous delivered phone version (2026-09-08): `3.2.2-hologram-preview`, code15.** A light forest tint lets the camera show through, with pale ivory/mint text, a dark text shadow and a thin mint frame/corner brackets. Existing panel layout, manual reading/compact controls and fixed microphone/close actions remained. That APK passed433phone+15watch tests, lint with0errors, and3panel+1conversation checks using an offline emulator and synthetic image; pixel samples verified the background showing through. No blur or continuous decorative animation was introduced. Contrast depends on the camera image; physical XREAL readability and actual voice were not verified. [Historical hologram guide, APK hash and validation](docs/HOLOGRAM_PANELS_3.2.2_AR.md). No server/web deployment was needed for3.2.2.

**Previous delivered phone version: `3.2.1-ar-panels-preview`, code14.** Opaque ivory panels and larger dark-green text separated reading content from the camera image. Landmark details stayed inside the camera activity with a selected-snapshot caption and fixed voice action. Its433phone+15watch tests, lint0errors and8panel+3conversation cases apply to that APK, using an offline emulator and synthetic image. [Historical opaque-panel guide, hash and validation](docs/AR_PANELS_3.2.1_AR.md). Physical XREAL readability and actual voice were not verified by those checks.

**Previous delivered phone version: `3.2.0-live-companion-preview`, code13.** A visible camera microphone sends a spoken question with its captured visual context and reads the answer automatically. Local image tracking keeps recognized boxes aligned between Gemini responses; tapping an item opens reference information and sourced devotional content. Eye remains the default; the optional phone camera retains its native video preview. [Live companion setup and validation](docs/LIVE_COMPANION_3.2.0_AR.md), [previous camera performance fix](docs/PHONE_CAMERA_AGENT_FIX_3.1.3_AR.md).

# Smart Mutawwif — المطوف الذكي — offline AR campus navigation

**Historical Eye baseline (2026-09-08): `3.0.1-companion-preview`.** Direct XREAL Eye USB camera,
glasses IMU, mirrored camera/route view, and downloadable diagnostic logs are now
implemented. The user confirmed live Eye video on Fold 7, and their diagnostic log
confirms concurrent IMU reception. Version 2.3 moves the road chevrons farther ahead,
removes underfoot clutter and aligns the horizon explicitly along with north.
Real-road alignment still needs a physical check.
Read [the current Eye setup and limitations](docs/XREAL_EYE_SETUP.md) before testing.
Build the APK and checks with `.\build.ps1 -Test`. The historical V2/device reports
below do not verify the new Eye integration or full 6DoF.

**V2 (Campus Edition).** Offline AR app: pick a campus destination, point the
camera at the map board (which becomes the world origin), and follow a glowing
multi-waypoint path along the floor to a labelled 3D marker.

New in V2 — see [`V2_SETUP.md`](V2_SETUP.md) for setup:
- **"Select Destination" bottom sheet** with three campus destinations
- **ARCore Augmented Images**: a printed reference image becomes the world
  origin (0,0,0), so routes are fixed and repeatable without GPS
- **Waypoint routes**: real multi-segment paths with mitred corners, not a
  straight line
- **Floating 3D text label** naming the destination above the marker
- **Live remaining distance** measured along the route

V1's straight-path behaviour is still the automatic fallback when no
reference image is supplied, so the app works out of the box.

**Status: built, tested, and already installed and running on your Galaxy A16.**
The AR camera feed is live and the app works. A working `app-debug.apk`
(10.5 MB) is in `output/`.

---

# ☀️ MORNING CHECKLIST — nothing to install

**The app is already on your phone.** Just open **Smart Mutawwif — المطوف الذكي** from the app
drawer. There is nothing to drag, wire, or configure — no visual editor is
involved at any point.

### Try it (about 30 seconds)
1. Open **Smart Mutawwif — المطوف الذكي**. The camera feed appears immediately.
2. Point it at the **floor** — a well-lit floor with visible texture (tiles,
   carpet pattern, grain) works far better than a plain glossy surface.
3. Move the phone **slowly side to side** for 2–5 seconds. ARCore needs
   parallax to find the plane.
4. A blue glowing path draws along the floor, with a gold cube hovering at the
   end of it.
5. **Tap the cube** → the "Coffee Shop" card slides up. Tap **Close**.

If the hint stays on "Move your phone slowly to scan the floor", keep moving
slowly across a textured, well-lit patch of floor — that message is the app
correctly telling you it has not locked onto a plane yet.

### To reinstall or rebuild later

```powershell
cd "C:\Users\albre\Videos\سراب\test-app"
.\build.ps1 -Install
```

Install the existing APK without rebuilding:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r ".\output\app-debug.apk"
```

> **It must be a physical device.** An emulator has no ARCore and no real
> camera, so it correctly shows "This application requires the latest version
> of Google Play Services for AR" and stops there.

---

## ⚠️ The one thing that will confuse you: `gradlew` does not work here

**Do not run `.\gradlew.bat` directly in this folder. Use `.\build.ps1`.**

This folder sits under a path containing Arabic characters:

```
C:\Users\albre\Videos\سراب\test-app
                     ^^^^
```

The JVM on this machine **cannot load classpath JARs from a non-ASCII path**.
I verified this directly: the identical `gradle-wrapper.jar` loads fine from an
ASCII path and fails with `ClassNotFoundException` from this one. It is an
environment limitation, not a bug in the project, and no Gradle setting fixes
it.

`build.ps1` works around it by mirroring the project to
`%LOCALAPPDATA%\SarabVisionBuild`, building there, and copying the APK back to
`output/`. **Your source of truth stays here.**

*Permanent fix, if you want one:* move the project to an ASCII path (e.g.
`C:\dev\sarab-vision`) and `gradlew` will work normally. Nothing in the code
depends on the current location.

### Two other environment facts, already handled
- **Java:** Gradle 8.13 rejects your system JDK 25. `build.ps1` automatically
  uses Android Studio's bundled **JBR 21**. Nothing for you to change.
- **Android Studio:** if you open this project in the IDE, set the Gradle JDK
  to JBR 21 under *Settings → Build Tools → Gradle*, and expect the same
  non-ASCII path problem — the IDE hits it too.

---

## Build commands

| Command | Result |
|---|---|
| `.\build.ps1` | Debug APK → `output\app-debug.apk` |
| `.\build.ps1 -Install` | Debug APK, install and launch on the connected phone |
| `.\build.ps1 -Release` | Release APK (minified, **unsigned**) |
| `.\build.ps1 -Clean` | Wipe the build mirror and rebuild from scratch |

Run the unit tests:
```powershell
robocopy . $env:LOCALAPPDATA\SarabVisionBuild /MIR /XD .git build .gradle .idea output
cd $env:LOCALAPPDATA\SarabVisionBuild
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest
```

---

## Historical V1 validation (not the current 3.0 build)

Honest accounting, so you know exactly where you stand.

**Verified on a real phone — Samsung Galaxy A16 5G (SM-A166E), Android 16:**
- ✅ Installs and launches, **AR camera feed live on screen**
- ✅ ARCore session creates and resumes; camera connects
  (`CONNECT device 0 client for package com.sarab.vision`)
- ✅ Compose overlay composites correctly over the camera feed — the
  "Move your phone slowly to scan the floor" hint renders as designed
- ✅ Camera permission screen renders and the grant flow works
- ✅ No crashes, no exceptions in logcat while running

**Also verified on this machine:**
- ✅ Compiles cleanly — `BUILD SUCCESSFUL`, 10.5 MB APK
- ✅ **9/9 unit tests pass** (ray picking, AABB hits/misses, path layout,
  NaN guards, degenerate inputs)
- ✅ **No `INTERNET` permission in the built APK** — offline confirmed at the
  binary level
- ✅ ARCore-missing path degrades gracefully on an emulator (shows the
  official install prompt, process stays alive)

**Still needs you to walk around with it:**
- ⏳ Plane detection locking onto a real floor (needs a well-lit, textured
  floor and a few seconds of slow side-to-side movement)
- ⏳ The path + cube appearing, and tapping the cube to open the card
- ⏳ Sustained frame rate

### 🐞 Two real bugs were found and fixed on the device

Testing on your actual phone caught two things the emulator could not:

1. **Missing `HIGH_SAMPLING_RATE_SENSORS` permission** — ARCore samples the
   gyroscope above 200 Hz, which Android 12+ gates behind this permission.
   Without it `session.resume()` threw
   `FatalException: Failed to register sensor to queue 0` and **AR could not
   start at all**. Added to the manifest; it is an install-time permission, so
   there is no extra prompt for you.

2. **`session.resume()` crashed the app** — the resume path only caught
   `CameraNotAvailableException`, so ARCore's `FatalException` escaped
   `onResume` and killed the process. It now catches it, rebuilds the session,
   and retries twice before showing a readable message instead of crashing.

---

## ❗ Scope: Android only — iOS is not included

The brief asked for iOS **and** Android. **This delivers Android only**, and I
want that clear rather than buried.

iOS requires ARKit, Swift, and **macOS with Xcode**, none of which exist on
this Windows machine. No engine choice changes that — a Unity project would
still need a Mac to produce an iOS build.

To keep that cost low, all the non-AR logic lives in
`com.sarab.vision.core` with **zero Android/ARCore imports**, so it
transliterates to Swift directly, with its tests. Full plan:
[`docs/IOS-PORT.md`](docs/IOS-PORT.md).

## Why not Unity, which you recommended

Short version: **Unity is not installed here and cannot be installed
unattended** — it needs interactive licence activation plus a multi-gigabyte
download. Hand-written `.unity`/`.prefab` files are GUID-linked serialised
graphs that frequently fail to load, and every reference would still need
manual Inspector wiring.

That path would have left you with never-compiled C# and an hour of editor
work. This path left you a tested, installable APK tonight.

Full reasoning: [`docs/ADR-001-tech-stack.md`](docs/ADR-001-tech-stack.md).

---

## Project layout

```
app/src/main/java/com/sarab/vision/
├── ArActivity.kt              Session lifecycle, permissions, Compose host
├── core/                      ← pure Kotlin, no Android/ARCore (iOS-portable)
│   ├── Geometry.kt              Vec3, Ray, AABB intersection (tap picking)
│   ├── Waypoints.kt             Destinations, campus routes, resampling,
│   │                            remaining-distance, image-mounting maths
│   └── Poi.kt                   Straight-line path helper (V1 fallback)
├── ar/
│   ├── ArSceneRenderer.kt     GL thread: origin acquisition, routes, raycast
│   └── AugmentedImageSupport.kt  Builds the image DB from assets at runtime
├── render/
│   ├── GlUtil.kt              Shader compile/link helpers
│   ├── CameraBackgroundRenderer.kt   Camera feed (external OES texture)
│   ├── PathRenderer.kt        Mitred floor ribbon, arc-length pulse
│   ├── MarkerRenderer.kt      Hovering, spinning shaded cube
│   └── LabelRenderer.kt       Billboarded 3D text label (Canvas → texture)
└── ui/
    ├── DestinationSheet.kt    "Select Destination" sheet + distance pill
    ├── PoiCard.kt             The 2D info card
    └── StatusOverlay.kt       Scan hints, permission / unsupported screens

app/src/main/assets/           ← drop your reference image here (V2_SETUP.md)
app/src/test/                  21 unit tests (picking, routes, mounting)
V2_SETUP.md                    Reference image + route editing guide
docs/ADR-001-tech-stack.md     Why native Android, not Unity
docs/IOS-PORT.md               ARKit port plan
build.ps1                      Build helper (works around the Arabic path)
```

## Tuning

Behaviour constants are at the top of `ar/ArSceneRenderer.kt`:

```kotlin
private const val PATH_LENGTH_M = 4.0f     // path length in metres
private const val MARKER_SIZE_M = 0.28f    // cube edge length
private const val MARKER_HOVER_M = 0.35f   // cube height above the floor
```

The POI text ("Coffee Shop" and its details) is in `core/Poi.kt`.

Path colour is in `PathRenderer.draw()`; cube colour in `MarkerRenderer.draw()`.

## Performance choices (for mid-range phones)

- **No Sceneform/Filament** — archived by Google, ~10 MB, real GPU cost. Raw
  GLES 2.0 instead.
- **Depth API disabled** — meaningful cost, no benefit for a flat path + cube.
- **Light estimation disabled** — the marker uses baked per-face shading.
- **Path geometry rebuilt only when the anchor moves**, never per frame.
- **Camera UVs recomputed only when display geometry changes.**
- **ARCore ABIs only** (`arm64-v8a`, `armeabi-v7a`) to keep the APK small.
- Path drawn as a **triangle-strip ribbon**, not `GL_LINES` — line width is
  clamped to 1px on most Android drivers, which would make it a hairline.

## Known limitations

- One hard-coded POI (offline MVP, no backend by design).
- The path is a straight line — no obstacle avoidance or routing.
- Placement happens once, on the first good floor plane. To re-place it,
  restart the app (`ArSceneRenderer.resetPlacement()` exists and is wired if
  you want to add a reset button).
- Release builds are **unsigned**; add a signing config before distributing.
