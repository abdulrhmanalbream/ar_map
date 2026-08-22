package com.sarab.vision

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.sarab.vision.core.CalibrationState
import com.sarab.vision.core.CalibrationStep
import com.sarab.vision.core.initialCalibrationStep
import com.sarab.vision.core.GuidanceMode
import com.sarab.vision.core.Landmark
import com.sarab.vision.core.SignMatch
import com.sarab.vision.core.distanceMeters
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
import com.sarab.vision.ui.SignOverlay
import com.sarab.vision.ui.StartTourButton
import com.sarab.vision.ui.TourOverlay
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
        if (granted) ensureSessionAndResume()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        campus = CampusApp.state(this)
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

        // Back must not dump the user straight out of the app.
        //
        // The camera is the launcher screen, so the default behaviour was to
        // exit on the first press regardless of what was open. Now back
        // closes whatever is showing first, and only leaves once there is
        // genuinely nothing left to dismiss.
        onBackPressedDispatcher.addCallback(this) {
            when {
                pickerVisible -> pickerVisible = false
                calibration.step != CalibrationStep.DONE ->
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
                            actionLabel = "السماح",
                            onAction = {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        )

                        unsupportedReason != null -> BlockingMessage(
                            title = "AR غير مدعوم",
                            body = unsupportedReason!!
                        )

                        else -> ArNavContent()
                    }
                }
            }
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

            if (!campus.tour.running && !pickerVisible &&
                calibration.step == CalibrationStep.DONE
            ) {
                StartTourButton(
                    visible = true,
                    onStart = { campus.startTour() },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 150.dp)
                )
            }

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

            // The "turn this way" arrow, shown ONLY when the destination is
            // off screen entirely.
            //
            // Once the path is visible on the ground it says everything this
            // does and says it better, so leaving both up puts a flat overlay
            // arrow on top of the thing it is describing. This is the fallback
            // for when the user is facing the wrong way and there is nothing
            // to look at yet.
            (guidance as? GuidanceMode.Compass)?.let { c ->
                if (kotlin.math.abs(c.relativeDegrees) > 55) {
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
                    modifier = Modifier.padding(horizontal = 14.dp)
                ) {
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
        if (!hasCameraPermission) return

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

    override fun onResume() {
        super.onResume()
        hasCameraPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }

        campus.startSensors()
        campus.onUpdate = { syncRenderer() }
        syncRenderer()
        ensureSessionAndResume()
    }

    override fun onPause() {
        super.onPause()
        campus.onUpdate = null
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

    override fun onDestroy() {
        signReader.close()
        renderer.signReader = null
        renderer.session = null
        session?.close()
        session = null
        super.onDestroy()
    }
}
