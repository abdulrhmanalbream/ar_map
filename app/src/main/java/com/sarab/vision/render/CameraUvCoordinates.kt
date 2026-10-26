package com.sarab.vision.render

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs

/** Coordinates belong to a GL background, not to ARCore's display-change flag. */
internal class CameraUvCoordinates {
    val positions: FloatBuffer = buffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    val texture: FloatBuffer = buffer(FloatArray(8))
    var ready = false
        private set

    fun invalidate() { ready = false }

    fun update(timestampNanos: Long, geometryChanged: Boolean,
        transform: (FloatBuffer, FloatBuffer) -> Unit): Boolean {
        if (timestampNanos == 0L) return false
        if (!ready || geometryChanged) {
            ready = false
            positions.rewind()
            for (index in 0 until 8) texture.put(index, Float.NaN)
            texture.rewind()
            transform(positions, texture)
            // A failed/stale native transform can leave the output untouched. Do not draw
            // a single texture pixel stretched over the entire camera viewport.
            ready = (0 until 8).all { texture.get(it).isFinite() } &&
                abs((texture.get(2) - texture.get(0)) * (texture.get(5) - texture.get(1)) -
                    (texture.get(3) - texture.get(1)) * (texture.get(4) - texture.get(0))) > 1e-8f
        }
        positions.rewind()
        texture.rewind()
        return ready
    }

    private fun buffer(values: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); rewind() }
}
