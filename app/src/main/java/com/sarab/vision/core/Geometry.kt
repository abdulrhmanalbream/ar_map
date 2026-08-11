package com.sarab.vision.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Minimal 3D vector type.
 *
 * Deliberately free of any Android or ARCore import so that the path layout
 * and ray/box intersection logic below can be reused verbatim by an ARKit
 * (iOS) front end, or exercised by plain JVM unit tests with no device.
 */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)

    fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z

    fun length(): Float = sqrt(dot(this))

    fun normalized(): Vec3 {
        val len = length()
        // Guard against a zero-length vector: normalizing one yields NaN,
        // which silently corrupts every downstream matrix it touches.
        return if (len < 1e-6f) Vec3(0f, 0f, 0f) else Vec3(x / len, y / len, z / len)
    }

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val UP = Vec3(0f, 1f, 0f)
    }
}

/** A ray in world space, used for screen-tap picking. */
data class Ray(val origin: Vec3, val direction: Vec3)

/**
 * Axis-aligned bounding box intersection (the "slab" method).
 *
 * Returns the distance along [ray] to the near hit, or null when the ray
 * misses. We use an AABB rather than exact mesh picking because the POI is a
 * cube and an AABB test is a handful of float ops -- picking is on the touch
 * path, so it must stay cheap on mid-range hardware.
 */
fun intersectAabb(ray: Ray, center: Vec3, halfExtent: Float): Float? {
    val min = center - Vec3(halfExtent, halfExtent, halfExtent)
    val max = center + Vec3(halfExtent, halfExtent, halfExtent)

    var tMin = Float.NEGATIVE_INFINITY
    var tMax = Float.POSITIVE_INFINITY

    val o = floatArrayOf(ray.origin.x, ray.origin.y, ray.origin.z)
    val d = floatArrayOf(ray.direction.x, ray.direction.y, ray.direction.z)
    val lo = floatArrayOf(min.x, min.y, min.z)
    val hi = floatArrayOf(max.x, max.y, max.z)

    for (i in 0..2) {
        if (abs(d[i]) < 1e-6f) {
            // Ray is parallel to this slab: it can only hit if the origin
            // already lies between the planes.
            if (o[i] < lo[i] || o[i] > hi[i]) return null
        } else {
            val inv = 1f / d[i]
            var t1 = (lo[i] - o[i]) * inv
            var t2 = (hi[i] - o[i]) * inv
            if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
            if (t1 > tMin) tMin = t1
            if (t2 < tMax) tMax = t2
            if (tMin > tMax) return null
        }
    }

    // A negative tMin means the box straddles the camera; treat the entry
    // point as 0 so a POI we are standing inside still registers as hit.
    return if (tMax < 0f) null else if (tMin < 0f) 0f else tMin
}
