package com.sarab.vision.phonecamera

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/** A hardware camera layer. No bitmap readback, conversion, or Compose state per preview frame. */
@Composable
fun PhoneCameraPreview(controller: PhoneCameraController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preview = remember(context, controller) { PhoneCameraPreviewView(context, controller) }
    DisposableEffect(preview) { onDispose { preview.release() } }
    AndroidView(factory = { preview }, modifier = modifier)
}

class PhoneCameraPreviewView(context: Context, private val controller: PhoneCameraController) : TextureView(context),
    TextureView.SurfaceTextureListener {
    private var released = false
    private var target: PhonePreviewTarget? = null
    private var geometry: PhonePreviewGeometry? = null

    init {
        isOpaque = true
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (released) return
        target = controller.attachPreview(texture) { next ->
            if (!released && surfaceTexture === texture) {
                geometry = next
                applyTransform()
            }
        }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = applyTransform()

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        target?.let(controller::previewUpdated)
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        val previous = target
        target = null
        geometry = null
        if (previous != null) {
            // Camera2 may still own this producer. The controller releases it after onClosed.
            controller.detachPreview(previous, destroyed = true)
            return false
        }
        return true
    }

    fun release() {
        if (released) return
        released = true
        alpha = 0f // Source changes hide old pixels immediately while Camera2 closes off-main.
        target?.let { controller.detachPreview(it, destroyed = false) }
        // Keep the listener until TextureView reports destruction; returning false transfers
        // SurfaceTexture ownership to the controller instead of releasing a camera-owned buffer.
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        applyTransform()
    }

    private fun applyTransform() {
        val value = geometry ?: return
        if (width <= 0 || height <= 0) return
        // Layout may observe the new display rotation before the camera worker listener.
        val rotation = when (display?.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> value.displayDegrees
        }
        val transform = phonePreviewTransform(value.width, value.height, value.sensorDegrees,
            rotation, width, height)
        setTransform(Matrix().apply {
            setScale(transform.scaleX, transform.scaleY, width / 2f, height / 2f)
            postRotate(transform.rotationDegrees.toFloat(), width / 2f, height / 2f)
        })
    }
}
