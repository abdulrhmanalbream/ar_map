# ADR-001: Engine and platform choice for the Sarab Vision MVP

**Status:** Accepted
**Date:** 2026-08-11

## Context

The brief asked for an offline AR MVP for **iOS and Android**, optimised for
mid-range phones, and recommended **Unity + AR Foundation**. It also asked for
the project to be left as close to "ready to build" as possible, with only a
few minutes of manual work remaining in the morning.

The build environment was surveyed before any code was written:

| Tool | Status |
|---|---|
| Unity / Unity Hub | **Not installed** |
| Android SDK | Installed — platforms 35 & 36, build-tools 36.1.0, adb, emulator |
| JDK | System JDK 25; Android Studio JBR 21 also present |
| Gradle | 8.13 cached, plus AGP 8.7.3 and Kotlin 2.0.21 in the module cache |
| Xcode / macOS | **Not available** (Windows host) |
| Network | Available (Google Maven + Maven Central reachable) |

## Decision

Build the MVP as a **native Android app: Kotlin + ARCore + OpenGL ES 2.0**,
with all AR-independent logic isolated in a pure-Kotlin `core` package.

## Rationale

### Why not Unity, despite the recommendation

Unity is a good choice for a large AR product. It was the wrong choice *for
this task*, for a reason that is practical rather than aesthetic: **Unity is
not installed, and it cannot be installed unattended.**

- Unity Hub requires interactive sign-in and licence activation. That cannot
  be completed while the user is asleep.
- Installing the editor plus AR Foundation, ARCore XR and ARKit XR is a
  multi-gigabyte download.
- Most decisively: `.unity` scenes and `.prefab` files are **GUID-linked
  serialised graphs**. Hand-authoring them without the editor routinely
  produces files that fail to deserialise, and every object reference (camera,
  plane manager, line renderer, canvas) would still have to be wired by hand
  in the Inspector.

The realistic Unity outcome was a folder of `.cs` files that had **never been
compiled**, plus an hour or more of manual editor work — the opposite of the
five-minute morning checklist that was requested.

The native path produced a **verified, installable APK**, with the AR maths
covered by passing unit tests, on the night it was asked for.

### Why raw OpenGL ES rather than Sceneform

Sceneform is the usual "easy" renderer for ARCore, and was rejected:

- Google **archived** Sceneform in 2021; community forks lag modern AGP.
- It pulls in Filament (~10 MB) and meaningful GPU overhead.
- The scene is a flat ribbon and a cube. A full PBR scene graph is not
  warranted, and the brief explicitly prioritised **mid-range phones**.

Hand-written GLES 2.0 keeps the APK at **10.48 MB** and removes an abandoned
dependency that could fail to resolve.

## Consequences

### Accepted cost: this delivers Android only

This is the one part of the brief this environment could not satisfy, and it
is called out rather than hidden. **iOS is not delivered.** ARKit requires
Swift, Xcode and macOS, none of which exist on this Windows host — no choice
of engine could have changed that tonight, including Unity (whose iOS output
still requires a Mac and Xcode to build).

To keep that cost as low as possible, all logic that is not tied to ARCore
lives in `com.sarab.vision.core` with **zero Android or ARCore imports**:

- `Geometry.kt` — vectors, rays, AABB intersection (tap picking)
- `Poi.kt` — POI model and floor path layout

These are the parts most likely to contain subtle bugs, they are unit-tested
on the JVM, and they port to Swift as a direct transliteration. See
`docs/IOS-PORT.md`.

### Other consequences

- **Offline is structural**, not conventional: the `INTERNET` permission is
  never declared, so the app cannot make a network call even by accident.
  Verified in the built APK with `aapt2 dump badging`.
- **Non-ASCII project path**: the JVM on this machine cannot load classpath
  JARs from `...\سراب\...`. This is an environment fact, not a code bug, and
  is worked around by `build.ps1`. See the README.
- Gradle must run on **JBR 21**, not the system JDK 25, which Gradle 8.13
  rejects.
