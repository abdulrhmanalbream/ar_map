import Foundation

// Places distant, GPS-anchored landmarks on screen.
//
// ## Why this is separate from the AR path renderer
//
// AR tracking only understands the few metres of ground it can actually see.
// A ribbon drawn 250m into the distance would pass through buildings, float
// above dips and sink into rises, because the tracker has no idea what the
// terrain does out there. Drawing it anyway looks broken and, worse, misleads.
//
// A floating marker is a different matter: it only needs a DIRECTION, which
// GPS gives us exactly. So distant landmarks get a billboard placed by
// bearing and elevation, and only the last stretch gets a ground path.
//
// This is the same split Google Maps Live View uses, and it is a deliberate
// design choice rather than a limitation we failed to overcome.

/// Where a landmark should be drawn on screen, in normalised coordinates.
public struct ScreenPlacement: Equatable {
    /// -1 = left edge, 0 = centre, +1 = right edge.
    public var x: Float
    /// -1 = bottom, 0 = centre, +1 = top.
    public var y: Float
    /// True when the landmark is within the camera's field of view.
    public var visible: Bool
    /// Which way to nudge the user when off screen: -1 left, +1 right.
    /// Meaningless when `visible` is true.
    public var offScreenDirection: Int
    public var distanceMeters: Double

    public init(x: Float, y: Float, visible: Bool, offScreenDirection: Int, distanceMeters: Double) {
        self.x = x
        self.y = y
        self.visible = visible
        self.offScreenDirection = offScreenDirection
        self.distanceMeters = distanceMeters
    }
}

/// Projects a landmark onto the screen from the user's position and heading.
///
/// - Parameters:
///   - horizontalFovDeg: camera horizontal field of view. Typical phone rear
///     cameras are 60-70 degrees; ARKit can report the real value.
///   - verticalFovDeg: camera vertical field of view.
///   - pitchDeg: how far the device is tilted up (+) or down (-) from level.
public func projectLandmark(
    userPosition: LatLng,
    userHeadingDeg: Double,
    pitchDeg: Double,
    target: LatLng,
    horizontalFovDeg: Double = 65.0,
    verticalFovDeg: Double = 50.0
) -> ScreenPlacement? {
    if !userPosition.isValid || !target.isValid { return nil }

    let distance = distanceMeters(userPosition, target)
    let bearing = bearingDegrees(userPosition, target)

    // Horizontal offset from the centre of view, in degrees.
    let relative = relativeBearing(userHeadingDeg, bearing)

    let halfH = horizontalFovDeg / 2.0
    let withinH = abs(relative) <= halfH

    // Map the angle onto [-1, 1] using tan so the projection matches how a
    // real camera lens spreads angles across the sensor. A linear mapping
    // drifts noticeably towards the edges of a wide field of view.
    let x: Float = halfH > 0
        ? Float(tan(toRadians(relative)) / tan(toRadians(halfH)))
        : 0

    // Vertical placement. We have no elevation data for landmarks, so treat
    // them as level with the user and let the device's pitch move them up and
    // down the screen. Being honest about this is better than inventing
    // heights that would be wrong on a campus built on a slope.
    let verticalAngle = -pitchDeg
    let halfV = verticalFovDeg / 2.0
    let y: Float = halfV > 0
        ? Float(tan(toRadians(verticalAngle)) / tan(toRadians(halfV)))
        : 0

    return ScreenPlacement(
        x: x.coerced(-4, 4),
        y: y.coerced(-4, 4),
        visible: withinH && abs(x) <= 1,
        offScreenDirection: relative >= 0 ? 1 : -1,
        distanceMeters: distance
    )
}

/// How large a distant marker should appear, as a fraction of its base size.
///
/// Real perspective scaling (1/distance) would make a 250m landmark a
/// sub-pixel dot. We instead shrink gently with distance and clamp, so far
/// landmarks stay legible while still reading as "further away".
public func markerScaleFor(_ distanceMeters: Double) -> Float {
    if distanceMeters <= 0 { return 1 }
    let scale = 1.0 / (1.0 + distanceMeters / 120.0)
    return Float(scale.coerced(0.45, 1.0))
}

/// How far along the ground a path should actually be drawn, in metres.
///
/// AR plane knowledge extends only a short way, so we draw a stub that points
/// the right way and stop before the geometry becomes fiction. The floating
/// marker carries the rest of the message.
public func groundPathLength(_ distanceToTargetMeters: Double) -> Double {
    if distanceToTargetMeters <= 0 { return 0.0 }
    // Close enough to draw the whole way.
    if distanceToTargetMeters <= 15.0 { return distanceToTargetMeters }
    // Otherwise a fixed stub: far enough to show direction, short enough to
    // stay on ground the tracker has actually observed.
    return 12.0
}
