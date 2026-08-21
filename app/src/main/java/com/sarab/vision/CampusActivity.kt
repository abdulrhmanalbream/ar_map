package com.sarab.vision

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.sarab.vision.data.ImageImporter
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
import com.sarab.vision.ui.MapScreen
import com.sarab.vision.ui.PathEditorScreen
import com.sarab.vision.ui.DemoControls
import com.sarab.vision.ui.LandmarkListScreen
import com.sarab.vision.ui.LocationPickerScreen
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

        // Shared instance: the AR screen must see the same target, fix and
        // landmark set, otherwise choosing a destination here would mean
        // nothing over there.
        campus = CampusApp.state(this)
        hasLocationPermission = checkLocationPermission()

        // Back closes whatever is open rather than the whole screen.
        //
        // Without this, pressing back inside the map picker threw away a
        // half-finished landmark -- the name typed, the category chosen and
        // every photo attached -- with no warning and no way back.
        onBackPressedDispatcher.addCallback(this) {
            when {
                campus.pickingLocation -> campus.pickingLocation = false
                campus.mode != AppMode.LIST -> campus.mode = AppMode.LIST
                else -> finish()
            }
        }

        setContent {
            // Force right-to-left layout.
            //
            // The UI is Arabic but the device locale here is en-GB, so
            // Compose laid everything out left-to-right: the title and close
            // button overlapped, and mixed Arabic/number strings rendered in
            // the wrong order. Pinning the direction makes the layout correct
            // regardless of the phone's language setting.
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalLayoutDirection provides
                    androidx.compose.ui.unit.LayoutDirection.Rtl
            ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0D1B2A))
            ) {
                // Screens that can work with no live position at all.
                //
                // Survey mode can take its coordinate from the map picker and
                // the path editor is drawn by tapping, so blocking either of
                // them behind GPS would recreate exactly the dead end this
                // release exists to remove.
                val needsLiveFix = campus.mode != AppMode.SURVEY &&
                    campus.mode != AppMode.PATHS

                when {
                    // Demo mode deliberately bypasses these gates: it needs
                    // no GPS at all, and it is exactly what someone away from
                    // the campus (or indoors with no fix) should be able to
                    // reach without first granting location access.
                    !hasLocationPermission && !campus.demoActive && needsLiveFix -> Column(
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

                    gpsDisabled && !campus.demoActive && needsLiveFix -> BlockingMessage(
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
                                        // Straight into the camera view: the
                                        // point of the app is seeing the path
                                        // on the real ground, not reading a
                                        // map.
                                        campus.selectTarget(lm)
                                        startActivity(
                                            Intent(
                                                this@CampusActivity,
                                                ArNavActivity::class.java
                                            ).putExtra(ArNavActivity.EXTRA_TARGET_ID, lm.id)
                                        )
                                    },
                                    onOpenMap = { campus.mode = AppMode.MAP },
                                    onOpenSurvey = {
                                        campus.beginCapture()
                                        campus.mode = AppMode.SURVEY
                                    },
                                    onOpenPaths = { campus.mode = AppMode.PATHS },
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
                                onTurn = { campus.demoTurn(it) },
                                useRealHeading = campus.useRealHeading,
                                onToggleRealHeading = { campus.toggleRealHeading() },
                                onPlaceMarkerHere = { campus.placeTargetAhead() }
                            )
                        }

                        AppMode.MAP -> MapScreen(
                            landmarks = campus.landmarks,
                            route = campus.route,
                            pathNetwork = campus.pathNetwork,
                            userFix = campus.fix,
                            travelMode = campus.travelMode,
                            selectedName = campus.target?.name,
                            onModeChange = { campus.chooseTravelMode(it) },
                            onClose = { campus.mode = AppMode.LIST },
                            onStartAr = {
                                startActivity(
                                    Intent(this@CampusActivity, ArNavActivity::class.java)
                                )
                            }
                        )

                        // The picker is a full-screen step of the survey
                        // rather than a separate mode: the half-typed name and
                        // the photos already attached have to survive it.
                        AppMode.SURVEY -> if (campus.pickingLocation) {
                            LocationPickerScreen(
                                landmarks = campus.landmarks,
                                pathNetwork = campus.pathNetwork,
                                userFix = campus.fix,
                                initial = campus.manualPosition,
                                onConfirm = { position ->
                                    campus.chooseManualPosition(position)
                                    campus.pickingLocation = false
                                },
                                onClose = { campus.pickingLocation = false }
                            )
                        } else {
                            SurveyScreen(
                                fix = campus.surveyFix,
                                samplesCollected = campus.surveySampleCount,
                                capturedLandmarks = campus.landmarks,
                                pendingPhotoCount = campus.pendingPhotoCount,
                                pendingPhotos = campus.pendingPhotoList,
                                photoDir = campus.photoDir,
                                manualPosition = campus.manualPosition,
                                onPickLocation = { campus.pickingLocation = true },
                                onClearManualPosition = {
                                    campus.chooseManualPosition(null)
                                },
                                onCapturePhoto = { viewpoint -> capturePhoto(viewpoint) },
                                onSaveLandmark = { name, category, detail ->
                                    saveLandmark(name, category, detail)
                                },
                                onExport = { exportSurvey() },
                                onExit = { campus.mode = AppMode.LIST }
                            )
                        }

                        AppMode.PATHS -> PathEditorScreen(
                            network = campus.pathNetwork,
                            landmarks = campus.landmarks,
                            userFix = campus.fix,
                            onNetworkChange = { campus.updatePathNetwork(it) },
                            onExport = { exportPaths() },
                            onClose = { campus.mode = AppMode.LIST }
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
        // Navigation itself lives in the camera activity now, so reaching
        // here means the map is what is actually wanted.
        MapScreen(
            landmarks = campus.landmarks,
            route = campus.route,
            pathNetwork = campus.pathNetwork,
            userFix = campus.fix,
            travelMode = campus.travelMode,
            selectedName = target.name,
            onModeChange = { campus.chooseTravelMode(it) },
            onClose = { campus.mode = AppMode.LIST },
            onStartAr = {
                startActivity(Intent(this@CampusActivity, ArNavActivity::class.java))
            }
        )
    }

    /**
     * Which viewpoint the pending gallery pick is for.
     *
     * The picker result arrives asynchronously with no way to carry extras,
     * so the intent has to be remembered across the launch.
     */
    private var pendingViewpoint: Viewpoint = Viewpoint.ENTRANCE

    /**
     * Gallery picker for landmark photos.
     *
     * Uses the system photo picker rather than a storage permission: it needs
     * no permission at all, grants access to exactly the chosen image, and is
     * the route most users expect. It also means the campus photos already on
     * the phone can be attached directly, instead of requiring a fresh visit
     * to re-shoot everything.
     */
    private val photoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(6)
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        var imported = 0
        uris.forEachIndexed { index, uri ->
            val name = ImageImporter.importFromGallery(
                context = this,
                uri = uri,
                dir = campus.photoDir,
                // Indexed rather than counted: a failed import used to leave
                // the counter unchanged, so the next photo in the same batch
                // reused the name and overwrote it.
                baseName = "img-${System.currentTimeMillis()}-$index"
            )
            if (name != null) {
                campus.addPendingPhoto(name, pendingViewpoint)
                imported++
            }
        }
        val failed = uris.size - imported
        Toast.makeText(
            this,
            when {
                imported > 0 && failed == 0 -> "تمت إضافة $imported صورة"
                imported > 0 -> "تمت إضافة $imported صورة، وتعذّر قراءة $failed"
                else -> "تعذّر قراءة الصور — جرّب صورة بصيغة JPG أو PNG"
            },
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun capturePhoto(viewpoint: Viewpoint) {
        pendingViewpoint = viewpoint
        photoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
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

    /** Shares the drawn path network so it can be committed into assets/. */
    private fun exportPaths() {
        val json = campus.exportPathsJson()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Sarab Vision - paths.json")
            putExtra(Intent.EXTRA_TEXT, json)
        }
        startActivity(Intent.createChooser(intent, "تصدير شبكة الطرق"))
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
