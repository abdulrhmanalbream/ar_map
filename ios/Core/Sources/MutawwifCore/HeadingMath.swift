import Foundation

// Compass heading that survives however the user is holding the phone.
//
// On Android the obvious implementation takes the azimuth of the device's +Y
// axis (the top edge). That breaks completely when the phone is held upright
// to look through the camera: the top edge points at the sky, the azimuth
// becomes degenerate (gimbal lock at pitch = 90 degrees) and the reading
// swings wildly. The fix reads direction vectors straight out of the rotation
// matrix and picks the one that is actually horizontal:
//
//  - Phone upright: use where the CAMERA points, the device -Z axis.
//  - Phone flat: use where the top of the SCREEN points instead.
//
// On iOS, CoreLocation's heading already handles this, but the same maths is
// kept so a CoreMotion attitude matrix can be fed in identically.
//
// Matrix convention: a row-major 3x3 that maps device coordinates to world
// coordinates (X = east, Y = north, Z = up). Columns are the world-space
// images of the device axes:
//   device X -> (R[0], R[3], R[6])
//   device Y -> (R[1], R[4], R[7])
//   device Z -> (R[2], R[5], R[8])

/// Above this much tilt the camera is considered to be pointing too far up or
/// down to carry a usable heading, and the screen-up direction is used instead.
///
/// 0.72 is roughly 46 degrees from horizontal: comfortably past normal AR
/// holding angles, but reached well before the phone is flat on a table.
let FLAT_THRESHOLD: Float = 0.72

/// Hysteresis band, so a phone held near the boundary does not flap.
///
/// Widened from 0.08 after a device log showed the source flipping between
/// SCREEN_UP and CAMERA several times in a few seconds, each flip landing the
/// heading tens of degrees away. Since the two modes read different physical
/// axes, every flip moved the drawn path; users turned to follow it, which
/// moved it again, and reported chasing the line in circles. 0.18 means: once
/// flat, stay flat until clearly raised, and vice versa.
let FLAT_HYSTERESIS: Float = 0.18

/// How far a direction must be from vertical before its bearing is trusted.
let MIN_HORIZONTAL_COMPONENT: Double = 0.08

/// Which reference direction produced a heading.
public enum HeadingSource: String, CaseIterable {
    /// Phone upright: heading is where the camera looks.
    case camera = "CAMERA"
    /// Phone flat: heading is where the top of the screen points.
    case screenUp = "SCREEN_UP"
}

public struct HeadingResult: Equatable {
    /// Degrees clockwise from north, or nil when nothing is trustworthy.
    public var degrees: Double?
    public var source: HeadingSource
    /// How far the camera is tilted from horizontal, 0 = level, 1 = straight up/down.
    public var tilt: Float

    public init(degrees: Double?, source: HeadingSource, tilt: Float) {
        self.degrees = degrees
        self.source = source
        self.tilt = tilt
    }
}

/// World-space direction the camera points, as (east, north, up).
/// The camera looks along the device's -Z axis.
public func cameraDirection(_ r: [Float]) -> (Float, Float, Float) {
    return (-r[2], -r[5], -r[8])
}

/// World-space direction the top of the SCREEN points, as (east, north, up).
///
/// Unlike the camera this does depend on screen rotation, so the device-space
/// "up the screen" vector is rotated before being transformed.
public func screenUpDirection(_ r: [Float], _ displayRotationDeg: Int) -> (Float, Float, Float) {
    let dx: Float
    let dy: Float
    switch ((displayRotationDeg % 360) + 360) % 360 {
    case 90: dx = -1; dy = 0
    case 180: dx = 0; dy = -1
    case 270: dx = 1; dy = 0
    default: dx = 0; dy = 1
    }
    return (
        r[0] * dx + r[1] * dy,
        r[3] * dx + r[4] * dy,
        r[6] * dx + r[7] * dy
    )
}

/// Compass bearing of a world-space direction, or nil if it is too close to
/// vertical for its horizontal component to mean anything.
public func bearingOf(_ direction: (Float, Float, Float)) -> Double? {
    let east = Double(direction.0)
    let north = Double(direction.1)
    if hypot(east, north) < MIN_HORIZONTAL_COMPONENT { return nil }
    return kRem(toDegrees(atan2(east, north)) + 360.0, 360.0)
}

/// Computes the heading, choosing the reference that is currently meaningful.
///
/// - Parameter wasFlat: the previous decision, used for hysteresis so the
///   source does not oscillate while the phone sits near the switching angle.
public func computeHeading(
    rotationMatrix: [Float],
    displayRotationDeg: Int,
    wasFlat: Bool = false
) -> HeadingResult {
    if rotationMatrix.count < 9 {
        return HeadingResult(degrees: nil, source: .camera, tilt: 0)
    }

    let camera = cameraDirection(rotationMatrix)
    // Vertical component of the camera direction: 0 when level, 1 when the
    // camera points straight up or down.
    let tilt = abs(camera.2)

    // Hysteresis: it takes a little more tilt to enter flat mode than to leave
    // it, which stops the source flickering at the boundary.
    let threshold = wasFlat ? FLAT_THRESHOLD - FLAT_HYSTERESIS : FLAT_THRESHOLD
    let flat = tilt > threshold

    if flat {
        // If the screen-up direction is ALSO vertical the phone is on edge; fall
        // back to the camera rather than reporting nothing.
        if let b = bearingOf(screenUpDirection(rotationMatrix, displayRotationDeg)) {
            return HeadingResult(degrees: b, source: .screenUp, tilt: tilt)
        }
        return HeadingResult(degrees: bearingOf(camera), source: .camera, tilt: tilt)
    } else {
        if let b = bearingOf(camera) {
            return HeadingResult(degrees: b, source: .camera, tilt: tilt)
        }
        return HeadingResult(
            degrees: bearingOf(screenUpDirection(rotationMatrix, displayRotationDeg)),
            source: .screenUp,
            tilt: tilt
        )
    }
}

/// Circular exponential smoothing.
///
/// Averaging raw degrees is wrong: the mean of 359 and 1 is 180, so the needle
/// swings a half turn every time it crosses north. Smoothing the sine and
/// cosine instead keeps it continuous. `alpha` 0..1; lower is steadier but laggier.
public final class CircularSmoother {
    private let alpha: Double
    private var s = 0.0
    private var c = 0.0
    private var primed = false

    public init(alpha: Double = 0.18) {
        self.alpha = alpha
    }

    public func reset() {
        primed = false
    }

    public func next(_ degrees: Double) -> Double {
        let rad = toRadians(degrees)
        let ns = sin(rad)
        let nc = cos(rad)

        if !primed {
            s = ns
            c = nc
            primed = true
        } else {
            s = s * (1 - alpha) + ns * alpha
            c = c * (1 - alpha) + nc * alpha
        }
        return kRem(toDegrees(atan2(s, c)) + 360.0, 360.0)
    }
}
