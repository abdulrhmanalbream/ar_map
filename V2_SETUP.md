# Sarab Vision V2 — setup

Everything here is optional. **The app runs without any setup**: with no
reference image it falls back to placing the route on the floor in front of
you. The reference image is what makes the origin *fixed and repeatable*,
which is what a real campus route needs.

---

## 1. The reference image (the "map board")

### Where to put it

```
app/src/main/assets/origin_marker.jpg
```

That exact folder. There is a `PUT_YOUR_REFERENCE_IMAGE_HERE.txt` in it as a
reminder.

Accepted filenames, checked in this order — the first one found wins:

| Filename |
|---|
| `origin_marker.jpg` ← preferred |
| `origin_marker.png` |
| `map_board.jpg` |
| `map_board.png` |
| `qr_code.jpg` |
| `qr_code.png` |

Then rebuild and install:

```powershell
.\build.ps1 -Install
```

On launch, logcat will confirm which file was used:

```
SarabAugImage: Augmented image database built from origin_marker.jpg
```

### Set the physical width — this one matters

Open `app/src/main/java/com/sarab/vision/ar/AugmentedImageSupport.kt`:

```kotlin
const val ORIGIN_IMAGE_WIDTH_METERS = 0.30f   // 30 cm printed width
```

**Change this to the real printed width of your board, in metres.** ARCore
uses it to resolve scale immediately. If it is wrong, the entire route is
scaled wrong — a 15 m path could come out 7 m or 30 m long.

### What makes a *good* reference image

ARCore tracks images by visual feature points, so:

- ✅ **High detail and contrast** — text, logos, photos, complex artwork
- ✅ **Matte paper**, flat against the wall, evenly lit
- ✅ **At least 20 cm wide**, ideally 30 cm+
- ❌ **Avoid a plain QR code.** Despite the filename being allowed, a bare QR
  code is mostly flat black-and-white blocks with repetitive structure and
  tracks *poorly*. A real map board or a poster with photos works far better.
- ❌ Avoid glossy/laminated prints (glare), repeating patterns, and anything
  behind glass.

You can check quality with Google's `arcoreimg` tool if you want a score
out of 100 (aim for 75+), but it is not required.

### Mounting: wall vs floor

The routes assume the board hangs **vertically on a wall**, the normal case.
In `app/src/main/java/com/sarab/vision/core/Waypoints.kt`:

```kotlin
var mounting: ImageMounting = ImageMounting.VERTICAL
var boardHeightMeters: Float = 1.5f   // board centre height above the floor
```

- **`VERTICAL`** — board on a wall. Set `boardHeightMeters` to how high the
  centre of the board sits above the floor, so the path drops to ground level
  instead of floating at chest height.
- **`FLAT`** — marker lying flat on the floor facing the ceiling.

Both are covered by unit tests (`WaypointsTest`).

---

## 2. Editing the campus routes

All routes are in `app/src/main/java/com/sarab/vision/core/Waypoints.kt`.
Coordinates are **metres relative to the reference image**, authored as:

- **X** = sideways (positive = right as you face the board)
- **Y** = forwards, away from the board
- **Z** = unused when authoring (the mounting logic fills it in)

```kotlin
val STADIUM = Destination(
    id = "dest-stadium",
    name = "Stadium",
    category = "Sports · 350 seats",
    detail = "Main athletics field and grandstand...",
    waypoints = listOf(
        Vec3(0.0f,  1.0f, 0f),   // start 1m in front of the board
        Vec3(0.0f,  4.0f, 0f),   // walk forward 4m
        Vec3(3.0f,  7.0f, 0f),   // veer right
        Vec3(3.0f, 12.0f, 0f),
        Vec3(6.5f, 15.0f, 0f)    // arrive
    )
)
```

To add a destination: define another `Destination` and add it to
`CampusMap.ALL`. The bottom sheet, the 3D label, and the distance readout all
pick it up automatically — no other file needs editing.

The current three (Stadium, Dorms, Engineering College) are **placeholder
coordinates**. Replace them with a real survey when you have one.

---

## 3. What V2 does at runtime

1. **Launch** → the "Select Destination" sheet appears over the camera.
2. **Pick a destination** → the sheet closes.
3. **Origin acquisition**:
   - With a reference image: point the camera at the board. The hint reads
     "Point your camera at the campus map board". Only a *fully tracked*
     image is accepted — a stale pose would anchor the whole route in the
     wrong place.
   - Without one: scan the floor as in V1.
4. **Navigation** → a mitred multi-segment ribbon runs along the waypoints, a
   gold cube hovers at the destination, and a floating text label shows the
   destination name above it.
5. **Tap the cube** → the detail card opens with live remaining distance.
6. A pill at the bottom shows the target and distance; tap it to switch
   destination at any time.

---

## 4. Offline guarantee (unchanged from V1)

The `INTERNET` permission is still **not declared**, so the app cannot make a
network call even by accident. The augmented image database is built at
runtime from the bundled asset — no server, no download, no `.imgdb` tooling
step.

Verify it yourself on the built APK:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\35.0.0\aapt2.exe" dump badging .\output\app-debug.apk `
  | Select-String "uses-permission"
```

You should see `CAMERA` and `HIGH_SAMPLING_RATE_SENSORS` — and no `INTERNET`.
