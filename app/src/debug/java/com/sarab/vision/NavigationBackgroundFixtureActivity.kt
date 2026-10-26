package com.sarab.vision

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Surface
import android.widget.TextView
import com.sarab.vision.render.CameraBackgroundRenderer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Offline synthetic producer exercising the real OES shader; not an ARCore tracking test. */
class NavigationBackgroundFixtureActivity : Activity() {
    @Volatile private var destroyed = false
    private var worker: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val emulator = Build.HARDWARE in setOf("ranchu", "goldfish") &&
            (Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val offline = Settings.Global.getInt(contentResolver, "airplane_mode_on", 0) == 1 &&
            Settings.Global.getInt(contentResolver, "wifi_on", 1) == 0 &&
            Settings.Global.getInt(contentResolver, "mobile_data", 1) == 0
        val runId = intent.getStringExtra("run_id").orEmpty()
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0 || !emulator || !offline ||
            !intent.getBooleanExtra("allow_fixture", false) || !runId.matches(Regex("[A-Za-z0-9_-]{1,64}")) ||
            savedInstanceState != null) { finish(); return }
        val label = TextView(this).apply { text = "Offline navigation background validation: RUNNING" }
        setContentView(label)
        val report = JSONObject().put("runId", runId).put("result", "RUNNING")
            .put("verificationKind", "offline-gles-navigation-background")
            .put("offline", true).put("networkRequests", 0).put("cameraAccess", false).put("physicalArCoreTest", false)
        val reportFile = File(filesDir, "navigation-background-fixture.json")
        reportFile.writeText(report.toString(2))
        worker = Thread({
            val checks = linkedSetOf<String>()
            val samples = JSONArray()
            try {
                val renderer = CameraBackgroundRenderer()
                // Reuse the renderer object across a genuinely destroyed and recreated EGL context.
                repeat(2) { contextIndex -> runContext(renderer, contextIndex, checks, samples) }
                report.put("result", "PASS")
            } catch (failure: Exception) {
                report.put("result", "FAIL").put("failureType", failure.javaClass.simpleName)
                    .put("failure", failure.message?.take(160))
            } finally {
                report.put("checks", JSONArray(checks.toList())).put("samples", samples)
                reportFile.writeText(report.toString(2))
                runOnUiThread { if (!destroyed) label.text = "Offline navigation background validation: ${report.getString("result")}" }
            }
        }, "Navigation-OES-Fixture").apply { isDaemon = true; start() }
    }

    private fun runContext(renderer: CameraBackgroundRenderer, contextIndex: Int,
        checks: MutableSet<String>, samples: JSONArray) {
        check(!destroyed) { "activity_destroyed" }
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "egl_display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "egl_initialize" }
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] == 1) { "egl_config" }
        val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "egl_context" }
        val pbuffer = EGL14.eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, SIZE, EGL14.EGL_HEIGHT, SIZE, EGL14.EGL_NONE), 0)
        var texture: SurfaceTexture? = null
        var producer: Surface? = null
        try {
            check(pbuffer != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "egl_current" }
            GLES20.glViewport(0, 0, SIZE, SIZE)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            renderer.createOnGlThread()
            val received = CountDownLatch(1)
            val activeTexture = SurfaceTexture(renderer.textureId).also { texture = it }
            activeTexture.setDefaultBufferSize(SIZE, SIZE)
            activeTexture.setOnFrameAvailableListener({ received.countDown() }, Handler(Looper.getMainLooper()))
            val activeProducer = Surface(activeTexture).also { producer = it }
            val canvas = activeProducer.lockCanvas(null)
            val paint = Paint()
            try {
                val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
                for (index in colors.indices) {
                    paint.color = colors[index]
                    val x = (index % 2) * SIZE / 2f
                    val y = (index / 2) * SIZE / 2f
                    canvas.drawRect(x, y, x + SIZE / 2f, y + SIZE / 2f, paint)
                }
            } finally { activeProducer.unlockCanvasAndPost(canvas) }
            check(received.await(3, TimeUnit.SECONDS)) { "producer_frame_timeout" }
            activeTexture.updateTexImage()
            val textureMatrix = FloatArray(16)
            activeTexture.getTransformMatrix(textureMatrix)
            val timestamp = activeTexture.timestamp.takeIf { it != 0L } ?: 1L
            var transforms = 0
            fun draw(rotation: Int, changed: Boolean = false): List<String> {
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
                check(renderer.draw(timestamp, changed) { positions, output ->
                    transforms++
                    transform(positions, output, textureMatrix, rotation)
                }) { "background_not_drawn" }
                GLES20.glFinish()
                check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "gles_error" }
                val result = pixels("context-$contextIndex-rotation-$rotation", contextIndex == 0 && rotation == 0)
                samples.put(JSONObject().put("context", contextIndex).put("rotation", rotation)
                    .put("colors", JSONArray(result)).put("transforms", transforms))
                return result
            }
            check(!renderer.draw(0, true) { _, _ -> error("timestamp_zero_transformed") })
            checks += "timestamp_zero_skips_undefined_texture"
            val initial = draw(0)
            check(initial.toSet() == setOf("red", "green", "blue", "yellow")) { "first_frame_collapsed" }
            check(transforms == 1) { "first_frame_uv_missing" }
            checks += if (contextIndex == 0) "first_frame_without_geometry_flag_has_four_regions" else "recreated_gl_context_reinitializes_uv"
            check(draw(0) == initial && transforms == 1) { "unchanged_frame_remapped" }
            checks += "repeated_frame_preserves_texture_and_uv"
            val orders = listOf(listOf(2, 0, 3, 1), listOf(3, 2, 1, 0), listOf(1, 3, 0, 2))
            for (rotation in 1..3) check(draw(rotation, true) == orders[rotation - 1].map { initial[it] }) { "rotation_$rotation" }
            checks += "all_quarter_turns_keep_distinct_correctly_mapped_regions"
            renderer.invalidateUv()
            check(draw(0) == initial) { "session_invalidation_uv" }
            checks += "new_session_invalidation_refreshes_uv_without_geometry_flag"
            renderer.invalidateUv()
            check(!renderer.draw(timestamp, false) { _, output -> output.put(FloatArray(8)) }) { "degenerate_uv_drawn" }
            check(draw(0) == initial) { "recovery_after_invalid_uv" }
            checks += "degenerate_uv_rejected_then_recovers"
            val vbo = IntArray(1)
            GLES20.glGenBuffers(1, vbo, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 32, ByteBuffer.allocateDirect(32), GLES20.GL_STATIC_DRAW)
            check(draw(0) == initial) { "virtual_geometry_vbo_leaked" }
            GLES20.glDeleteBuffers(1, vbo, 0)
            checks += "camera_attributes_isolated_from_virtual_geometry_vbo"
        } finally {
            producer?.release()
            texture?.release()
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            EGL14.eglReleaseThread()
        }
    }

    private fun transform(input: FloatBuffer, output: FloatBuffer, matrix: FloatArray, rotation: Int) {
        repeat(4) {
            val u = (input.get() + 1f) / 2f
            val v = (input.get() + 1f) / 2f
            val (x, y) = when (rotation) { 1 -> v to 1f - u; 2 -> 1f - u to 1f - v; 3 -> 1f - v to u; else -> u to v }
            output.put(matrix[0] * x + matrix[4] * y + matrix[12])
            output.put(matrix[1] * x + matrix[5] * y + matrix[13])
        }
    }

    private fun pixels(name: String, save: Boolean): List<String> {
        val rgba = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, SIZE, SIZE, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgba)
        val colors = listOf("red" to intArrayOf(255, 0, 0), "green" to intArrayOf(0, 255, 0),
            "blue" to intArrayOf(0, 0, 255), "yellow" to intArrayOf(255, 255, 0))
        val result = listOf(1 to 1, 3 to 1, 1 to 3, 3 to 3).map { (x, y) ->
            val offset = ((y * SIZE / 4) * SIZE + x * SIZE / 4) * 4
            val sample = IntArray(3) { rgba.get(offset + it).toInt() and 255 }
            colors.singleOrNull { (_, value) -> (0..2).all { kotlin.math.abs(sample[it] - value[it]) <= 24 } }?.first
                ?: error("unexpected_pixel_${sample.joinToString("_")}")
        }
        if (save) {
            val argb = IntArray(SIZE * SIZE) { index ->
                val x = index % SIZE
                val y = SIZE - 1 - index / SIZE
                val offset = (y * SIZE + x) * 4
                Color.rgb(rgba.get(offset).toInt() and 255, rgba.get(offset + 1).toInt() and 255, rgba.get(offset + 2).toInt() and 255)
            }
            val bitmap = Bitmap.createBitmap(argb, SIZE, SIZE, Bitmap.Config.ARGB_8888)
            File(filesDir, "navigation-background-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle() // Fixture-owned synthetic image; never published to the app.
        }
        return result
    }

    override fun onDestroy() { destroyed = true; worker?.interrupt(); super.onDestroy() }
    companion object { private const val SIZE = 256 }
}
