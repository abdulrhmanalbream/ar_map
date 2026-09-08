# XREAL One / One Pro IMU provenance

The application-local `glasses.motion` package uses wire framing, the IMU report
layout, the tracker sensor mapping and complementary-filter equations from
[Skarian/one-xr](https://github.com/Skarian/one-xr), MIT, copyright 2026 Neil Skaria.
Inspected commit: `c7b000444cacc23090a71867e46cebc549205dd5`.

Upstream references:

- `onexr/src/main/java/io/onexr/OneXrReportMessageParser.kt`
- `onexr/src/main/java/io/onexr/OneXrTrackerSampleMapper.kt`
- `onexr/src/main/java/io/onexr/OneXrHeadTracker.kt`
- `app/src/main/java/io/onexr/demo/OrientationGlSurfaceView.kt`

The latter specifies its camera forward vector as
`(sin(yaw)*cos(pitch), -sin(pitch), -cos(yaw)*cos(pitch))`.
Our published yaw keeps that clockwise/right convention. We negate its pitch
and roll to publish up-positive pitch and clockwise roll. All three angles are
relative to startup calibration. This is the upstream estimated camera convention,
not an official firmware attitude quaternion. The sensor-to-camera mounting and
motion direction still require verification on the attached One Pro hardware.

Changes for this application: Ethernet-only Android socket selection, cancellable
lifecycle, sub-second stale detection, reconnect sessions, stillness-gated residual
gyro calibration, finite-value validation, bounded stream framing, device-clock
discontinuity handling and frame-rate-limited StateFlow updates. The application
does not retrieve factory calibration or apply unverified coefficients. It does
not use magnetometer readings to manufacture an absolute north reference.
The user must explicitly align the glasses with a true-north compass reference.
The estimator provides 3DoF directional overlays; it cannot detect ground planes.

No enable command is needed for the IMU stream: connect to `169.254.2.1:52998`
on the glasses' USB Ethernet network. Ethernet must be enabled and the glasses
should be in Follow mode with stabilization disabled. Camera USB re-enumeration
disconnects the network temporarily; the controller reconnects and recalibrates.
TCP control (`52999`) and USB interfaces are not opened by this controller.

`app/src/test/resources/packets/one-xr-capture-prefix.bin` is the first 536 bytes
of the upstream `onexr_stream_capture_v1.bin` hardware capture. Upstream metadata:
capture `2026-02-18T16:52:02.284417+01:00`; original length 5,242,880 bytes;
SHA-256 `2acf9849f4f08ad1cc4ae5fd9649db9f34377ff7b6cd871d752c33b111bf1cbc`.
The prefix contains one magnetometer report and three IMU reports. It proves the
decoder against captured bytes; it does not constitute a live Fold 7 hardware test.

The MIT license is retained here and packaged in the APK at
`assets/licenses/one-xr-MIT.txt`.
