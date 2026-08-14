package com.sarab.vision

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.sarab.vision.core.GuidanceMode
import com.sarab.vision.core.SweepTracker
import com.sarab.vision.core.nextCalibrationState
import com.sarab.vision.ui.CalibrationOverlay
import com.sarab.vision.core.formatDistanceAr
import com.sarab.vision.core.instructionAr
import com.sarab.vision.ui.AmbiguityPrompt
import com.sarab.vision.ui.BlockingMessage
import com.sarab.vision.ui.CompassBar
import com.sarab.vision.ui.DestinationPicker
import com.sarab.vision.ui.DirectionArrow
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

            // Direction arrow, shown whenever the target is not straight ahead.
            (guidance as? GuidanceMode.Compass)?.let { c ->
                if (kotlin.math.abs(c.relativeDegrees) > 20) {
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
            Column(modifier = Modifier.fillMaxWidth()) {
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

            // Ambiguity takes over the bottom when GPS cannot decide.
            val ambiguous = guidance as? GuidanceMode.Ambiguous
            if (ambiguous != null) {
                AmbiguityPrompt(
                    candidates = ambiguous.candidates,
                    onPick = { campus.selectTarget(it) },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            } else if (!pickerVisible) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
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

        val previousStep = calibration.step
        calibration = nextCalibrationState(previousStep, input)

        if (calibration.step != previousStep) {
            // Each step measures its own elapsed time and sweep.
            stepStartedAt = now
            sweep.reset()
        }
    }

    /** Pushes the latest GPS-derived aim into the renderer. */
    private fun syncRenderer() {
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
    }

    override fun onDestroy() {
        renderer.session = null
        session?.close()
        session = null
        super.onDestroy()
    }
}
