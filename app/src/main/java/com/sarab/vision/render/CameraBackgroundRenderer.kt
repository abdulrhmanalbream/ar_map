package com.sarab.vision.render

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.sarab.vision.diagnostics.DiagnosticLog
import java.nio.FloatBuffer

/**
 * Draws the ARCore camera image as a full-screen background.
 *
 * ARCore hands us the camera frame as an external OES texture. We draw it on
 * a full-screen quad with depth writes disabled so every 3D object composites
 * on top of it.
 */
class CameraBackgroundRenderer {

    private var program = 0
    private var positionAttrib = 0
    private var texCoordAttrib = 0
    private var textureUniform = 0

    /** ARCore fills this with the texture handle it renders the frame into. */
    var textureId: Int = -1
        private set

    private var uv = CameraUvCoordinates()
    private var invalidUvReported = false

    private val vertexShader = """
        attribute vec4 a_Position;
        attribute vec2 a_TexCoord;
        varying vec2 v_TexCoord;
        void main() {
            gl_Position = a_Position;
            v_TexCoord = a_TexCoord;
        }
    """.trimIndent()

    // samplerExternalOES (not sampler2D) is required for ARCore's camera
    // texture; the OES extension directive must come first.
    private val fragmentShader = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 v_TexCoord;
        uniform samplerExternalOES u_Texture;
        void main() {
            gl_FragColor = texture2D(u_Texture, v_TexCoord);
        }
    """.trimIndent()

    fun createOnGlThread() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )

        // A recreated GL context owns new buffers even if ARCore's surface geometry
        // did not change. The first real frame must always initialise these UVs.
        uv = CameraUvCoordinates()
        invalidUvReported = false

        program = buildProgram(vertexShader, fragmentShader)
        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordAttrib = GLES20.glGetAttribLocation(program, "a_TexCoord")
        textureUniform = GLES20.glGetUniformLocation(program, "u_Texture")
    }

    fun invalidateUv() {
        uv.invalidate()
        invalidUvReported = false
    }

    fun draw(frame: Frame) {
        draw(frame.timestamp, frame.hasDisplayGeometryChanged()) { positions, texture ->
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                positions,
                Coordinates2d.TEXTURE_NORMALIZED,
                texture
            )
        }
    }

    /** Same OES draw path also accepts a SurfaceTexture producer for offline GL validation. */
    internal fun draw(timestampNanos: Long, displayGeometryChanged: Boolean,
        transformUv: (FloatBuffer, FloatBuffer) -> Unit): Boolean {
        val refresh = !uv.ready || displayGeometryChanged
        if (!uv.update(timestampNanos, displayGeometryChanged, transformUv)) {
            if (timestampNanos != 0L && !invalidUvReported) {
                DiagnosticLog.record("ArCamera", "uv rejected reason=degenerate_or_nonfinite")
                invalidUvReported = true
            }
            return false
        }
        if (refresh) {
            invalidUvReported = false
            DiagnosticLog.record("ArCamera", "uv ready geometryChanged=$displayGeometryChanged")
        }

        // Client-memory attributes must not inherit a VBO binding from virtual content.
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureUniform, 0)

        GLES20.glVertexAttribPointer(
            positionAttrib, 2, GLES20.GL_FLOAT, false, 0, uv.positions
        )
        GLES20.glVertexAttribPointer(
            texCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, uv.texture
        )
        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glEnableVertexAttribArray(texCoordAttrib)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisableVertexAttribArray(texCoordAttrib)

        // Restore depth state for the 3D content drawn after us.
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        checkGlError("camera background draw")
        return true
    }
}
