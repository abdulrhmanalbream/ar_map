package com.sarab.vision

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.provider.Settings
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import com.sarab.vision.ar.CampusArRenderer
import com.sarab.vision.ar.CampusArState
import com.sarab.vision.core.CalibrationInput
import com.sarab.vision.core.alignedGlassesTilt
import com.sarab.vision.core.CalibrationState
import com.sarab.vision.core.CalibrationStep
import com.sarab.vision.core.initialCalibrationStep
import com.sarab.vision.core.GuidanceMode
import com.sarab.vision.core.HudCommand
import com.sarab.vision.core.HudMenuAction
import com.sarab.vision.core.HudMenuState
import com.sarab.vision.core.Landmark
import com.sarab.vision.core.SignMatch
import com.sarab.vision.core.VoiceState
import com.sarab.vision.core.advanceHudMenu
import com.sarab.vision.core.nextVoiceCue
import com.sarab.vision.core.syncHudMenu
import com.sarab.vision.core.hudMenuView
import com.sarab.vision.ui.GlassesHud
import com.sarab.vision.core.voiceCueAr
import com.sarab.vision.core.distanceMeters
import com.sarab.vision.core.stairsAhead
import com.sarab.vision.ar.SignReader
import com.sarab.vision.core.SweepTracker
import com.sarab.vision.core.nextCalibrationState
import com.sarab.vision.ui.CalibrationOverlay
import com.sarab.vision.core.formatDistanceAr
import com.sarab.vision.core.instructionAr
import com.sarab.vision.ui.AmbiguityPrompt
import com.sarab.vision.ui.BlockingMessage
import androidx.compose.runtime.LaunchedEffect
import com.sarab.vision.ui.CompassBar
import com.sarab.vision.ui.DestinationPicker
import com.sarab.vision.ui.DirectionArrow
import com.sarab.vision.ui.IgnitionSplash
import com.sarab.vision.ui.SignOverlay
import com.sarab.vision.ui.StartTourButton
import com.sarab.vision.ui.TourOverlay
import com.sarab.vision.glasses.camera.EyeCameraController
import com.sarab.vision.glasses.motion.GlassesMotionController
import com.sarab.vision.ui.GlassesCameraScreen
import com.sarab.vision.diagnostics.DiagnosticLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.EnumSet

private const val TAG = "SarabArNav"
private const val MIN_CPU_IMAGE_HEIGHT = 720
private val Muted = Color(0xFF9FB3C8)

/**
 * The main navigation screen: camera first, path on the ground.
 *
 * This is what the user actually asked for -- open the app, see the real
 * world through the camera with the route drawn on it. The 2D map is a
 * secondary reference reached by a button, not the primary interface.
 *
 * GPS supplies the bearing and distance to the target; ARCore supplies the
 * ground plane to draw on. Neither alone is enough: ARCore drifts too much
 * to cross a campus, and GPS cannot draw on the floor.
 */
class ArNavActivity : ComponentActivity() {

    private val activityInstance = Integer.toHexString(System.identityHashCode(this))
    private var lastEyeRetryMs = -2_000L

    companion object {
        const val EXTRA_TARGET_ID = "target_id"
    }

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var renderer: CampusArRenderer
    private lateinit var campus: CampusState

    private var session: Session? = null
    private var userRequestedArCoreInstall = true

    private var arState by mutableStateOf<CampusArState>(CampusArState.Initialising)
    private var hasCameraPermission by mutableStateOf(false)
    private var unsupportedReason by mutableStateOf<String?>(null)

    /** The destination sheet, shown over the camera. */
    private var pickerVisible by mutableStateOf(false)

    /** Plays once over whatever is loading underneath, then never shows again. */
    private var showIgnition by mutableStateOf(true)

    /**
     * What the camera has read off a building plaque, if anything.
     *
     * Shown as information, never acted on automatically: the buildings here
     * are identical enough that a wrong confident answer would be worse than
     * no answer, and re-targeting navigation on a misread would be worse
     * still.
     */
    private var signMatch by mutableStateOf<SignMatch?>(null)

    private val signReader = SignReader()

    /** Shows the HUD on USB-C display glasses whenever a pair is plugged in. */
    private lateinit var glasses: GlassesDisplayController

    // ---- Eyes-free control -----------------------------------------------

    /** The glasses menu; all input sources drive it through [onHudCommand]. */
    private var hudMenu by mutableStateOf<HudMenuState>(HudMenuState.Closed)

    /** Compose-visible mirror of the controller's connection state. */
    private var glassesConnected by mutableStateOf(false)

    /** User's choice: false = instrument HUD, true = mirror the phone. */
    private var glassesMirror by mutableStateOf(true)

    private var useEyeCamera by mutableStateOf(true)
    private lateinit var eyeCamera: EyeCameraController
    private lateinit var headMotion: GlassesMotionController
    private var northOffset by mutableStateOf<Double?>(null)
    private var horizonPitch by mutableStateOf<Double?>(null)
    private var horizonRoll by mutableStateOf<Double?>(null)
    private var alignedSession: Long? = null
    private var horizontalFov by mutableStateOf(70f)
    private var resumed = false
    private var hasLocationPermission by mutableStateOf(false)
    private var exportingLog by mutableStateOf(false)
    private var cameraPermissionRequested = false
    private var cameraPermissionBlocked by mutableStateOf(false)
    private var locationPermissionRequested = false

    private lateinit var voice: VoiceGuide
    private var voiceState = VoiceState()

    /** Previous guidance, so menu sync reacts to transitions, not states. */
    private var previousGuidance: GuidanceMode? = null

    /**
     * Receives media buttons (Bluetooth remotes and rings, and the glasses'
     * own controls if the firmware sends consumer-control codes). Only active
     * while glasses are connected, so ordinary music control is untouched the
     * rest of the time.
     */
    private var mediaSession: MediaSession? = null

    /** Throttles gesture scroll, which arrives as a burst per hand-swipe. */
    private var lastScrollCommandMs = 0L

    // ---- Guided start-up -------------------------------------------------

    private var calibration by mutableStateOf(
        CalibrationState(
            CalibrationStep.WAVE,
            0f,
            "حرّك الجوال يميناً ويساراً",
            "امسك الجوال مائلاً قليلاً وحرّكه ببطء يميناً ويساراً لثوانٍ."
        )
    )
    private val sweep = SweepTracker()
    private var stepStartedAt = 0L

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        cameraPermissionBlocked = !granted && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        DiagnosticLog.record("Permission", "Camera granted=$granted")
        if (granted && resumed) startNavigation()
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasLocationPermission = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        DiagnosticLog.record("Permission", "Precise location granted=$hasLocationPermission")
        if (resumed) campus.startSensors()
    }

    private val diagnosticExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            exportingLog = true
            lifecycleScope.launch {
                val error = DiagnosticLog.writeTo(this@ArNavActivity, uri)
                exportingLog = false
                Toast.makeText(this@ArNavActivity,
                    error ?: "تم حفظ سجل التشخيص؛ أرفقه في المحادثة", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exportDiagnosticLog() {
        if (exportingLog) return
        DiagnosticLog.record("App", "Export requested; eye=$useEyeCamera display=$glassesConnected " +
            "cameraPermission=$hasCameraPermission locationPermission=$hasLocationPermission " +
            "calibrated=${northOffset != null} demo=${campus.demoActive}")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        diagnosticExportLauncher.launch("smart-mutawwif-eye-$stamp.txt")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLog.initialize(this)

        campus = CampusApp.state(this)
        val preferences = getPreferences(MODE_PRIVATE)
        useEyeCamera = preferences.getBoolean("use_eye_camera", true)
        logLifecycle("created restored=${savedInstanceState != null}")
        requestedOrientation = if (useEyeCamera) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        applyCameraWindow()
        horizontalFov = preferences.getFloat("eye_horizontal_fov", 70f).coerceIn(40f, 110f)
        eyeCamera = EyeCameraController(this)
        headMotion = GlassesMotionController(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        voice = VoiceGuide(this)
        glasses = GlassesDisplayController(
            this, campus,
            menu = { hudMenu },
            onCommand = { runOnUiThread { onHudCommand(it) } },
            onConnectionChanged = { connected ->
                glassesConnected = connected
                DiagnosticLog.record("Display", "Observer active=${glasses.observing} available=$connected mirror=$glassesMirror")
                // Media buttons are only claimed while the wearer depends on
                // them; otherwise this would silently break music controls.
                mediaSession?.isActive = connected
                if (!connected) hudMenu = HudMenuState.Closed
            }
        )
        glasses.setMirror(true)
        setUpMediaSession()
        hasCameraPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        intent.getStringExtra(EXTRA_TARGET_ID)?.let { id ->
            campus.landmarks.firstOrNull { it.id == id }?.let { campus.selectTarget(it) }
        }

        signReader.onResult = { result ->
            // Arrives on an ML Kit worker thread; Compose state must be
            // written from the main thread.
            runOnUiThread { signMatch = result }
        }
        renderer = CampusArRenderer(onStateChanged = { state ->
            runOnUiThread {
                arState = state
                // The renderer ticks every frame, so this is where the
                // start-up sequence gets its live readings.
                updateCalibration()
            }
        })

        surfaceView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 0, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        renderer.signReader = signReader
        renderer.displayRotationDegrees = 0

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    headMotion.state.collect { refreshHeadHeading() }
                }
                launch {
                    eyeCamera.state.collect { refreshHeadHeading() }
                }
            }
        }

        // Back must not dump the user straight out of the app.
        //
        // The camera is the launcher screen, so the default behaviour was to
        // exit on the first press regardless of what was open. Now back
        // closes whatever is showing first, and only leaves once there is
        // genuinely nothing left to dismiss.
        onBackPressedDispatcher.addCallback(this) {
            when {
                // The glasses menu closes first: right-click from the
                // glasses' gesture control arrives as BACK, and it must
                // dismiss what the wearer is looking at, not what the
                // phone screen happens to show.
                hudMenu !is HudMenuState.Closed -> hudMenu = HudMenuState.Closed
                pickerVisible -> pickerVisible = false
                !useEyeCamera && calibration.step != CalibrationStep.DONE ->
                    calibration = CalibrationState(CalibrationStep.DONE, 1f, "", "")
                campus.target != null -> {
                    // Clear the destination rather than quitting: the user is
                    // far more likely to want a different one.
                    campus.selectTarget(null)
                    pickerVisible = true
                }
                else -> finish()
            }
        }

        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1B2A))) {
                    when {
                        !hasCameraPermission -> BlockingMessage(
                            title = "نحتاج إذن الكاميرا",
                            body = "يعرض التطبيق المسار على الأرض من خلال الكاميرا. " +
                                "لا تغادر أي صورة جهازك.",
                            actionLabel = if (cameraPermissionBlocked) "فتح إعدادات التطبيق" else "السماح",
                            onAction = {
                                if (cameraPermissionBlocked) {
                                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        android.net.Uri.parse("package:$packageName")))
                                } else {
                                    cameraPermissionRequested = true
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            }
                        )

                        useEyeCamera -> GlassesContent()

                        unsupportedReason != null -> BlockingMessage(
                            title = "AR غير مدعوم",
                            body = unsupportedReason!!,
                            actionLabel = "استخدام كاميرا النظارة",
                            onAction = { selectCamera(true) }
                        )

                        else -> ArNavContent()
                    }

                    if ((!useEyeCamera || !hasCameraPermission) && !showIgnition) {
                        TextButton(onClick = { exportDiagnosticLog() }, enabled = !exportingLog,
                            modifier = Modifier.align(Alignment.TopEnd)
                                .windowInsetsPadding(WindowInsets.statusBars)) {
                            Text("حفظ سجل التشخيص", color = Color(0xFF4FC3F7))
                        }
                    }
                    if (showIgnition) {
                        IgnitionSplash(onFinished = { showIgnition = false })
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun GlassesContent() {
        val cameraState by eyeCamera.state.collectAsState()
        val frame by eyeCamera.frame.collectAsState()
        val motion by headMotion.state.collectAsState()
        androidx.compose.runtime.SideEffect {
            com.sarab.vision.platform.PlatformStore.get(this).apply {
                cameraConnected = cameraState.streaming
                imuTracking = motion.tracking
                destinationName = campus.target?.name
            }
        }
        Box(Modifier.fillMaxSize()) {
            GlassesCameraScreen(
                campus = campus,
                cameraState = cameraState,
                frame = frame,
                motion = motion.copy(
                    pitchDegrees = alignedGlassesTilt(motion.pitchDegrees, horizonPitch),
                    rollDegrees = alignedGlassesTilt(motion.rollDegrees, horizonRoll),
                ),
                calibrated = northOffset != null,
                displayConnected = glassesConnected,
                horizontalFov = horizontalFov,
                onFovChange = {
                    horizontalFov = it
                    getPreferences(MODE_PRIVATE).edit().putFloat("eye_horizontal_fov", it).apply()
                },
                onAlign = { alignHeadToPhone() },
                onRetry = {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastEyeRetryMs >= 2_000) {
                        lastEyeRetryMs = now
                        DiagnosticLog.record("App", "activity=$activityInstance manual Eye + IMU reconnect")
                        clearHeadAlignment()
                        eyeCamera.stop()
                        headMotion.stop()
                        eyeCamera.start()
                        headMotion.start()
                    }
                },
                onPhoneCamera = { selectCamera(false) },
                onPickDestination = { pickerVisible = true },
                onExportLog = { exportDiagnosticLog() },
                exportingLog = exportingLog,
                onOpenTools = { startActivity(Intent(this@ArNavActivity, CampusActivity::class.java)) },
                onOpenCompanion = { startActivity(Intent(this@ArNavActivity, com.sarab.vision.platform.CompanionActivity::class.java)) },
                locationGranted = hasLocationPermission,
                onEnableLocation = {
                    if (hasLocationPermission) {
                        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    } else if (locationPermissionRequested &&
                        !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:$packageName")))
                    } else {
                        locationPermissionRequested = true
                        locationPermissionLauncher.launch(arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ))
                    }
                }
            )
            DestinationPicker(
                landmarks = campus.landmarks,
                userFix = campus.fix,
                visible = pickerVisible,
                onSelect = {
                    campus.selectTarget(it)
                    pickerVisible = false
                },
                onDismiss = { pickerVisible = false },
                onOpenTools = {
                    pickerVisible = false
                    startActivity(Intent(this@ArNavActivity, CampusActivity::class.java))
                }
            )
            hudMenuView(hudMenu, campus.fix?.position)?.let { menuView ->
                GlassesHud(campus.guidance, campus.target?.name, null, false, menuView)
                TextButton(onClick = { hudMenu = HudMenuState.Closed },
                    modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
                    Text("إغلاق القائمة", color = Color.White)
                }
            }
        }
    }

    private fun clearHeadAlignment() {
        northOffset = null
        horizonPitch = null
        horizonRoll = null
        alignedSession = null
        if (campus.externalTrackingEnabled) campus.updateExternalHeading(null)
    }

    private fun alignHeadToPhone() {
        val motion = headMotion.state.value
        val yaw = motion.yawDegrees ?: return
        val pitch = motion.pitchDegrees?.takeIf { it.isFinite() } ?: return
        val roll = motion.rollDegrees?.takeIf { it.isFinite() } ?: return
        val phone = campus.phoneHeadingDegrees ?: return
        if (!motion.tracking || !campus.phoneHeadingReliable ||
            (!campus.hasNorthReference && !campus.demoActive)) return
        // One explicit alignment establishes north. Later phone movement must
        // never steer arrows over a camera mounted on the wearer's head.
        northOffset = phone - yaw
        // The first IMU stillness may happen while looking down at the phone.
        // The explicit horizon instruction establishes the camera's level pose.
        horizonPitch = pitch
        horizonRoll = roll
        alignedSession = motion.sessionId
        DiagnosticLog.record("Alignment", "Explicit north + horizon alignment accepted; session=${motion.sessionId}")
        refreshHeadHeading()
    }

    private fun refreshHeadHeading() {
        if (!useEyeCamera || !campus.externalTrackingEnabled) return
        val motion = headMotion.state.value
        if (!motion.tracking || alignedSession != motion.sessionId) {
            clearHeadAlignment()
            return
        }
        val offset = northOffset
        val yaw = motion.yawDegrees
        if (offset == null || yaw == null || !eyeCamera.state.value.streaming) {
            campus.updateExternalHeading(null)
            return
        }
        val heading = ((yaw + offset) % 360.0 + 360.0) % 360.0
        val pitch = alignedGlassesTilt(motion.pitchDegrees, horizonPitch)
        if (pitch == null || alignedGlassesTilt(motion.rollDegrees, horizonRoll) == null) {
            campus.updateExternalHeading(null)
            return
        }
        val tilt = kotlin.math.abs(kotlin.math.sin(Math.toRadians(pitch))).toFloat()
        campus.updateExternalHeading(heading, tilt)
    }

    private fun selectCamera(eye: Boolean) {
        if (useEyeCamera == eye) return
        useEyeCamera = eye
        DiagnosticLog.record("App", "Camera source selected: ${if (eye) "Eye + glasses IMU" else "phone ARCore"}")
        requestedOrientation = if (eye) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        applyCameraWindow()
        getPreferences(MODE_PRIVATE).edit().putBoolean("use_eye_camera", eye).apply()
        unsupportedReason = null
        clearHeadAlignment()
        if (resumed) startNavigation()
    }

    private fun startNavigation() {
        if (!hasCameraPermission || !resumed) return
        campus.enableExternalTracking(useEyeCamera)
        campus.startSensors()
        campus.onUpdate = { syncRenderer(); onGuidanceTick() }
        glassesMirror = true
        glasses.setMirror(true)
        glasses.start()
        if (useEyeCamera) {
            // ARCore's camera pose and floor belong to the phone. Feeding Eye
            // images into that session would put arrows in the wrong frame.
            if (session != null) {
                surfaceView.onPause()
                renderer.session = null
                session?.pause()
                session?.close()
                session = null
            }
            signReader.reset()
            signMatch = null
            eyeCamera.start()
            headMotion.start()
            refreshHeadHeading()
        } else {
            eyeCamera.stop()
            headMotion.stop()
            syncRenderer()
            ensureSessionAndResume()
        }
    }

    @androidx.compose.runtime.Composable
    private fun ArNavContent() {
        val target = campus.target
        val guidance = campus.guidance

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(factory = { surfaceView }, modifier = Modifier.fillMaxSize())

            // The scripted demo drives itself from a timer, so nothing on
            // screen depends on a sensor or a connection while it runs.
            LaunchedEffect(campus.tour.running) {
                var last = System.currentTimeMillis()
                while (campus.tour.running) {
                    kotlinx.coroutines.delay(60)
                    val now = System.currentTimeMillis()
                    campus.tickTour(now - last)
                    last = now
                    syncRenderer()
                }
            }

            TourOverlay(
                tour = campus.tour,
                onStop = { campus.stopTour() },
                modifier = Modifier.align(Alignment.TopCenter)
            )


            // The guided start-up. Sits above everything: until ARCore has
            // tracking there is nothing useful behind it, and a live-looking
            // camera invites the user to stand still, which is exactly what
            // stops tracking from ever starting.
            CalibrationOverlay(
                state = calibration,
                onSkip = {
                    calibration = CalibrationState(CalibrationStep.DONE, 1f, "", "")
                }
            )

            // Destination picker, over the camera. The camera is the app, so
            // choosing where to go must not mean leaving it.
            DestinationPicker(
                landmarks = campus.landmarks,
                userFix = campus.fix,
                visible = pickerVisible,
                onSelect = {
                    campus.selectTarget(it)
                    pickerVisible = false
                    syncRenderer()
                },
                onDismiss = { pickerVisible = false },
                onOpenTools = {
                    pickerVisible = false
                    startActivity(Intent(this@ArNavActivity, CampusActivity::class.java))
                }
            )

            // The "turn this way" arrow.
            //
            // The threshold was pushed out to 55 degrees to stop it sitting on
            // top of the ground path. That went too far: by 30 degrees off the
            // path has already slid out of frame, so the user was left with
            // nothing to follow at exactly the angle where they needed it
            // most. 25 keeps it out of the way when the path is visible and
            // present when it is not.
            (guidance as? GuidanceMode.Compass)?.let { c ->
                if (kotlin.math.abs(c.relativeDegrees) > 25) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        DirectionArrow(relativeDegrees = c.relativeDegrees)
                    }
                }
            }

            // The compass ribbon: which way am I facing, and where is the
            // destination relative to that. This is the instrument that makes
            // a bare camera view navigable.
            //
            // statusBars padding is essential: the camera fills the screen
            // edge to edge, so without it the ribbon renders UNDERNEATH the
            // system clock and battery icons.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                CompassBar(
                    headingDegrees = campus.headingDegrees,
                    targetBearingDegrees = campus.targetBearing(),
                    targetDistanceMeters = (campus.guidance as? GuidanceMode.Compass)
                        ?.distanceMeters
                        ?: (campus.guidance as? GuidanceMode.ArApproach)?.distanceMeters
                        ?: 0.0,
                    targetName = target?.name,
                    travelMode = campus.travelMode,
                    onModeChange = { campus.chooseTravelMode(it) },
                    needsCalibration = campus.compassNeedsCalibration
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp)
                ) {
                    Text(
                        "كاميرا النظارة",
                        color = Color(0xFF4FC3F7),
                        fontSize = 13.sp,
                        modifier = Modifier
                            .background(Color(0xCC16202C), RoundedCornerShape(10.dp))
                            .clickable { selectCamera(true) }
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "رجوع",
                        color = Color(0xFF4FC3F7),
                        fontSize = 13.sp,
                        modifier = Modifier
                            .background(Color(0xCC16202C), RoundedCornerShape(10.dp))
                            .clickable { finish() }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = statusText(),
                        color = Muted,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .background(Color(0xCC16202C), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )

                    // What the glasses show, chosen by the wearer: the black
                    // instrument HUD suits see-through optics, but some want
                    // the whole screen -- camera, menus, map -- up there, and
                    // that is plain mirroring, which the HUD must get out of
                    // the way for.
                    if (glassesConnected) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (glassesMirror) "النظارة: شاشة كاملة"
                            else "النظارة: مؤشرات",
                            color = Color(0xFFFFB300),
                            fontSize = 11.sp,
                            modifier = Modifier
                                .background(Color(0xCC16202C), RoundedCornerShape(10.dp))
                                .clickable {
                                    glassesMirror = !glassesMirror
                                    glasses.setMirror(glassesMirror)
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                // Inside the column, below the status row.
                //
                // This used to float at a hardcoded 150dp from the top of the
                // screen, which put it straight on top of the back button and
                // the status text on this device -- the offset happened to
                // match whatever inset the phone it was written against had.
                // Laying it out in the flow means it cannot collide with them
                // on any device.
                if (!campus.tour.running && !pickerVisible &&
                    calibration.step == CalibrationStep.DONE
                ) {
                    Spacer(Modifier.height(10.dp))
                    Row(modifier = Modifier.padding(horizontal = 14.dp)) {
                        StartTourButton(
                            visible = true,
                            onStart = { campus.startTour() }
                        )
                    }
                }
            }

            // The bottom stack: what the camera read, then what to do about it.
            val ambiguous = guidance as? GuidanceMode.Ambiguous
            val signResolved = signMatch is SignMatch.Found

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                // A read plaque sits above everything: it is the most specific
                // thing the app knows, and on this campus it is the only thing
                // that separates one college from its identical neighbour.
                if (!pickerVisible) {
                    SignOverlay(
                        match = signMatch,
                        currentTargetId = target?.id,
                        onNavigateTo = {
                            campus.selectTarget(it)
                            signMatch = null
                        },
                        modifier = Modifier.padding(horizontal = 14.dp)
                    )
                }

            // Ambiguity takes over the bottom when GPS cannot decide -- unless
            // the camera already settled it, in which case asking would be
            // asking a question the app can already answer.
            if (ambiguous != null && !signResolved) {
                AmbiguityPrompt(
                    candidates = ambiguous.candidates,
                    onPick = { campus.selectTarget(it) }
                )
            } else if (!pickerVisible && ambiguous == null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // navigationBars padding keeps the card clear of the
                        // gesture bar, which otherwise swallows its buttons.
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(14.dp)
                        .background(Color(0xE6121A24), RoundedCornerShape(18.dp))
                        .padding(16.dp)
                ) {
                    if (target == null) {
                        // No destination yet: the camera and compass still
                        // work, so prompt rather than blocking the view.
                        Text(
                            "اختر وجهة للبدء",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "الكاميرا والبوصلة تعملان الآن",
                            color = Muted,
                            fontSize = 13.sp
                        )
                    } else {
                        Text(
                            target.name,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            instructionAr(guidance, target.name),
                            color = Color(0xFF4FC3F7),
                            fontSize = 14.sp
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionChip(
                            if (target == null) "اختر وجهة" else "تغيير الوجهة",
                            Modifier.weight(1f)
                        ) { pickerVisible = true }
                        ActionChip("أدوات", Modifier.weight(1f)) {
                            startActivity(
                                Intent(this@ArNavActivity, CampusActivity::class.java)
                            )
                        }
                    }
                }
            }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ActionChip(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = modifier
                .background(Color(0xFF243244), RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp)
        )
    }

    private fun statusText(): String = when (arState) {
        is CampusArState.Initialising -> "حرّك الجوال ببطء لتهيئة الكاميرا…"
        is CampusArState.WaitingForGps -> "بانتظار إشارة GPS…"
        is CampusArState.Navigating -> buildString {
            // Show the live compass reading. It is the clearest proof that
            // the phone knows which way it is pointing: turn around and the
            // number moves by 180.
            campus.headingDegrees?.let {
                append("اتجاهك ")
                append(it.toInt())
                append("° ")
            }
            campus.fix?.let {
                append("· دقة ")
                append(it.accuracyMeters.toInt())
                append(" م")
            }
            if (isEmpty()) append("جاهز")
        }
        is CampusArState.Error -> (arState as CampusArState.Error).message
    }

    /**
     * Advances the start-up sequence from live sensor readings.
     *
     * Driven by measured readiness rather than a timer: dismissing the hint
     * before ARCore has tracking leaves the user staring at a camera that
     * cannot draw anything, and they blame the app rather than the pose.
     */
    /** True until the sequence has decided whether it needs to run at all. */
    private var calibrationEntryChecked = false

    private fun updateCalibration() {
        if (calibration.step == CalibrationStep.DONE) return

        val now = System.currentTimeMillis()
        if (stepStartedAt == 0L) stepStartedAt = now

        campus.headingDegrees?.let { sweep.update(it) }

        val input = CalibrationInput(
            tracking = arState is CampusArState.Navigating ||
                arState is CampusArState.WaitingForGps,
            compassReliable = !campus.compassNeedsCalibration,
            tilt = campus.cameraTilt,
            movedDegrees = sweep.totalDegrees,
            elapsedMs = now - stepStartedAt
        )

        // Decide once, on the first frame that carries real sensor readings,
        // whether any of this needs showing. A phone whose compass the OS has
        // already calibrated and whose camera is already tracking should go
        // straight to navigating without being told to wave anything.
        if (!calibrationEntryChecked) {
            calibrationEntryChecked = true
            val entry = initialCalibrationStep(input)
            if (entry != CalibrationStep.WAVE) {
                calibration = nextCalibrationState(entry, input)
                stepStartedAt = now
                sweep.reset()
                if (calibration.step == CalibrationStep.DONE) return
            }
        }

        val previousStep = calibration.step
        calibration = nextCalibrationState(previousStep, input)

        if (calibration.step != previousStep) {
            // Each step measures its own elapsed time and sweep.
            stepStartedAt = now
            sweep.reset()
        }
    }

    /**
     * How far away a building can be and still be worth reading a sign for.
     *
     * Wide enough to cover the whole cluster of neighbouring colleges, narrow
     * enough that the rare-word weighting is computed against the handful of
     * buildings actually in front of the user rather than the entire campus.
     */
    private val signCandidateRangeM = 120.0

    /** Pushes the latest GPS-derived aim into the renderer. */
    private fun syncRenderer() {
        renderer.signCandidates = signCandidatesNow()
        // Feed the real route through, so the ground ribbon follows the path
        // A* actually found rather than pointing through buildings.
        renderer.userPosition = campus.fix?.position
        renderer.routePoints = campus.route?.points.orEmpty()
        renderer.stairsAhead = campus.fix?.position?.let {
            stairsAhead(campus.route, campus.pathNetwork, it)
        } ?: false
        renderer.targetBearingDeg = campus.targetBearing()
        renderer.deviceHeadingDeg = campus.headingDegrees
        renderer.targetName = campus.target?.name
        renderer.targetDistanceM = when (val g = campus.guidance) {
            is GuidanceMode.Compass -> g.distanceMeters
            is GuidanceMode.ArApproach -> g.distanceMeters
            is GuidanceMode.Arrived -> g.distanceMeters
            is GuidanceMode.Ambiguous -> g.distanceMeters
            else -> 0.0
        }
    }

    /**
     * Buildings close enough to be the one in view.
     *
     * With no fix, every landmark is a candidate: the reader still works, it
     * just has more names to discriminate between.
     */
    private fun signCandidatesNow(): List<Landmark> {
        val here = campus.fix?.position?.takeIf { it.isValid } ?: return campus.landmarks.toList()
        return campus.landmarks.filter {
            distanceMeters(here, it.position) <= signCandidateRangeM
        }.ifEmpty { campus.landmarks.toList() }
    }

    private fun ensureSessionAndResume() {
        if (!hasCameraPermission || useEyeCamera) return

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

                // Same camera-config reasoning as the V2 screen: cap the CPU
                // image at 720p and refuse depth configs, which is what cut
                // memory from 352MB to 109MB and the temperature by 10C.
                try {
                    val filter = CameraConfigFilter(newSession)
                        .setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30))
                        .setDepthSensorUsage(
                            EnumSet.of(CameraConfig.DepthSensorUsage.DO_NOT_USE)
                        )
                    val configs = newSession.getSupportedCameraConfigs(filter)
                    val chosen = configs
                        .filter { it.imageSize.height >= MIN_CPU_IMAGE_HEIGHT }
                        .minByOrNull { it.imageSize.width.toLong() * it.imageSize.height }
                        ?: configs.maxByOrNull { it.imageSize.width.toLong() * it.imageSize.height }
                    chosen?.let {
                        newSession.cameraConfig = it
                        Log.i(TAG, "Camera config ${it.imageSize.width}x${it.imageSize.height}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not select camera config", e)
                }

                newSession.configure(
                    Config(newSession).apply {
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        lightEstimationMode = Config.LightEstimationMode.DISABLED
                        depthMode = Config.DepthMode.DISABLED
                    }
                )
                session = newSession
                renderer.session = newSession
                renderer.reset()
            } catch (e: UnavailableException) {
                unsupportedReason = "هذا الجهاز لا يدعم ARCore."
                return
            } catch (e: Exception) {
                Log.e(TAG, "Session creation failed", e)
                unsupportedReason = "تعذّر تشغيل AR: ${e.message}"
                return
            }
        }

        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            session = null
            renderer.session = null
            unsupportedReason = "الكاميرا غير متاحة. أغلق التطبيقات الأخرى وأعد المحاولة."
            return
        } catch (e: Exception) {
            Log.e(TAG, "Session resume failed", e)
            session?.close()
            session = null
            renderer.session = null
            unsupportedReason = "تعذّر تشغيل AR. أعد فتح التطبيق."
            return
        }

        surfaceView.onResume()
    }

    // ---- Eyes-free control -----------------------------------------------
    //
    // XREAL's gesture control recognises the hand ON the glasses (firmware
    // 1.9.1+, needs the Eye camera) and sends the result to the phone as
    // ordinary HID mouse events: scroll for an air-swipe, click for a pinch.
    // None of it needs a cursor position, so events are consumed globally and
    // position is ignored -- which also means any Bluetooth mouse, ring or
    // clicker drives the HUD identically. All sources reduce to HudCommand;
    // behaviour lives in the pure state machine in core/HudMenu.kt.

    /** Single entry point for every eyes-free input source. */
    private fun onHudCommand(command: HudCommand) {
        val before = selectedMenuName()
        val result = advanceHudMenu(
            hudMenu, command, campus.landmarks.toList(), campus.fix?.position
        )
        hudMenu = result.state

        when (val action = result.action) {
            is HudMenuAction.Select -> {
                campus.selectTarget(action.landmark)
                voice.speak("التوجه إلى ${action.landmark.name}")
            }
            HudMenuAction.RepeatInstruction -> campus.target?.let {
                voice.speak(instructionAr(campus.guidance, it.name))
            }
            HudMenuAction.None -> Unit
        }

        // Speak the highlighted name as the wearer cycles: with the phone
        // lowered, the ears confirm what the glasses show.
        val after = selectedMenuName()
        if (after != null && after != before) voice.speak(after)
    }

    private fun selectedMenuName(): String? = when (val m = hudMenu) {
        is HudMenuState.DestinationMenu -> m.items[m.selected].name
        is HudMenuState.AmbiguityMenu -> m.candidates[m.selected].name
        is HudMenuState.NextSuggestion -> m.suggestion.name
        is HudMenuState.Closed -> null
    }

    /** Runs on every sensor update: menu sync and voice, from pure policy. */
    private fun onGuidanceTick() {
        val guidance = campus.guidance
        hudMenu = syncHudMenu(
            hudMenu, previousGuidance, guidance,
            campus.landmarks.toList(), campus.target, campus.fix?.position
        )

        // Voice only speaks while glasses are up: it exists so the wearer
        // can keep their eyes on the path, and unprompted speech in the
        // phone-only flow would be a surprise nobody asked for. Keyed on
        // connected, not hudShown, so mirror mode keeps its voice.
        if (glasses.connected) {
            val cue = campus.target?.let { voiceCueAr(guidance, it.name) }
            val urgent = guidance is GuidanceMode.Arrived ||
                guidance is GuidanceMode.Ambiguous
            val (next, toSpeak) =
                nextVoiceCue(voiceState, cue, urgent, System.currentTimeMillis())
            voiceState = next
            toSpeak?.let { voice.speak(it) }
        }
        previousGuidance = guidance
    }

    /**
     * Media buttons as navigation commands.
     *
     * This is what makes cheap Bluetooth rings and clickers work as glasses
     * remotes, and catches the glasses' own controls should the firmware
     * send consumer-control codes. A fake "playing" state is required for
     * Android to route buttons here at all; the session only activates while
     * glasses are connected so ordinary music control is otherwise untouched.
     */
    private fun setUpMediaSession() {
        mediaSession = MediaSession(this, "SarabVisionHud").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val key = if (Build.VERSION.SDK_INT >= 33) {
                        mediaButtonIntent.getParcelableExtra(
                            Intent.EXTRA_KEY_EVENT, KeyEvent::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    } ?: return false
                    if (key.action != KeyEvent.ACTION_DOWN) return true
                    when (key.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_NEXT -> onHudCommand(HudCommand.NEXT)
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> onHudCommand(HudCommand.PREV)
                        KeyEvent.KEYCODE_MEDIA_PLAY,
                        KeyEvent.KEYCODE_MEDIA_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_HEADSETHOOK -> onHudCommand(HudCommand.CONFIRM)
                        else -> return false
                    }
                    return true
                }
            })
            setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                    .setActions(
                        PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT or
                            PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    )
                    .build()
            )
            // Not active yet: activation follows glasses connection.
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Volume as navigation only while the HUD owns the glasses: gesture
        // recognition degrades in harsh backlight (XREAL documents this),
        // and campus noon sun is exactly when a physical key must still
        // work. In mirror mode the phone behaves normally.
        if (glasses.hudShown) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    onHudCommand(HudCommand.NEXT); return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    onHudCommand(HudCommand.PREV); return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (glasses.hudShown && event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val now = System.currentTimeMillis()
            // One command per burst: a single air-swipe emits a stream of
            // scroll ticks, and stepping once per tick would fly past the
            // intended item.
            if (v != 0f && now - lastScrollCommandMs > 250) {
                lastScrollCommandMs = now
                onHudCommand(if (v < 0) HudCommand.NEXT else HudCommand.PREV)
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // A pinch-click must confirm the glasses menu, never tap whatever
        // Compose element happens to sit under an invisible cursor. Only
        // while the HUD owns the glasses: in mirror mode the wearer can see
        // the cursor, so the mouse behaves like a mouse.
        if (glasses.hudShown && ev.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                onHudCommand(HudCommand.CONFIRM)
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        logLifecycle("resumed")
        applyCameraWindow()
        resumed = true
        hasLocationPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        hasCameraPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCameraPermission) {
            if (!cameraPermissionRequested) {
                cameraPermissionRequested = true
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            return
        }

        startNavigation()
    }

    override fun onPause() {
        logLifecycle("paused")
        resumed = false
        super.onPause()
        glasses.stop()
        campus.onUpdate = null
        campus.stopSensors()
        campus.enableExternalTracking(false)
        if (session != null) {
            surfaceView.onPause()
            session?.pause()
        }
        // Forget the streak: whatever was in frame is not what will be in
        // frame when the camera comes back.
        signReader.reset()
        signMatch = null
        // Re-evaluate on the way back in: the phone may have been put in a
        // pocket next to a magnet, or picked up already tracking.
        calibrationEntryChecked = false
    }

    override fun onStop() {
        com.sarab.vision.platform.PlatformStore.get(this).apply { cameraConnected = false; imuTracking = false }
        logLifecycle("stopped changingConfigurations=$isChangingConfigurations finishing=$isFinishing")
        // USB permission dialogs can pause the Activity; stopping onStop
        // leaves their pending grant alive while still closing in background.
        eyeCamera.stop()
        headMotion.stop()
        clearHeadAlignment()
        super.onStop()
    }

    override fun onDestroy() {
        logLifecycle("destroyed changingConfigurations=$isChangingConfigurations finishing=$isFinishing")
        eyeCamera.stop()
        headMotion.stop()
        voice.shutdown()
        mediaSession?.release()
        mediaSession = null
        signReader.close()
        renderer.signReader = null
        renderer.session = null
        session?.close()
        session = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        logLifecycle("configuration changed")
        applyCameraWindow()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyCameraWindow()
    }

    private fun applyCameraWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, !useEyeCamera)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (useEyeCamera)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (useEyeCamera) hide(WindowInsetsCompat.Type.systemBars())
            else show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun logLifecycle(event: String) {
        val config = resources.configuration
        DiagnosticLog.record("App", "activity=$activityInstance $event eye=$useEyeCamera " +
            "orientation=${config.orientation} screenDp=${config.screenWidthDp}x${config.screenHeightDp} " +
            "density=${config.densityDpi} keyboard=${config.keyboard} navigation=${config.navigation}")
    }
}
