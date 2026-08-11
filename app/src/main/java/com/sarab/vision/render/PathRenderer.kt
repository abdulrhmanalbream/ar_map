package com.sarab.vision.render

import android.opengl.GLES20
import com.sarab.vision.core.Vec3

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

    private var vertexBuffer = floatBuffer(0)
    private var progressBuffer = floatBuffer(0)
    private var vertexCount = 0

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
    private val fragmentShader = """
        precision mediump float;
        uniform float u_Time;
        uniform vec4 u_Color;
        varying float v_Progress;
        void main() {
            float pulse = fract(v_Progress * 2.0 - u_Time * 0.6);
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

        for (i in points.indices) {
            // Direction along the path at this point, from the neighbouring
            // segment (forward difference, backward at the last point).
            val dir = if (i < points.size - 1) {
                (points[i + 1] - points[i]).normalized()
            } else {
                (points[i] - points[i - 1]).normalized()
            }

            // Perpendicular in the floor plane: cross(dir, up) for a path
            // lying flat on the ground.
            val side = Vec3(
                dir.z * Vec3.UP.y - dir.y * Vec3.UP.z,
                dir.x * Vec3.UP.z - dir.z * Vec3.UP.x,
                dir.y * Vec3.UP.x - dir.x * Vec3.UP.y
            ).normalized()

            val left = points[i] - side * half
            val right = points[i] + side * half
            val t = i.toFloat() / (points.size - 1)

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

        vertexBuffer = floatBufferOf(verts)
        progressBuffer = floatBufferOf(progress)
        vertexCount = points.size * 2
    }

    fun draw(mvpMatrix: FloatArray, timeSeconds: Float) {
        if (vertexCount == 0) return

        vertexBuffer.position(0)
        progressBuffer.position(0)

        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        // The path hugs the floor; writing depth would make it z-fight with
        // the detected plane. Test against depth but do not write it.
        GLES20.glDepthMask(false)

        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvpMatrix, 0)
        GLES20.glUniform1f(timeUniform, timeSeconds)
        GLES20.glUniform4f(colorUniform, 0.31f, 0.76f, 0.97f, 0.9f)

        GLES20.glVertexAttribPointer(
            positionAttrib, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer
        )
        GLES20.glVertexAttribPointer(
            progressAttrib, 1, GLES20.GL_FLOAT, false, 0, progressBuffer
        )
        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glEnableVertexAttribArray(progressAttrib)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, vertexCount)

        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisableVertexAttribArray(progressAttrib)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        checkGlError("path draw")
    }
}
