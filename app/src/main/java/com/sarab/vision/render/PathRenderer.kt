package com.sarab.vision.render

import android.opengl.GLES20
import com.sarab.vision.core.Vec3

/** Upper bound on corner miter stretch, to survive near-switchback turns. */
private const val MAX_MITER_SCALE = 3f

/** Perpendicular to [dir] in the floor plane: cross(dir, UP). */
private fun floorPerpendicular(dir: Vec3): Vec3 = Vec3(
    dir.z * Vec3.UP.y - dir.y * Vec3.UP.z,
    dir.x * Vec3.UP.z - dir.z * Vec3.UP.x,
    dir.y * Vec3.UP.x - dir.x * Vec3.UP.y
).normalized()

/**
 * Draws the navigation path as a flat ribbon lying on the floor.
 *
 * We build an explicit triangle strip rather than using GL_LINE_STRIP because
 * glLineWidth is clamped to 1px on the majority of Android GL drivers, which
 * would make the path a barely-visible hairline. A ribbon also lets us fade
 * and animate it.
 */
class PathRenderer {

    private var program = 0
    private var positionAttrib = 0
    private var progressAttrib = 0
    private var mvpUniform = 0
    private var timeUniform = 0
    private var colorUniform = 0
    private var repeatsUniform = 0

    /** GPU-resident vertex buffers; 0 until first upload. */
    private val vertexVbo = intArrayOf(0)
    private val progressVbo = intArrayOf(0)
    private var buffersReady = false
    private var vertexCount = 0

    /** Pulse repeats across the ribbon, derived from the route length. */
    private var pulseRepeats = 2f

    private val vertexShader = """
        uniform mat4 u_MvpMatrix;
        attribute vec4 a_Position;
        attribute float a_Progress;
        varying float v_Progress;
        void main() {
            v_Progress = a_Progress;
            gl_Position = u_MvpMatrix * a_Position;
        }
    """.trimIndent()

    // A travelling pulse gives the path a sense of direction, which reads as
    // "go this way" far better than a static stripe.
    // u_Repeats keeps one pulse per ~2.5m regardless of route length, so a
    // 15m route does not get one enormous smear and a 4m route a strobe.
    private val fragmentShader = """
        precision mediump float;
        uniform float u_Time;
        uniform vec4 u_Color;
        uniform float u_Repeats;
        varying float v_Progress;
        void main() {
            float pulse = fract(v_Progress * u_Repeats - u_Time * 0.6);
            float glow = smoothstep(0.0, 0.35, pulse) * (1.0 - smoothstep(0.55, 0.95, pulse));
            float alpha = u_Color.a * (0.45 + 0.55 * glow);
            gl_FragColor = vec4(u_Color.rgb, alpha);
        }
    """.trimIndent()

    fun createOnGlThread() {
        program = buildProgram(vertexShader, fragmentShader)
        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        progressAttrib = GLES20.glGetAttribLocation(program, "a_Progress")
        mvpUniform = GLES20.glGetUniformLocation(program, "u_MvpMatrix")
        timeUniform = GLES20.glGetUniformLocation(program, "u_Time")
        colorUniform = GLES20.glGetUniformLocation(program, "u_Color")
        repeatsUniform = GLES20.glGetUniformLocation(program, "u_Repeats")

        // A new GL context invalidates old buffer names, so forget them and
        // let the next updatePath() allocate fresh ones.
        vertexVbo[0] = 0
        progressVbo[0] = 0
        buffersReady = false
    }

    /**
     * Rebuilds the ribbon geometry from the path centre line.
     *
     * Called only when the path itself changes (i.e. when it is first placed),
     * never per frame.
     */
    fun updatePath(points: List<Vec3>, widthMeters: Float = 0.22f) {
        if (points.size < 2) {
            vertexCount = 0
            return
        }

        val half = widthMeters / 2f
        val verts = FloatArray(points.size * 2 * 3)
        val progress = FloatArray(points.size * 2)

        // Arc-length parameterisation. Using the vertex index instead would
        // make the animated pulse speed up over long segments and crawl over
        // short ones, which is very visible on a resampled multi-leg route.
        val cumulative = FloatArray(points.size)
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] + (points[i] - points[i - 1]).length()
        }
        val totalLength = cumulative.last().takeIf { it > 1e-6f } ?: 1f

        // One pulse per ~2.5 metres of real-world route.
        pulseRepeats = (totalLength / 2.5f).coerceIn(1f, 12f)

        for (i in points.indices) {
            // Incoming and outgoing directions at this vertex.
            val incoming = if (i > 0) (points[i] - points[i - 1]).normalized() else null
            val outgoing =
                if (i < points.size - 1) (points[i + 1] - points[i]).normalized() else null

            val sideIn = incoming?.let { floorPerpendicular(it) }
            val sideOut = outgoing?.let { floorPerpendicular(it) }

            // Miter the corner: averaging the two edge normals and scaling by
            // 1/cos(theta/2) keeps the ribbon a constant width through a turn.
            // A forward-difference normal (V1's approach) tears the ribbon
            // open on the outside of every corner, which multi-waypoint
            // routes make obvious.
            val side = when {
                sideIn != null && sideOut != null -> {
                    val avg = (sideIn + sideOut).normalized()
                    val cosHalf = avg.dot(sideOut)
                    // Clamp the miter so a near-180-degree switchback cannot
                    // fling the vertex off to infinity.
                    val scale = if (cosHalf > 0.2f) 1f / cosHalf else MAX_MITER_SCALE
                    avg * scale.coerceAtMost(MAX_MITER_SCALE)
                }
                sideOut != null -> sideOut
                sideIn != null -> sideIn
                else -> Vec3(1f, 0f, 0f)
            }

            val left = points[i] - side * half
            val right = points[i] + side * half
            val t = cumulative[i] / totalLength

            val base = i * 6
            verts[base + 0] = left.x
            verts[base + 1] = left.y
            verts[base + 2] = left.z
            verts[base + 3] = right.x
            verts[base + 4] = right.y
            verts[base + 5] = right.z

            progress[i * 2] = t
            progress[i * 2 + 1] = t
        }

        vertexCount = points.size * 2

        // Upload once into GPU-resident buffers. Previously the ribbon was
        // streamed from client memory on EVERY draw call, which meant pushing
        // ~80 vertices across the JNI/driver boundary 30-60 times a second for
        // geometry that almost never changes. GL_STATIC_DRAW tells the driver
        // it can keep this in fast memory.
        uploadBuffer(vertexVbo, floatBufferOf(verts), verts.size)
        uploadBuffer(progressVbo, floatBufferOf(progress), progress.size)
        buffersReady = true
    }

    /** Creates the VBO on first use, then uploads [data] into it. */
    private fun uploadBuffer(vboHolder: IntArray, data: java.nio.FloatBuffer, count: Int) {
        if (vboHolder[0] == 0) {
            GLES20.glGenBuffers(1, vboHolder, 0)
        }
        data.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboHolder[0])
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            count * Float.SIZE_BYTES,
            data,
            GLES20.GL_STATIC_DRAW
        )
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    fun draw(mvpMatrix: FloatArray, timeSeconds: Float) {
        if (vertexCount == 0 || !buffersReady) return

        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        // The path hugs the floor; writing depth would make it z-fight with
        // the detected plane. Test against depth but do not write it.
        GLES20.glDepthMask(false)

        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvpMatrix, 0)
        GLES20.glUniform1f(timeUniform, timeSeconds)
        GLES20.glUniform1f(repeatsUniform, pulseRepeats)
        GLES20.glUniform4f(colorUniform, 0.31f, 0.76f, 0.97f, 0.9f)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexVbo[0])
        GLES20.glVertexAttribPointer(positionAttrib, 3, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glEnableVertexAttribArray(positionAttrib)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, progressVbo[0])
        GLES20.glVertexAttribPointer(progressAttrib, 1, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glEnableVertexAttribArray(progressAttrib)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, vertexCount)

        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisableVertexAttribArray(progressAttrib)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        checkGlError("path draw")
    }
}
