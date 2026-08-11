package com.sarab.vision.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix

/**
 * Draws a floating text label above the destination marker.
 *
 * The text is rasterised once into a bitmap with Android's Canvas and
 * uploaded as a texture, then drawn on a camera-facing billboard quad.
 *
 * Rationale: rendering real glyph geometry in GL would mean shipping a font
 * atlas and a text shaper. Rasterising once per destination change costs a
 * few milliseconds, produces crisp system-font text in any language, and adds
 * no dependencies -- which matters for the mid-range target.
 */
class LabelRenderer {

    private var program = 0
    private var positionAttrib = 0
    private var texCoordAttrib = 0
    private var mvpUniform = 0
    private var textureUniform = 0
    private var alphaUniform = 0

    private var textureId = 0
    private var hasTexture = false

    /** Quad half-width in metres, derived from the bitmap aspect ratio. */
    private var halfWidth = 0.5f
    private var halfHeight = 0.15f

    private val quad = floatBufferOf(
        floatArrayOf(
            -1f, -1f, 0f,
            1f, -1f, 0f,
            -1f, 1f, 0f,
            1f, 1f, 0f
        )
    )

    // V is flipped: Android bitmaps are top-down, GL textures are bottom-up.
    private val texCoords = floatBufferOf(
        floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
        )
    )

    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private val vertexShader = """
        uniform mat4 u_MvpMatrix;
        attribute vec4 a_Position;
        attribute vec2 a_TexCoord;
        varying vec2 v_TexCoord;
        void main() {
            v_TexCoord = a_TexCoord;
            gl_Position = u_MvpMatrix * a_Position;
        }
    """.trimIndent()

    private val fragmentShader = """
        precision mediump float;
        uniform sampler2D u_Texture;
        uniform float u_Alpha;
        varying vec2 v_TexCoord;
        void main() {
            vec4 c = texture2D(u_Texture, v_TexCoord);
            gl_FragColor = vec4(c.rgb, c.a * u_Alpha);
        }
    """.trimIndent()

    fun createOnGlThread() {
        program = buildProgram(vertexShader, fragmentShader)
        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordAttrib = GLES20.glGetAttribLocation(program, "a_TexCoord")
        mvpUniform = GLES20.glGetUniformLocation(program, "u_MvpMatrix")
        textureUniform = GLES20.glGetUniformLocation(program, "u_Texture")
        alphaUniform = GLES20.glGetUniformLocation(program, "u_Alpha")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
    }

    /**
     * Rasterises [text] into the label texture.
     *
     * Must be called on the GL thread. Called only when the destination
     * changes, never per frame.
     */
    fun setText(text: String, subtitle: String? = null) {
        val bitmap = renderTextBitmap(text, subtitle)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

        // Keep the quad's aspect ratio matched to the bitmap so text is never
        // stretched. Width is fixed in metres; height follows.
        val labelWidthMeters = 0.9f
        halfWidth = labelWidthMeters / 2f
        halfHeight = halfWidth * (bitmap.height.toFloat() / bitmap.width.toFloat())

        bitmap.recycle()
        hasTexture = true
        checkGlError("label texture upload")
    }

    /** Draws the text into a rounded translucent card, like the 2D UI. */
    private fun renderTextBitmap(text: String, subtitle: String?): Bitmap {
        val padding = 28f
        val titleSize = 64f
        val subSize = 38f

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = titleSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#9FB3C8")
            textSize = subSize
            typeface = Typeface.DEFAULT
        }

        val titleWidth = titlePaint.measureText(text)
        val subWidth = subtitle?.let { subPaint.measureText(it) } ?: 0f
        val contentWidth = maxOf(titleWidth, subWidth)

        val lineGap = if (subtitle != null) 14f else 0f
        val contentHeight = titleSize + lineGap + (if (subtitle != null) subSize else 0f)

        // Power-of-two-ish sizing is not required by GLES2 for CLAMP_TO_EDGE
        // with LINEAR filtering, so we size to content and keep it small.
        val w = (contentWidth + padding * 2).toInt().coerceAtLeast(64)
        val h = (contentHeight + padding * 2).toInt().coerceAtLeast(32)

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F21B2430")
        }
        canvas.drawRoundRect(
            RectF(0f, 0f, w.toFloat(), h.toFloat()), 22f, 22f, bg
        )

        // Accent bar down the left edge, echoing the 2D card styling.
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFB300")
        }
        canvas.drawRoundRect(RectF(0f, 0f, 10f, h.toFloat()), 6f, 6f, accent)

        var y = padding + titleSize * 0.82f
        canvas.drawText(text, padding, y, titlePaint)

        if (subtitle != null) {
            y += lineGap + subSize * 0.9f
            canvas.drawText(subtitle, padding, y, subPaint)
        }

        return bitmap
    }

    /**
     * Draws the label as a billboard that always faces the camera.
     *
     * @param viewProjection combined view-projection matrix
     * @param viewMatrix     needed to cancel the camera rotation
     * @param position       label centre in world space
     */
    fun draw(
        viewProjection: FloatArray,
        viewMatrix: FloatArray,
        position: FloatArray
    ) {
        if (!hasTexture) return

        // Billboarding: the upper-left 3x3 of the view matrix is the camera's
        // rotation, so transposing it into the model matrix cancels that
        // rotation and leaves the quad facing the viewer.
        Matrix.setIdentityM(modelMatrix, 0)
        modelMatrix[0] = viewMatrix[0]
        modelMatrix[1] = viewMatrix[4]
        modelMatrix[2] = viewMatrix[8]
        modelMatrix[4] = viewMatrix[1]
        modelMatrix[5] = viewMatrix[5]
        modelMatrix[6] = viewMatrix[9]
        modelMatrix[8] = viewMatrix[2]
        modelMatrix[9] = viewMatrix[6]
        modelMatrix[10] = viewMatrix[10]
        modelMatrix[12] = position[0]
        modelMatrix[13] = position[1]
        modelMatrix[14] = position[2]

        Matrix.scaleM(modelMatrix, 0, halfWidth, halfHeight, 1f)
        Matrix.multiplyMM(mvpMatrix, 0, viewProjection, 0, modelMatrix, 0)

        quad.position(0)
        texCoords.position(0)

        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        // Labels should not occlude each other or the marker via the depth
        // buffer; they are UI floating in the scene.
        GLES20.glDepthMask(false)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(textureUniform, 0)
        GLES20.glUniform1f(alphaUniform, 1f)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvpMatrix, 0)

        GLES20.glVertexAttribPointer(
            positionAttrib, 3, GLES20.GL_FLOAT, false, 0, quad
        )
        GLES20.glVertexAttribPointer(
            texCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, texCoords
        )
        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glEnableVertexAttribArray(texCoordAttrib)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisableVertexAttribArray(texCoordAttrib)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        checkGlError("label draw")
    }
}
