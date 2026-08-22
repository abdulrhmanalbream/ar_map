package com.sarab.vision.render

import android.opengl.GLES20
import com.sarab.vision.core.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Spacing between chevrons along the path, in metres. */
private const val CHEVRON_SPACING_M = 1.6f

/** Chevron size, in metres. */
private const val CHEVRON_LENGTH_M = 0.75f
private const val CHEVRON_WIDTH_M = 0.62f

/** Height above the floor, so chevrons never z-fight with the ribbon. */
private const val CHEVRON_LIFT_M = 0.02f

/** Hard cap, so a long path cannot allocate an unbounded buffer. */
private const val MAX_CHEVRONS = 40

/**
 * Draws chevron arrows along the path, flowing toward the destination.
 *
 * ## Why arrows and not just the ribbon
 *
 * A ribbon shows where the path IS. It does not show which way to go along it,
 * and standing on a line that stretches away in both directions is exactly the
 * moment a user needs to be told. The travelling pulse in [PathRenderer]
 * gestures at direction; an arrow states it, in the visual language every
 * navigation app has trained people to read.
 *
 * ## Why they animate
 *
 * The chevrons slide forward and fade in and out along the way. Static arrows
 * painted on the ground read as scenery; moving ones read as instruction, and
 * the motion also survives being glanced at from the corner of an eye while
 * walking, which is how this is actually used.
 */
class ArrowRenderer {

    private var program = 0
    private var positionAttrib = 0
    private var phaseAttrib = 0
    private var mvpUniform = 0
    private var timeUniform = 0
    private var colorUniform = 0

    private var vertexBuffer: FloatBuffer? = null
    private var phaseBuffer: FloatBuffer? = null
    private var vertexCount = 0

    private val vertexShader = """
        uniform mat4 u_MvpMatrix;
        attribute vec4 a_Position;
        attribute float a_Phase;
        varying float v_Phase;
        void main() {
            v_Phase = a_Phase;
            gl_Position = u_MvpMatrix * a_Position;
        }
    """.trimIndent()

    // Each chevron carries its own position along the path as a_Phase. The
    // fragment shader turns that into a brightness that sweeps forward, so the
    // whole line of arrows appears to march toward the destination.
    private val fragmentShader = """
        precision mediump float;
        uniform float u_Time;
        uniform vec4 u_Color;
        varying float v_Phase;
        void main() {
            float sweep = fract(v_Phase - u_Time * 0.45);
            float glow = smoothstep(0.0, 0.25, sweep) * (1.0 - smoothstep(0.45, 0.9, sweep));
            float alpha = u_Color.a * (0.35 + 0.65 * glow);
            gl_FragColor = vec4(u_Color.rgb, alpha);
        }
    """.trimIndent()

    fun createOnGlThread() {
        program = buildProgram(vertexShader, fragmentShader)
        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        phaseAttrib = GLES20.glGetAttribLocation(program, "a_Phase")
        mvpUniform = GLES20.glGetUniformLocation(program, "u_MvpMatrix")
        timeUniform = GLES20.glGetUniformLocation(program, "u_Time")
        colorUniform = GLES20.glGetUniformLocation(program, "u_Color")
        vertexCount = 0
    }

    /**
     * Rebuilds the chevrons from the path centre line.
     *
     * Chevrons are placed at fixed real-world spacing rather than one per
     * vertex: the path is resampled into many short segments, so per-vertex
     * arrows would pile into an unreadable smear.
     */
    fun updatePath(points: List<Vec3>) {
        if (points.size < 2) {
            vertexCount = 0
            return
        }

        val cumulative = FloatArray(points.size)
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] + (points[i] - points[i - 1]).length()
        }
        val total = cumulative.last()
        if (total <= CHEVRON_SPACING_M) {
            vertexCount = 0
            return
        }

        // Leave the first half-spacing clear so an arrow is never drawn
        // underfoot, where it is both invisible and disorienting.
        val count = ((total - CHEVRON_SPACING_M) / CHEVRON_SPACING_M)
            .toInt()
            .coerceIn(0, MAX_CHEVRONS)
        if (count <= 0) {
            vertexCount = 0
            return
        }

        // Three vertices per chevron: an open arrowhead drawn as one triangle.
        val verts = FloatArray(count * 3 * 3)
        val phases = FloatArray(count * 3)

        var segment = 1
        for (c in 0 until count) {
            val distance = CHEVRON_SPACING_M * (c + 1)

            // Walk forward through the segments to find where this falls.
            while (segment < points.size - 1 && cumulative[segment] < distance) segment++

            val a = points[segment - 1]
            val b = points[segment]
            val segmentLength = (cumulative[segment] - cumulative[segment - 1]).coerceAtLeast(1e-5f)
            val t = ((distance - cumulative[segment - 1]) / segmentLength).coerceIn(0f, 1f)

            val centre = Vec3(
                a.x + (b.x - a.x) * t,
                a.y + (b.y - a.y) * t + CHEVRON_LIFT_M,
                a.z + (b.z - a.z) * t
            )
            val forward = (b - a).normalized()
            val side = floorPerpendicularArrow(forward)

            val halfWidth = CHEVRON_WIDTH_M / 2f
            val tip = Vec3(
                centre.x + forward.x * CHEVRON_LENGTH_M / 2f,
                centre.y,
                centre.z + forward.z * CHEVRON_LENGTH_M / 2f
            )
            val left = Vec3(
                centre.x - forward.x * CHEVRON_LENGTH_M / 2f + side.x * halfWidth,
                centre.y,
                centre.z - forward.z * CHEVRON_LENGTH_M / 2f + side.z * halfWidth
            )
            val right = Vec3(
                centre.x - forward.x * CHEVRON_LENGTH_M / 2f - side.x * halfWidth,
                centre.y,
                centre.z - forward.z * CHEVRON_LENGTH_M / 2f - side.z * halfWidth
            )

            val base = c * 9
            verts[base + 0] = tip.x; verts[base + 1] = tip.y; verts[base + 2] = tip.z
            verts[base + 3] = left.x; verts[base + 4] = left.y; verts[base + 5] = left.z
            verts[base + 6] = right.x; verts[base + 7] = right.y; verts[base + 8] = right.z

            val phase = distance / total
            phases[c * 3] = phase
            phases[c * 3 + 1] = phase
            phases[c * 3 + 2] = phase
        }

        vertexBuffer = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(verts); position(0) }
        phaseBuffer = ByteBuffer.allocateDirect(phases.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(phases); position(0) }
        vertexCount = count * 3
    }

    fun draw(
        viewProjection: FloatArray,
        timeSeconds: Float,
        rgba: FloatArray = floatArrayOf(1f, 1f, 1f, 0.95f),
        dim: Float = 1f
    ) {
        if (vertexCount == 0 || program == 0) return
        val positions = vertexBuffer ?: return
        val phases = phaseBuffer ?: return

        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        // Chevrons sit a couple of centimetres above the ribbon; without
        // depth writes disabled they punch holes in it at grazing angles.
        GLES20.glDepthMask(false)

        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, viewProjection, 0)
        GLES20.glUniform1f(timeUniform, timeSeconds)
        GLES20.glUniform4f(colorUniform, rgba[0], rgba[1], rgba[2], rgba[3] * dim)

        positions.position(0)
        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glVertexAttribPointer(positionAttrib, 3, GLES20.GL_FLOAT, false, 0, positions)

        phases.position(0)
        GLES20.glEnableVertexAttribArray(phaseAttrib)
        GLES20.glVertexAttribPointer(phaseAttrib, 1, GLES20.GL_FLOAT, false, 0, phases)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)

        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisableVertexAttribArray(phaseAttrib)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }
}

/** Perpendicular to [dir] in the floor plane. */
private fun floorPerpendicularArrow(dir: Vec3): Vec3 = Vec3(
    dir.z * Vec3.UP.y - dir.y * Vec3.UP.z,
    dir.x * Vec3.UP.z - dir.z * Vec3.UP.x,
    dir.y * Vec3.UP.x - dir.x * Vec3.UP.y
).normalized()
