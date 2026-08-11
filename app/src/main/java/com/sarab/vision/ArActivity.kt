package com.sarab.vision

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import com.sarab.vision.ar.ArSceneRenderer
import com.sarab.vision.ar.ArUiState
import com.sarab.vision.core.PoiCatalogue
import com.sarab.vision.ui.BlockingMessage
import com.sarab.vision.ui.PoiCardHost
import com.sarab.vision.ui.ScanHint

private const val TAG = "SarabArActivity"

/** How many times to rebuild the session after a failed resume. */
private const val MAX_RESUME_RETRIES = 2

/** Delay before a retry, giving ARCore time to release its sensor queue. */
private const val RESUME_RETRY_DELAY_MS = 600L

/**
 * The single Activity: AR camera on launch, path on the floor, tappable POI.
 *
 * ARCore session lifecycle is the fiddly part here. The rules that matter:
 *  - the session is created only after camera permission is granted
 *  - ARCore may need to install/update itself, which round-trips through
 *    onResume, so creation is retried rather than assumed to succeed once
 *  - session.resume() can throw if the camera is claimed by another app
 */
class ArActivity : ComponentActivity() {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var renderer: ArSceneRenderer

    private var session: Session? = null
    private var userRequestedArCoreInstall = true
    private var resumeRetries = 0

    private var uiState by mutableStateOf<ScreenState>(ScreenState.NeedsPermission)
    private var cardVisible by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        uiState = if (granted) ScreenState.Scanning else ScreenState.PermissionDenied
        if (granted) {
            // Permission arrives asynchronously, after onResume has already
            // run, so the session must be created here too.
            ensureSessionAndResume()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        renderer = ArSceneRenderer(onStateChanged = ::handleArState)

        surfaceView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            // 8888 colour, 16-bit depth, no stencil, alpha for the overlay.
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setWillNotDraw(false)
        }

        surfaceView.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                view.performClick()
                // Queue the tap onto the GL thread; the renderer owns all
                // matrix state and must not be read from the UI thread.
                renderer.onTap(event.x, event.y)
            }
            true
        }

        setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                when (val state = uiState) {
                    is ScreenState.PermissionDenied -> BlockingMessage(
                        title = getString(R.string.camera_permission_title),
                        body = getString(R.string.camera_permission_body),
                        actionLabel = getString(R.string.camera_permission_grant),
                        onAction = { requestCameraPermission() }
                    )

                    is ScreenState.Unsupported -> BlockingMessage(
                        title = getString(R.string.ar_unsupported_title),
                        body = state.reason
                    )

                    else -> {
                        AndroidView(
                            factory = { surfaceView },
                            modifier = Modifier.fillMaxSize()
                        )

                        ScanHint(
                            text = getString(R.string.scan_hint),
                            visible = state is ScreenState.Scanning
                        )

                        ScanHint(
                            text = getString(R.string.tap_hint),
                            visible = state is ScreenState.Ready && !cardVisible
                        )

                        PoiCardHost(
                            poi = PoiCatalogue.DEFAULT,
                            visible = cardVisible,
                            onClose = {
                                cardVisible = false
                                renderer.setHighlighted(false)
                            }
                        )
                    }
                }
            }
        }
    }

    /** Bridges GL-thread state changes onto the main thread for Compose. */
    private fun handleArState(state: ArUiState) {
        runOnUiThread {
            when (state) {
                ArUiState.Scanning ->
                    if (uiState !is ScreenState.Unsupported) uiState = ScreenState.Scanning

                ArUiState.Ready ->
                    if (uiState !is ScreenState.Unsupported) uiState = ScreenState.Ready

                ArUiState.MarkerTapped -> {
                    cardVisible = true
                    renderer.setHighlighted(true)
                }

                is ArUiState.Error ->
                    uiState = ScreenState.Unsupported(state.message)
            }
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        if (!hasCameraPermission()) {
            uiState = ScreenState.PermissionDenied
            requestCameraPermission()
            return
        }
        ensureSessionAndResume()
    }

    /**
     * Creates the ARCore session if needed, then resumes it.
     *
     * Safe to call repeatedly -- each step is guarded.
     */
    private fun ensureSessionAndResume() {
        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, userRequestedArCoreInstall)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        // ARCore is installing; onResume will fire again when
                        // the user returns. Do not create the session yet.
                        userRequestedArCoreInstall = false
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> Unit
                }

                val newSession = Session(this)
                newSession.configure(
                    Config(newSession).apply {
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                        // LATEST_CAMERA_IMAGE keeps the feed responsive; the
                        // blocking mode stalls the GL thread on slower devices.
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        lightEstimationMode = Config.LightEstimationMode.DISABLED
                        // Depth is a meaningful GPU cost and buys us nothing
                        // for a flat path and a cube. Off by design.
                        depthMode = Config.DepthMode.DISABLED
                    }
                )
                session = newSession
                renderer.session = newSession
            } catch (e: UnavailableException) {
                Log.e(TAG, "ARCore unavailable", e)
                uiState = ScreenState.Unsupported(
                    getString(R.string.ar_unsupported_body)
                )
                return
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create AR session", e)
                uiState = ScreenState.Unsupported("Could not start AR: ${e.message}")
                return
            }
        }

        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available", e)
            // Drop the session so the next resume rebuilds it cleanly.
            session = null
            renderer.session = null
            uiState = ScreenState.Unsupported("Camera unavailable. Close other camera apps and reopen.")
            return
        } catch (e: Exception) {
            // session.resume() can also throw FatalException -- most often
            // "Failed to register sensor to queue 0", which happens when
            // ARCore's motion-sensor queue is left in a bad state (seen after
            // the camera permission is granted mid-lifecycle, and after an
            // abrupt pause/resume). It is usually transient: discarding the
            // session and building a fresh one on the next resume clears it.
            //
            // Without this catch the exception escapes onResume and kills the
            // app, which is exactly what happened on a Galaxy A16.
            Log.e(TAG, "Session resume failed", e)
            session?.close()
            session = null
            renderer.session = null

            if (resumeRetries < MAX_RESUME_RETRIES) {
                resumeRetries++
                Log.w(TAG, "Retrying AR session (attempt $resumeRetries)")
                // Re-enter after the current lifecycle callback unwinds, so
                // ARCore tears the old session down before we build a new one.
                surfaceView.postDelayed({ ensureSessionAndResume() }, RESUME_RETRY_DELAY_MS)
                uiState = ScreenState.Scanning
            } else {
                uiState = ScreenState.Unsupported(
                    "AR could not start on this device. Reopen the app, or " +
                        "restart the phone if it keeps happening."
                )
            }
            return
        }

        // A successful resume clears the retry budget for the next pause cycle.
        resumeRetries = 0

        surfaceView.onResume()
        if (uiState !is ScreenState.Unsupported) {
            uiState = ScreenState.Scanning
        }
    }

    override fun onPause() {
        super.onPause()
        // Order matters: pause the view before the session, or the GL thread
        // can call into a paused session and crash.
        if (session != null) {
            surfaceView.onPause()
            session?.pause()
        }
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    private sealed interface ScreenState {
        data object NeedsPermission : ScreenState
        data object PermissionDenied : ScreenState
        data object Scanning : ScreenState
        data object Ready : ScreenState
        data class Unsupported(val reason: String) : ScreenState
    }
}
