import Foundation

/// Minimal 3D vector type.
///
/// Deliberately free of any platform import so that the path layout and
/// ray/box intersection logic below can be reused verbatim by the ARKit front
/// end, and exercised by plain unit tests with no device. Float, as in the
/// Kotlin original, so test tolerances carry over unchanged.
public struct Vec3: Equatable, Hashable, Codable {
    public var x: Float
    public var y: Float
    public var z: Float

    public init(_ x: Float, _ y: Float, _ z: Float) {
        self.x = x; self.y = y; self.z = z
    }

    public init(x: Float, y: Float, z: Float) {
        self.x = x; self.y = y; self.z = z
    }

    public static func + (a: Vec3, o: Vec3) -> Vec3 { Vec3(a.x + o.x, a.y + o.y, a.z + o.z) }
    public static func - (a: Vec3, o: Vec3) -> Vec3 { Vec3(a.x - o.x, a.y - o.y, a.z - o.z) }
    public static func * (a: Vec3, s: Float) -> Vec3 { Vec3(a.x * s, a.y * s, a.z * s) }

    public func dot(_ o: Vec3) -> Float { x * o.x + y * o.y + z * o.z }

    public func length() -> Float { dot(self).squareRoot() }

    public func normalized() -> Vec3 {
        let len = length()
        // Guard against a zero-length vector: normalizing one yields NaN,
        // which silently corrupts every downstream matrix it touches.
        return len < 1e-6 ? Vec3(0, 0, 0) : Vec3(x / len, y / len, z / len)
    }

    public static let ZERO = Vec3(0, 0, 0)
    public static let UP = Vec3(0, 1, 0)
}

/// A ray in world space, used for screen-tap picking.
public struct Ray: Equatable, Hashable {
    public var origin: Vec3
    public var direction: Vec3

    public init(origin: Vec3, direction: Vec3) {
        self.origin = origin
        self.direction = direction
    }
}

/// Axis-aligned bounding box intersection (the "slab" method).
///
/// Returns the distance along `ray` to the near hit, or nil when the ray
/// misses. We use an AABB rather than exact mesh picking because the POI is a
/// cube and an AABB test is a handful of float ops -- picking is on the touch
/// path, so it must stay cheap on mid-range hardware.
public func intersectAabb(ray: Ray, center: Vec3, halfExtent: Float) -> Float? {
    let mn = center - Vec3(halfExtent, halfExtent, halfExtent)
    let mx = center + Vec3(halfExtent, halfExtent, halfExtent)

    var tMin = -Float.infinity
    var tMax = Float.infinity

    let o: [Float] = [ray.origin.x, ray.origin.y, ray.origin.z]
    let d: [Float] = [ray.direction.x, ray.direction.y, ray.direction.z]
    let lo: [Float] = [mn.x, mn.y, mn.z]
    let hi: [Float] = [mx.x, mx.y, mx.z]

    for i in 0...2 {
        if abs(d[i]) < 1e-6 {
            // Ray is parallel to this slab: it can only hit if the origin
            // already lies between the planes.
            if o[i] < lo[i] || o[i] > hi[i] { return nil }
        } else {
            let inv: Float = 1 / d[i]
            var t1 = (lo[i] - o[i]) * inv
            var t2 = (hi[i] - o[i]) * inv
            if t1 > t2 { swap(&t1, &t2) }
            if t1 > tMin { tMin = t1 }
            if t2 < tMax { tMax = t2 }
            if tMin > tMax { return nil }
        }
    }

    // A negative tMin means the box straddles the camera; treat the entry
    // point as 0 so a POI we are standing inside still registers as hit.
    if tMax < 0 { return nil }
    return tMin < 0 ? 0 : tMin
}
