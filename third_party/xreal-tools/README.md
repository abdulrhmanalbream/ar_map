# XREAL Eye USB camera source

Vendored from [nudou350/Xreal-tools](https://github.com/nudou350/Xreal-tools), revision
`bd9419200a04a047aee36c94c40d10f414f17abe`, retrieved 2026-09-08.
The original project is independent of XREAL. The camera/control subset is Apache-2.0;
its UVC and HEVC implementation also carries the Aloim MIT attribution in `NOTICE`.
Keep both `LICENSE` and `NOTICE` with source and distributed applications.

Copied from `xreal-hand-mouse/app/src/main/kotlin/com/raphael/handmouse/`:

- `glasses/GlassesCommands.kt`, `GlassesFrame.kt`, `GlassesTransport.kt`, `UsbConfigCodec.kt`
- `capture/UvcCameraHelper.kt`, `FrameAssembler.kt`, `MjpegDecoder.kt`, `HevcDecoder.kt`, `HevcNal.kt`

The files live in `com.sarab.vision.glasses.camera.vendor`. Changes for Sarab Vision:
package relocation and attribution headers; bounded HEVC decode queue and selected-frame
dimensions; USB/frame length validation; require a bulk streaming endpoint; prevent late
stream startup after cancellation; close the USB connection before joining its worker.
The owning `EyeCameraController` and `YuvBitmapConverter` are new integration code.

No XREAL proprietary native libraries, firmware, MediaPipe, accessibility service, audio
capture, native conversion library, or camera frame dumps are included or enabled.
The camera uses the tested activation packet `45 10 01 00`, preserving the source's
working NCM/ECM/HID/UVC profile. This causes a USB re-enumeration and a second Android
USB permission request; any glasses network/IMU connection must reconnect afterward.
The packed configuration decoder has only one observed configuration and is not a
general-purpose API for changing arbitrary USB profiles.

Capture favors the smallest landscape MJPEG format reported by the device, then HEVC.
USB negotiates the upstream 60 fps timing; decoding/presentation is capped at 15 fps.
Latest immutable bitmaps are shared by the phone and glasses views; old frames are left
to Android's garbage collector rather than recycled while either display may use them.

Compatibility remains a hardware validation requirement: upstream reports One Pro + Eye
on Samsung Android, while XREAL's official Unity SDK lists S24/S25/Beam Pro and warns about
Android 16. This native USB integration has not been verified on the user's Fold 7.
The feed does not provide ARCore plane detection, world anchors, camera calibration, or
6DoF position. Pixel dimensions are known; calibrated Eye intrinsics are not supplied.

Primary references:

- https://docs.xreal.com/Camera/Access%20RGB%20Camera
- https://docs.xreal.com/XREALDevices/Compatibility
- https://developer.xreal.com/download/
- https://github.com/nudou350/Xreal-tools/blob/bd9419200a04a047aee36c94c40d10f414f17abe/README.md
