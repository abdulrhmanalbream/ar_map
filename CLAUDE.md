# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Smart Mutawwif (المطوف الذكي; formerly Sarab Vision) — an offline-first AR campus navigation app for Android (Kotlin, `:app` phone and `:wear` watch modules, package `com.sarab.vision`). The user picks a campus destination; far away they get a compass arrow and distance, close up ARCore draws a glowing path on the real ground to the building's door. UI text is Arabic (RTL). The project evolved V1 (straight path) → V2 (printed-marker anchored routes) → V3 (current: GPS + compass far, AR near). `V3_CAMPUS.md` (Arabic) describes the current design; `README.md` largely describes V2 and references the project's old location under a non-ASCII path — trust the code over the README where they disagree.

## Build and test commands

Use `build.ps1` (PowerShell), not `gradlew` directly:

```powershell
.\build.ps1              # debug APK → output\app-debug.apk
.\build.ps1 -Install     # build + adb install + launch on connected phone
.\build.ps1 -Release     # release APK (minified, unsigned)
.\build.ps1 -Clean       # wipe the build mirror and rebuild
```

`build.ps1` exists for two reasons that still apply: the system JDK is 25, which Gradle 8.13/AGP rejects, so it sets `JAVA_HOME` to Android Studio's bundled JBR 21 (`C:\Program Files\Android\Android Studio\jbr`); and the build is ABI-split (arm64-v8a / armeabi-v7a, no universal APK), so it picks the APK matching the connected device's ABI. It mirrors the source to `%LOCALAPPDATA%\SarabVisionBuild` and builds there (a workaround for the repo's former non-ASCII path; harmless now).

Unit tests (JUnit 4, all in `app/src/test`, pure JVM — no device needed):

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat testDebugUnitTest
# single class:
.\gradlew.bat testDebugUnitTest --tests "com.sarab.vision.core.GuidanceTest"
```

Manual AR testing requires a physical ARCore device — an emulator has no ARCore and stops at the "requires Google Play Services for AR" screen. Install without rebuilding: `adb install -r .\output\app-debug.apk`.

## Architecture

### The `core/` package is pure Kotlin — keep it that way

`app/src/main/java/com/sarab/vision/core/` has **zero Android/ARCore imports** by design: it holds all navigation math (geodesy, guidance modes, routing, projections, Arabic search, calibration state machines) so it can be transliterated to Swift for a future iOS port (`docs/IOS-PORT.md`) and unit-tested on the JVM. Nearly every test in `app/src/test` targets it. Put new logic here whenever it doesn't need Android APIs; the Android layers (`ar/`, `render/`, `ui/`, `data/`, `loc/`, `map/`) should stay thin wiring around it.

### Guidance state machine (the heart of the app)

`core/Guidance.kt` picks a `GuidanceMode` from GPS distance to the target's nearest **door** (`approachPoint`, not the building centroid — pins were surveyed 40–50 m from entrances):

- `Compass` (> ~30 m): arrow + bearing + distance — ARCore can't know the ground that far out
- `ArApproach`: ARCore draws a short ground path (`ar/CampusArRenderer.kt`)
- `Ambiguous`: within arrival range but another landmark is < 25 m away (`GPS_DISTINGUISHABLE_M`) — GPS error can't distinguish them, so the UI **asks the user to confirm visually** instead of guessing
- `Arrived` / `NoFix`

The ambiguity handling is a deliberate product decision (see `V3_CAMPUS.md`); don't "simplify" it to nearest-wins.

### State and activities

- `CampusApp` — process-wide singleton holding the one `CampusState`. Application-scoped because it owns live sensor streams (GPS `loc/LocationProvider`, compass `loc/HeadingProvider` with true-north correction) that can't ride in Intents.
- `CampusState` — all navigation state as Compose `mutableStateOf`; deliberately **not** a ViewModel (sensors/AR are Activity-lifecycle-bound; a surviving component would create two sources of truth). `AppMode` enum: NAVIGATE / LIST / MAP / SURVEY / PATHS.
- `ArNavActivity` — the launcher; camera-first V3 nav screen (compass bar, calibration flow, sign OCR, tour overlay).
- `CampusActivity` — secondary screens: landmark list, MapLibre map, survey mode, path editor.
- `ArActivity` — the legacy V2 printed-marker AR screen, kept because image anchoring beats GPS at close range.

### Rendering

Raw OpenGL ES 2.0, no Sceneform/Filament (archived, ~10 MB, GPU cost — `docs/ADR-001-tech-stack.md`). `render/` holds the primitives (camera background on an OES texture, mitred triangle-strip path ribbon — GL line width is clamped to 1px on Android, arrows, cube marker, Canvas-to-texture billboard labels). `ar/CampusArRenderer.kt` is the V3 GPS-aimed renderer; `ar/ArSceneRenderer.kt` is the V2 image-anchored one; `ar/SignReader.kt` reads building plaques with ML Kit OCR (the *bundled* model variant, deliberately — the play-services variant downloads its model on first use, which an offline app can't tolerate).

### Display glasses HUD

The default is now **Eye camera + glasses IMU with full-screen mirroring**, implemented by `glasses/camera/`, `glasses/motion/`, `ui/GlassesCameraScreen.kt` and `ui/GlassesRouteOverlay.kt`. `GlassesDisplay.kt` defaults to mirror and only opens the old black `GlassesHud` Presentation when the phone-mode user explicitly chooses it. Samsung must be in screen-mirroring mode to mirror the phone composition. Display output, USB camera capture and glasses motion are separate capabilities; do not infer that an external display has no sensors.

Since3.1.2, `phonecamera/` supplies an optional Camera2 back-camera source for the same religious companion HUD, without glasses or ARCore. `religious_camera_source` defaults to Eye independently of the legacy navigation camera preference. Source changes clear the retained scene and disable cloud sharing until fresh consent. Phone rotation is for conservative scene freshness only and must not populate Eye/IMU telemetry. Camera2 releases its device before the separate phone AR navigation session resumes.

Since3.1.3, phone live video renders through a native TextureView, while separate low-rate YUV snapshots feed analysis. Do not drive phone live preview through the analysis Bitmap flow. Captured Gemini answers survive pose/signature changes; those changes only hide live markers. Manual recognition pins and opens the returned snapshot. Consent, source, binding and lifecycle invalidation must still reject late answers. The debug-only delayed-response fixture uses synthetic images and an in-process transport override on an isolated emulator, never production enrollment or uploads.

Since3.3.6, religious recognition restores its pre-3.3.5 full-screen center-crop preview and matching live marker coordinates; imported photos retain Fit. The user explicitly rejected the 3.3.5 Fit change because the actual image problem was in the separate ARCore navigation screen. Do not conflate the native Camera2 recognition preview with the ARCore OES camera background. Navigation must initialize UV coordinates on the first valid frame even without a display-change flag, bind camera textures once per session/GL context, and apply the current viewport/rotation before session.update. ARCore navigation requests autofocus; native recognition keeps its prior settings. Preserve native high-rate preview and separate low-rate analysis.

Since3.2.0, the shared HUD speech runtime provides tap-to-talk, automatic question submission and revision-gated reply playback on both the live camera and item detail. A spoken live question holds the photograph from the mic tap, gets the next request slot, and keeps recent conversation history without pinning background detections. A selected item retains its own photograph. Microphone permission pauses retain the selected image temporarily; actual ON_STOP still clears it. Worker-side160px image tracking replays bounded frame history, rejects ambiguity/occlusion, and never presents lost eligible targets as stationary fallbacks. Phone local samples are4fps; cloud recognition is6s minimum while acquiring,18s while a scene stays matched, with bounded failure backoff. Catalog devotional texts and links are server-owned, not generated display actions. Consult `docs/LIVE_COMPANION_3.2.0_AR.md` and its validation limits before claiming field performance.

Eye mode closes phone ARCore and explicitly owns CampusState heading. The user aligns head direction to the phone compass once; subsequent phone sensor/demo updates cannot replace the glasses heading. Stale/disconnected samples clear heading and suppress guidance. The raw open IMU protocol supplies estimated 3DoF rotation; the official XREAL SDK supports One + Eye 6DoF, but its full positional tracking is **not implemented** here. FOV/eye height and ground placement are approximations. See `docs/XREAL_EYE_SETUP.md` for exact scope, physical acceptance checks and source licenses. Never describe these APKs as device-verified until tested on the actual Fold 7 + One Pro + Eye.

`diagnostics/DiagnosticLog.kt` stores bounded local logs and exports through Android's document picker. Log connectivity, permission, codec, rates and failures; never log camera payloads/images, GPS coordinates, device serials, Wi-Fi names or whole-system logcat. Use `.\build.ps1 -Test` for APK + unit tests + lint; it also handles the Windows Java socket temp-path issue.

Eyes-free control: every input source — XREAL Eye air gestures (which arrive as HID mouse scroll/click, firmware 1.9.1+), volume keys, Bluetooth remotes via `MediaSession` — is a dumb translator into the five `HudCommand`s consumed by the pure state machine in `core/HudMenu.kt` (destination picker, ambiguity chooser, arrival suggestion; fully unit-tested). Menu sync keys on guidance *transitions*, not states, so a dismissed menu doesn't reopen every tick. Since3.3.6 automatic Arabic navigation speech works on the phone as well as connected glasses, only while navigation is foreground, a target is selected and audio is unmuted. Recognition owns its separate assistant speech. Ordinary changed cues wait until the current complete direction/distance finishes without consuming VoiceState while busy; urgent arrival/ambiguity and explicit repeat may interrupt. Keep distance buckets, deduplication and the minimum six-second interval. Volume keys and mouse events remain claimed only while `glasses.active`.

Since 3.3.4, navigation uses 36 bundled Arabic WAV clips in `assets/navigation/ar/`, selected by the pure `core/NavigationSpeech.kt` plan. `VoiceGuide` sequences local clips with installed offline Arabic TTS only for custom names and uncovered distances. Manual repeat uses the spoken plan rather than display abbreviations. New cues, mute, activity pause and shutdown cancel both active audio and pending segments; keep the existing `VoiceCue` thresholds, distance buckets and six-second policy. The debug-only offline emulator fixture verifies playback/cancellation, not human pronunciation quality or physical XREAL audio. See `docs/ARABIC_NAVIGATION_3.3.4_AR.md` and its exact artifact evidence.

### Data and routing

- `data/LandmarkStore.kt` — landmarks as plain JSON in the app's private files dir, seeded from `app/src/main/assets/landmarks.json`. **If you add entries to the bundled seed, bump `CURRENT_SEED_VERSION`** in LandmarkStore.kt or existing installs never see them (seed merges into on-device data without clobbering user surveys).
- `core/PathNetwork.kt` — offline A* routing over a hand-drawn campus graph (drawn in the PATHS editor screen); per-edge foot/bike/car flags, so a `TravelMode` filters edges rather than switching profiles.
- `data/OverpassClient.kt` — optional OSM path import, tries three Overpass mirrors in order.
- Map tiles via MapLibre (`map/`), downloaded once and cached. Navigation (GPS, compass, routing, landmarks) remains offline. Opt-in group telemetry and Gemini assistant calls use the isolated Sarab platform, with credentials kept on the server. Since3.1, the religious visual companion sends reduced Eye snapshots to Gemini only after explicit in-app consent, while the HUD is foreground. Images are memory-only on the app/server; diagnostic logs must never contain image payloads, questions or answers. See `docs/RELIGIOUS_COMPANION_AR.md`, `docs/SARAB_PLATFORM_AR.md` and `platform/docs/CONTRACT.md`.

### Arabic-aware search

Landmark search (`core/`, tested in `LandmarkSearchTest`) normalizes Arabic: numbers match as whole words (13 ≠ 1), Arabic-Indic digits ≡ ASCII digits, أ/إ/آ → ا, ة → ه, diacritics ignored. Any new text matching should reuse this normalization.

## Conventions

- Comments in this codebase explain *why* — measured facts, device findings, rejected alternatives (e.g. why `largeHeap` is absent, why HIGH_SAMPLING_RATE_SENSORS is required). Match that style; don't add what-comments.
- Behaviour constants live at the top of the file that uses them (e.g. marker visibility distance in `CampusArRenderer.kt`, handoff/arrival distances in `core/`), documented with their rationale.
- Release builds are unsigned; there is no CI and no lint config beyond AGP defaults.
