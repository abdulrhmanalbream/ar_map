package com.sarab.vision.render

import android.opengl.GLES20
import android.opengl.Matrix

/**
 * Draws the POI marker: a cube that hovers and slowly spins above the end of
 * the path.
 *
 * Per-face flat shading is baked into the vertex colours rather than computed
 * from a light, which keeps the fragment shader trivial -- the marker is the
 * only lit object in the scene, so a full lighting model would not pay for
 * itself on mid-range hardware.
 */
class MarkerRenderer {

    private var program = 0
    private var positionAttrib = 0
    private var shadeAttrib = 0
    private var mvpUniform = 0
    private var tintUniform = 0

    private val vertexVbo = intArrayOf(0)
    private val shadeVbo = intArrayOf(0)
    private var vertexCount = 0

    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private val vertexShader = """
        uniform mat4 u_MvpMatrix;
        attribute vec4 a_Position;
        attribute float a_Shade;
        varying float v_Shade;
        void main() {
            v_Shade = a_Shade;
            gl_Position = u_MvpMatrix * a_Position;
        }
    """.trimIndent()

    private val fragmentShader = """
        precision mediump float;
        uniform vec4 u_Tint;
        varying float v_Shade;
        void main() {
            gl_FragColor = vec4(u_Tint.rgb * v_Shade, u_Tint.a);
        }
    """.trimIndent()

    fun createOnGlThread() {
        program = buildProgram(vertexShader, fragmentShader)
        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        shadeAttrib = GLES20.glGetAttribLocation(program, "a_Shade")
        mvpUniform = GLES20.glGetUniformLocation(program, "u_MvpMatrix")
        tintUniform = GLES20.glGetUniformLocation(program, "u_Tint")

        buildCube()
    }

    /** Unit cube centred on the origin, 36 vertices (6 faces x 2 triangles). */
    private fun buildCube() {
        val h = 0.5f
        // Per-face brightness: top brightest, sides mid, bottom darkest.
        val faces = listOf(
            // front
            Triple(
                floatArrayOf(-h, -h, h, h, -h, h, h, h, h, -h, -h, h, h, h, h, -h, h, h),
                0.85f, 0
            ),
            // back
            Triple(
                floatArrayOf(-h, -h, -h, -h, h, -h, h, h, -h, -h, -h, -h, h, h, -h, h, -h, -h),
                0.65f, 0
            ),
            // left
            Triple(
                floatArrayOf(-h, -h, -h, -h, -h, h, -h, h, h, -h, -h, -h, -h, h, h, -h, h, -h),
                0.72f, 0
            ),
            // right
            Triple(
                floatArrayOf(h, -h, -h, h, h, -h, h, h, h, h, -h, -h, h, h, h, h, -h, h),
                0.78f, 0
            ),
            // top
            Triple(
                floatArrayOf(-h, h, -h, -h, h, h, h, h, h, -h, h, -h, h, h, h, h, h, -h),
                1.0f, 0
            ),
            // bottom
            Triple(
                floatArrayOf(-h, -h, -h, h, -h, -h, h, -h, h, -h, -h, -h, h, -h, h, -h, -h, h),
                0.5f, 0
            )
        )

        val verts = FloatArray(36 * 3)
        val shades = FloatArray(36)
        var vi = 0
        var si = 0
        for ((positions, shade, _) in faces) {
            positions.copyInto(verts, vi)
            vi += positions.size
            repeat(6) { shades[si++] = shade }
        }

        // Cube geometry never changes, so it belongs on the GPU permanently.
        // Streaming 36 vertices from client memory on every draw was pure
        // overhead -- and the cube is drawn twice per frame (destination
        // marker + origin marker), so it cost double.
        GLES20.glGenBuffers(1, vertexVbo, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexVbo[0])
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            verts.size * Float.SIZE_BYTES,
            floatBufferOf(verts),
            GLES20.GL_STATIC_DRAW
        )

        GLES20.glGenBuffers(1, shadeVbo, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, shadeVbo[0])
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            shades.size * Float.SIZE_BYTES,
            floatBufferOf(shades),
            GLES20.GL_STATIC_DRAW
        )
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        vertexCount = 36
    }

    /**
     * @param viewProjection combined view-projection matrix
     * @param position       marker centre in world space
     * @param sizeMeters     cube edge length
     * @param timeSeconds    drives the hover bob and spin
     * @param highlighted    true while the info card for this marker is open
     */
    fun draw(
        viewProjection: FloatArray,
        position: FloatArray,
        sizeMeters: Float,
        timeSeconds: Float,
        highlighted: Boolean
    ) {
        if (vertexCount == 0) return

        val bob = kotlin.math.sin(timeSeconds * 1.6f) * 0.04f

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, position[0], position[1] + bob, position[2])
        Matrix.rotateM(modelMatrix, 0, timeSeconds * 28f, 0f, 1f, 0f)
        Matrix.scaleM(modelMatrix, 0, sizeMeters, sizeMeters, sizeMeters)
        Matrix.multiplyMM(mvpMatrix, 0, viewProjection, 0, modelMatrix, 0)

        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvpMatrix, 0)

        if (highlighted) {
            GLES20.glUniform4f(tintUniform, 1.0f, 0.85f, 0.3f, 1f)
        } else {
            GLES20.glUniform4f(tintUniform, 1.0f, 0.72f, 0.16f, 1f)
        }

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexVbo[0])
        GLES20.glVertexAttribPointer(positionAttrib, 3, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glEnableVertexAttribArray(positionAttrib)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, shadeVbo[0])
        GLES20.glVertexAttribPointer(shadeAttrib, 1, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glEnableVertexAttribArray(shadeAttrib)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)

        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisableVertexAttribArray(shadeAttrib)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        checkGlError("marker draw")
    }
}
