# iOS port plan (ARKit)

**Status: not implemented.** This is the one part of the original brief that
this environment could not deliver, and it is documented here rather than
quietly dropped.

## Why it is not built

Building for iOS requires **macOS + Xcode + the iOS SDK**. This project was
built on a Windows host. No engine choice changes this: even a Unity project
must be exported and compiled on a Mac with Xcode to produce an iOS binary.

## Why the port is small

The Android app was deliberately structured so that everything ARKit would
need to reuse is already free of Android and ARCore dependencies.

`app/src/main/java/com/sarab/vision/core/` contains **zero platform imports**:

| File | Contents | Reusable on iOS? |
|---|---|---|
| `Geometry.kt` | `Vec3`, `Ray`, `intersectAabb` | Yes — direct transliteration to Swift |
| `Poi.kt` | `Poi`, `PoiCatalogue`, `buildPathPoints` | Yes — direct transliteration |

These are the parts where subtle bugs hide (ray unprojection, AABB picking,
path layout), and they are already covered by passing JVM unit tests in
`app/src/test/`. Porting them is mechanical, and the tests port with them.

## What has to be rewritten

| Android piece | iOS equivalent |
|---|---|
| `ArActivity` (session lifecycle, permissions) | `ARSCNView` / `ARSession` in a `UIViewController`; camera usage string in `Info.plist` |
| `Config.PlaneFindingMode.HORIZONTAL` | `ARWorldTrackingConfiguration.planeDetection = .horizontal` |
| `CameraBackgroundRenderer` | **Delete.** ARKit's `ARSCNView` draws the camera feed for you |
| `PathRenderer` (GL ribbon) | `SCNGeometry` from the same points, or a `SCNPlane` chain |
| `MarkerRenderer` (GL cube) | `SCNBox` + `SCNNode` |
| `handleTap` unprojection | `ARSCNView.hitTest(_:options:)`, or reuse `intersectAabb` with `unprojectPoint` |
| Compose `PoiCard` | SwiftUI view overlaid on the AR view |

## Suggested order

1. New Xcode project, `ARSCNView` filling the screen, camera permission string.
2. Transliterate `core/` to Swift; port the unit tests first and get them green
   **before** touching any AR code.
3. Plane detection → place an `SCNNode` at the anchor.
4. Path geometry from `buildPathPoints`, then the `SCNBox` marker.
5. `hitTest` on tap → present the SwiftUI card.

Steps 1–4 are the bulk of the work and none of them are novel; the risky maths
is already written and tested.
