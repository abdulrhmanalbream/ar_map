package com.sarab.vision

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.sarab.vision.core.GuidanceMode
import com.sarab.vision.core.Landmark
import com.sarab.vision.core.LandmarkCategory
import com.sarab.vision.core.Viewpoint
import com.sarab.vision.core.instructionAr
import com.sarab.vision.ui.BlockingMessage
import com.sarab.vision.ui.CampusMapScreen
import com.sarab.vision.ui.DemoControls
import com.sarab.vision.ui.LandmarkListScreen
import com.sarab.vision.ui.SurveyScreen

/**
 * Campus navigation entry point (V3).
 *
 * Kept separate from [ArActivity] deliberately: that screen owns the V2
 * printed-marker flow with its own ARCore session lifecycle, and merging the
 * two would tangle two quite different state machines. This one is
 * GPS-first -- it works across the whole campus and needs no printed marker.
 */
class CampusActivity : ComponentActivity() {

    private lateinit var campus: CampusState

    private var hasLocationPermission by mutableStateOf(false)
    private var gpsDisabled by mutableStateOf(false)

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        hasLocationPermission = granted
        if (granted) startSensors()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        campus = CampusState(this)
        campus.load()
        hasLocationPermission = checkLocationPermission()

        setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0D1B2A))
            ) {
                when {
                    // Demo mode deliberately bypasses these gates: it needs
                    // no GPS at all, and it is exactly what someone away from
                    // the campus (or indoors with no fix) should be able to
                    // reach without first granting location access.
                    !hasLocationPermission && !campus.demoActive -> Column(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            BlockingMessage(
                                title = "نحتاج إذن الموقع",
                                body = "يستخدم التطبيق GPS لتحديد موقعك داخل الحرم وتوجيهك " +
                                    "إلى المباني. يعمل بالكامل بدون إنترنت، ولا تغادر بياناتك جهازك.",
                                actionLabel = "السماح",
                                onAction = { requestLocationPermission() }
                            )
                        }
                        DemoControls(
                            active = false,
                            guidance = campus.guidance,
                            targetName = null,
                            onStart = { campus.startDemo() },
                            onStop = { campus.stopDemo() },
                            onWalk = {},
                            onTeleportToTarget = {},
                            onTurn = {}
                        )
                    }

                    gpsDisabled && !campus.demoActive -> BlockingMessage(
                        title = "خدمة الموقع مغلقة",
                        body = "فعّل خدمة الموقع (GPS) من إعدادات الجهاز حتى يتمكن " +
                            "التطبيق من تحديد موقعك.",
                        actionLabel = "فتح الإعدادات",
                        onAction = {
                            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        }
                    )

                    else -> when (campus.mode) {
                        AppMode.LIST -> Column(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.weight(1f)) {
                                LandmarkListScreen(
                                    landmarks = campus.landmarks,
                                    userFix = campus.fix,
                                    onSelect = { lm ->
                                        campus.selectTarget(lm)
                                        campus.mode = AppMode.NAVIGATE
                                    },
                                    onOpenMap = { campus.mode = AppMode.MAP },
                                    onOpenSurvey = {
                                        campus.beginCapture()
                                        campus.mode = AppMode.SURVEY
                                    },
                                    onClose = { finish() }
                                )
                            }
                            DemoControls(
                                active = campus.demoActive,
                                guidance = campus.guidance,
                                targetName = campus.target?.name,
                                onStart = { campus.startDemo() },
                                onStop = { campus.stopDemo() },
                                onWalk = { campus.demoWalk(it) },
                                onTeleportToTarget = { campus.demoTeleportToTarget() },
                                onTurn = { campus.demoTurn(it) }
                            )
                        }

                        AppMode.MAP -> CampusMapScreen(
                            landmarks = campus.landmarks,
                            userFix = campus.fix,
                            headingDegrees = campus.headingDegrees,
                            selectedId = campus.target?.id,
                            onSelect = { campus.selectTarget(it) },
                            onClose = { campus.mode = AppMode.LIST }
                        )

                        AppMode.SURVEY -> SurveyScreen(
                            fix = campus.fix,
                            samplesCollected = campus.surveySampleCount,
                            capturedLandmarks = campus.landmarks,
                            pendingPhotoCount = campus.pendingPhotoCount,
                            onCapturePhoto = { viewpoint -> capturePhoto(viewpoint) },
                            onSaveLandmark = { name, category, detail ->
                                saveLandmark(name, category, detail)
                            },
                            onExport = { exportSurvey() },
                            onExit = { campus.mode = AppMode.LIST }
                        )

                        AppMode.NAVIGATE -> {
                            // The AR navigation view launches the existing AR
                            // activity for close-range guidance; the compass
                            // view is shown until then.
                            NavigateRoute()
                        }
                    }
                }
            }
        }
    }

    /**
     * Navigation screen.
     *
     * Currently routes to the map with the target selected. The camera-based
     * AR view is wired separately so that a failure in ARCore can never take
     * the whole navigation feature down with it.
     */
    @androidx.compose.runtime.Composable
    private fun NavigateRoute() {
        val target = campus.target
        if (target == null) {
            campus.mode = AppMode.LIST
            return
        }
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                CampusMapScreen(
                    landmarks = campus.landmarks,
                    userFix = campus.fix,
                    headingDegrees = campus.headingDegrees,
                    selectedId = target.id,
                    onSelect = { campus.selectTarget(it) },
                    onClose = { campus.mode = AppMode.LIST }
                )
            }
            // Controls live here too: this is the screen where the arrow,
            // the distance countdown and the mode changes are actually
            // visible, so simulating movement belongs alongside them.
            if (campus.demoActive) {
                DemoControls(
                    active = true,
                    guidance = campus.guidance,
                    targetName = target.name,
                    onStart = { campus.startDemo() },
                    onStop = { campus.stopDemo() },
                    onWalk = { campus.demoWalk(it) },
                    onTeleportToTarget = { campus.demoTeleportToTarget() },
                    onTurn = { campus.demoTurn(it) }
                )
            }
        }
    }

    private fun capturePhoto(viewpoint: Viewpoint) {
        // Photo capture needs the AR camera session, which lives in
        // ArActivity. Until that hand-off is wired, tell the user plainly
        // rather than silently doing nothing.
        Toast.makeText(
            this,
            "التقاط الصور يتطلب فتح الكاميرا — قيد الربط",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun saveLandmark(name: String, category: LandmarkCategory, detail: String) {
        val ok = campus.saveLandmark(name, category, detail)
        Toast.makeText(
            this,
            if (ok) "تم حفظ: $name" else "تعذر الحفظ — تحقق من دقة GPS",
            Toast.LENGTH_SHORT
        ).show()

        campus.ambiguityWarning?.let { warning ->
            Toast.makeText(
                this,
                "تنبيه: معالم متقاربة يصعب على GPS التفريق بينها — $warning",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Shares the survey as JSON so it can be committed into the app. */
    private fun exportSurvey() {
        val json = campus.exportJson()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Sarab Vision - landmarks.json")
            putExtra(Intent.EXTRA_TEXT, json)
        }
        startActivity(Intent.createChooser(intent, "تصدير المعالم"))
    }

    private fun checkLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun startSensors() {
        if (!campus.isGpsEnabled()) {
            gpsDisabled = true
            return
        }
        gpsDisabled = false
        campus.startSensors()
    }

    override fun onResume() {
        super.onResume()
        hasLocationPermission = checkLocationPermission()
        if (hasLocationPermission) {
            startSensors()
        } else {
            requestLocationPermission()
        }
    }

    override fun onPause() {
        super.onPause()
        campus.stopSensors()
    }
}
