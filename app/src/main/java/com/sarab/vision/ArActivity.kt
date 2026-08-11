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
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import com.sarab.vision.ar.ArSceneRenderer
import com.sarab.vision.ar.ArUiState
import com.sarab.vision.ar.ImageDbResult
import com.sarab.vision.ar.buildOriginImageDatabase
import com.sarab.vision.core.Destination
import com.sarab.vision.ui.BlockingMessage
import com.sarab.vision.ui.ChangeDestinationButton
import com.sarab.vision.ui.DestinationSheet
import com.sarab.vision.ui.PoiCardHost
import com.sarab.vision.ui.ScanHint

private const val TAG = "SarabArActivity"

/** How many times to rebuild the session after a failed resume. */
private const val MAX_RESUME_RETRIES = 2

/** Delay before a retry, giving ARCore time to release its sensor queue. */
private const val RESUME_RETRY_DELAY_MS = 600L

/**
 * The single Activity: AR camera, image-anchored campus routes, and the
 * destination picker.
 *
 * ARCore session lifecycle rules that matter here:
 *  - the session is created only after camera permission is granted
 *  - ARCore may need to install/update itself, which round-trips through
 *    onResume, so creation is retried rather than assumed to succeed once
 *  - session.resume() can throw FatalException (see the catch below)
 */
class ArActivity : ComponentActivity() {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var renderer: ArSceneRenderer

    private var session: Session? = null
    private var userRequestedArCoreInstall = true
    private var resumeRetries = 0

    private var uiState by mutableStateOf<ScreenState>(ScreenState.NeedsPermission)
    private var cardVisible by mutableStateOf(false)
    private var sheetVisible by mutableStateOf(true)
    private var selected by mutableStateOf<Destination?>(null)
    private var remainingMeters by mutableStateOf<Float?>(null)

    /** Which origin mode the session ended up in; drives the hint text. */
    private var imageDbResult by mutableStateOf<ImageDbResult>(ImageDbResult.NoImageProvided)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        uiState = if (granted) ScreenState.Searching else ScreenState.PermissionDenied
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
                            text = hintForState(state),
                            visible = state is ScreenState.Searching &&
                                !sheetVisible && !cardVisible
                        )

                        // While navigating, a compact pill shows the target
                        // and remaining distance, and reopens the picker.
                        selected?.let { dest ->
                            if (!sheetVisible && !cardVisible &&
                                state is ScreenState.Navigating
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(bottom = 36.dp),
                                    contentAlignment = Alignment.BottomCenter
                                ) {
                                    ChangeDestinationButton(
                                        label = dest.name,
                                        remainingMeters = remainingMeters,
                                        onClick = { sheetVisible = true }
                                    )
                                }
                            }
                        }

                        selected?.let { dest ->
                            PoiCardHost(
                                destination = dest,
                                remainingMeters = remainingMeters,
                                visible = cardVisible,
                                onClose = {
                                    cardVisible = false
                                    renderer.setHighlighted(false)
                                }
                            )
                        }

                        DestinationSheet(
                            visible = sheetVisible,
                            selectedId = selected?.id,
                            onSelect = { destination ->
                                selected = destination
                                remainingMeters = null
                                renderer.selectDestination(destination)
                                sheetVisible = false
                                cardVisible = false
                                renderer.setHighlighted(false)
                            },
                            onDismiss = {
                                // Only dismissible once something is chosen,
                                // otherwise there is nothing to look at.
                                if (selected != null) sheetVisible = false
                            }
                        )
                    }
                }
            }
        }
    }

    /** Context-appropriate guidance while the origin is being acquired. */
    private fun hintForState(state: ScreenState): String = when {
        selected == null -> getString(R.string.hint_choose_destination)
        imageDbResult is ImageDbResult.Ready -> getString(R.string.hint_find_board)
        else -> getString(R.string.scan_hint)
    }

    /** Bridges GL-thread state changes onto the main thread for Compose. */
    private fun handleArState(state: ArUiState) {
        runOnUiThread {
            if (uiState is ScreenState.Unsupported) return@runOnUiThread

            when (state) {
                ArUiState.SearchingForImage,
                ArUiState.Scanning,
                ArUiState.Tracking,
                ArUiState.AwaitingDestination -> uiState = ScreenState.Searching

                ArUiState.OriginAcquired -> Unit

                ArUiState.Navigating -> uiState = ScreenState.Navigating

                is ArUiState.DistanceUpdate -> remainingMeters = state.remainingMeters

                ArUiState.MarkerTapped -> {
                    if (selected != null) {
                        cardVisible = true
                        renderer.setHighlighted(true)
                    }
                }

                is ArUiState.Error -> uiState = ScreenState.Unsupported(state.message)
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
     * Safe to call repeatedly -- each step is guarded.
     */
    private fun ensureSessionAndResume() {
        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, userRequestedArCoreInstall)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        userRequestedArCoreInstall = false
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> Unit
                }

                val newSession = Session(this)

                // Load the printed reference image, if one was supplied.
                val (imageDb, result) = buildOriginImageDatabase(this, newSession)
                imageDbResult = result
                renderer.usingImageOrigin = imageDb != null
                if (result is ImageDbResult.Rejected) {
                    Log.w(TAG, "Reference image rejected: ${result.reason}")
                }

                newSession.configure(
                    Config(newSession).apply {
                        // Planes are still needed for the no-image fallback.
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        lightEstimationMode = Config.LightEstimationMode.DISABLED
                        depthMode = Config.DepthMode.DISABLED
                        imageDb?.let { augmentedImageDatabase = it }
                    }
                )
                session = newSession
                renderer.session = newSession
            } catch (e: UnavailableException) {
                Log.e(TAG, "ARCore unavailable", e)
                uiState = ScreenState.Unsupported(getString(R.string.ar_unsupported_body))
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
                surfaceView.postDelayed({ ensureSessionAndResume() }, RESUME_RETRY_DELAY_MS)
                uiState = ScreenState.Searching
            } else {
                uiState = ScreenState.Unsupported(
                    "AR could not start on this device. Reopen the app, or " +
                        "restart the phone if it keeps happening."
                )
            }
            return
        }

        resumeRetries = 0
        surfaceView.onResume()
        if (uiState !is ScreenState.Unsupported && uiState !is ScreenState.Navigating) {
            uiState = ScreenState.Searching
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

        /** Acquiring the world origin (image or floor plane). */
        data object Searching : ScreenState

        /** Route is drawn. */
        data object Navigating : ScreenState

        data class Unsupported(val reason: String) : ScreenState
    }
}
