import Foundation

/// Projects a real route onto the ground in front of the camera.
///
/// ## What this replaces
///
/// The AR view used to draw a straight ribbon along the compass bearing to the
/// destination. That is a pointer, not a path: it goes through walls, ignores
/// every turn, and tells the user to walk into whatever is in the way. What
/// people expect from a navigation view is the route itself lying on the
/// ground, bending where the route bends.
///
/// ## The frame conversion
///
/// The AR world frame has -Z as the direction the camera faced when the
/// session began, which has no fixed relationship to true north (unless ARKit
/// runs with `.gravityAndHeading`). Every route point is therefore converted
/// through the device's current heading: the angle between where the phone
/// points now and where the point lies gives its direction in the AR frame.
///
/// - Parameter maxVisibleMeters: how far ahead to draw. AR only knows the
///   ground it can see; a 250m ribbon would pass through buildings and float
///   over dips, so the path is a visible stub and distance is carried by the
///   marker.
public func routeGroundPath(
    userPosition: LatLng,
    routePoints: [LatLng],
    headingDeg: Double,
    maxVisibleMeters: Double = 25.0,
    startOffsetMeters: Double = 0.8
) -> [Vec3] {
    if !userPosition.isValid || routePoints.count < 2 { return [] }

    // Local (east, north) metres for every route vertex, plus the user at the
    // origin so the path starts underfoot rather than at the first node.
    var local: [(Double, Double)] = []
    local.reserveCapacity(routePoints.count + 1)
    local.append((0.0, 0.0))
    for point in routePoints {
        if !point.isValid { continue }
        let distance = distanceMeters(userPosition, point)
        let bearing = bearingDegrees(userPosition, point)
        let rad = toRadians(bearing)
        local.append((distance * sin(rad), distance * cos(rad)))
    }
    if local.count < 2 { return [] }

    // The first route vertex is often the node the user snapped onto, which
    // can be behind them. Dropping it stops the ribbon doubling back.
    let trimmed = dropBacktrack(local)

    // Walk forward, clipping at the visible horizon.
    let clipped = clipToLength(trimmed, maxVisibleMeters, startOffsetMeters)
    if clipped.count < 2 { return [] }

    // Rotate from world (east/north) into the camera's frame. Camera forward
    // is -Z, so a point directly ahead must land on negative Z.
    let headingRad = toRadians(headingDeg)
    let cosH = cos(headingRad)
    let sinH = sin(headingRad)

    return clipped.map { p -> Vec3 in
        let east = p.0
        let north = p.1
        // Rotate by -heading so "north" becomes "whatever the phone faces".
        let forward = north * cosH + east * sinH
        let right = east * cosH - north * sinH
        return Vec3(Float(right), 0, Float(-forward))
    }
}

/// Removes leading vertices that point away from the route's general direction.
///
/// A router snaps the user onto the nearest node, and that node is frequently
/// slightly behind them. Drawn literally, the path starts by going backwards
/// past the user's feet, which reads as an instruction to turn around.
private func dropBacktrack(_ local: [(Double, Double)]) -> [(Double, Double)] {
    if local.count < 3 { return local }

    // Direction of the route as a whole, from the user to its far end.
    let (endEast, endNorth) = local[local.count - 1]
    let endLength = hypot(endEast, endNorth)
    if endLength < 1e-6 { return local }

    let dirEast = endEast / endLength
    let dirNorth = endNorth / endLength

    var start = 0
    var i = 1
    while i < local.count - 1 {
        let (e, n) = local[i]
        // Projection onto the overall direction: negative means behind.
        if e * dirEast + n * dirNorth >= -0.5 { break }
        start = i
        i += 1
    }
    if start == 0 { return local }
    return [(0.0, 0.0)] + Array(local[(start + 1)...])
}

/// Trims the polyline to the stretch between `startOffset` and `maxLength`.
///
/// Both ends are interpolated rather than snapped to a vertex, so the ribbon
/// begins and ends at a consistent distance regardless of where the route
/// happens to have nodes.
private func clipToLength(
    _ local: [(Double, Double)],
    _ maxLength: Double,
    _ startOffset: Double
) -> [(Double, Double)] {
    var out: [(Double, Double)] = []
    var travelled = 0.0
    var started = false

    for i in 1..<max(local.count, 1) {
        let (ax, ay) = local[i - 1]
        let (bx, by) = local[i]
        let segment = hypot(bx - ax, by - ay)
        if segment < 1e-9 { continue }

        let segmentStart = travelled
        let segmentEnd = travelled + segment

        // Where this segment begins to be visible.
        if !started && segmentEnd > startOffset {
            let t = ((startOffset - segmentStart) / segment).coerced(0.0, 1.0)
            out.append((ax + (bx - ax) * t, ay + (by - ay) * t))
            started = true
        }

        if segmentEnd >= maxLength {
            let t = ((maxLength - segmentStart) / segment).coerced(0.0, 1.0)
            out.append((ax + (bx - ax) * t, ay + (by - ay) * t))
            return out
        }

        if started { out.append((bx, by)) }
        travelled = segmentEnd
    }
    return out
}

/// Resamples a path so points are evenly spaced.
///
/// The ribbon mitres its corners from the vertices it is given, and a route can
/// run tens of metres between nodes. Even spacing keeps the animated pulse
/// moving at a constant speed and gives the chevrons regular ground to sit on.
public func resamplePath(_ points: [Vec3], spacingMeters: Float = 1.0) -> [Vec3] {
    if points.count < 2 || spacingMeters <= 0 { return points }

    var out: [Vec3] = [points[0]]

    var carry: Float = 0
    for i in 1..<points.count {
        let a = points[i - 1]
        let b = points[i]
        let segment = (b - a).length()
        if segment < 1e-6 { continue }

        var position = spacingMeters - carry
        while position < segment {
            let t = position / segment
            out.append(Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t))
            position += spacingMeters
        }
        let rest = segment - (position - spacingMeters)
        carry = rest < 0 ? 0 : rest
    }

    let last = points[points.count - 1]
    if (out[out.count - 1] - last).length() > 0.05 { out.append(last) }
    return out
}

/// Absolute turn between the first and last heading of a ground path.
public func groundPathTurnDegrees(_ points: [Vec3]) -> Double {
    if points.count < 3 { return 0.0 }
    let start = points[1] - points[0]
    let end = points[points.count - 1] - points[points.count - 2]
    let a = toDegrees(atan2(Double(start.x), -Double(start.z)))
    let b = toDegrees(atan2(Double(end.x), -Double(end.z)))
    return abs(relativeBearing(kRem(a + 360.0, 360.0), kRem(b + 360.0, 360.0)))
}
