import Foundation

/// Straight-line path helper.
///
/// V1 used this to lay a single straight path in front of the user. V2 routes
/// come from waypoints instead, but this is kept because it is still the
/// simplest way to generate an evenly-spaced run of points along a direction,
/// and it is covered by unit tests.
///
/// - Parameters:
///   - start: where the path begins, on the floor
///   - forward: unit direction along the floor pointing away from the user
///   - length: path length in metres
///   - segments: number of segments; more looks smoother but costs vertices
public func buildPathPoints(
    start: Vec3,
    forward: Vec3,
    length: Float,
    segments: Int = 24
) -> [Vec3] {
    let dir = forward.normalized()
    if dir.length() < 1e-6 || segments < 1 { return [] }

    return (0...segments).map { i in
        let t = Float(i) / Float(segments)
        return start + dir * (length * t)
    }
}
