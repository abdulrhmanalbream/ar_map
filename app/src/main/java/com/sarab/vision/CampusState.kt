package com.sarab.vision

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sarab.vision.core.GpsFix
import com.sarab.vision.core.GuidanceMode
import com.sarab.vision.core.Landmark
import com.sarab.vision.core.LandmarkCategory
import com.sarab.vision.core.LandmarkPhoto
import com.sarab.vision.core.LatLng
import com.sarab.vision.core.Viewpoint
import com.sarab.vision.core.DemoCampus
import com.sarab.vision.core.averageFixes
import com.sarab.vision.core.bearingDegrees
import com.sarab.vision.core.distanceMeters
import com.sarab.vision.core.stepAlongBearing
import com.sarab.vision.core.stepTowards
import com.sarab.vision.core.findAmbiguousPairs
import com.sarab.vision.core.guidanceFor
import com.sarab.vision.data.LandmarkStore
import com.sarab.vision.loc.HeadingProvider
import com.sarab.vision.loc.LocationProvider

private const val TAG = "SarabCampus"

/** Which screen the user is on. */
enum class AppMode { NAVIGATE, LIST, MAP, SURVEY }

/**
 * Holds all campus navigation state and owns the GPS/compass providers.
 *
 * Deliberately not a ViewModel: the AR session, camera and sensors are all
 * tied to the Activity lifecycle, and adding a lifecycle-surviving component
 * on top would create two sources of truth about when sensors are running.
 */
class CampusState(private val context: Context) {

    private val store = LandmarkStore(context)
    private val location = LocationProvider(context)
    private val heading = HeadingProvider(context)

    var mode by mutableStateOf(AppMode.LIST)
    var landmarks = mutableStateListOf<Landmark>()
        private set

    var fix by mutableStateOf<GpsFix?>(null)
        private set
    var headingDegrees by mutableStateOf<Double?>(null)
        private set

    var target by mutableStateOf<Landmark?>(null)
        private set

    var guidance by mutableStateOf<GuidanceMode>(GuidanceMode.NoFix)
        private set

    /**
     * Notified whenever position, heading or target changes.
     *
     * The AR renderer runs on the GL thread and cannot observe Compose state,
     * so it needs an explicit push of the latest bearing and distance.
     */
    var onUpdate: (() -> Unit)? = null

    /** GPS samples accumulated for the landmark currently being captured. */
    private val surveySamples = mutableListOf<GpsFix>()
    var surveySampleCount by mutableStateOf(0)
        private set

    /** Photos taken for the landmark currently being captured. */
    private val pendingPhotos = mutableListOf<LandmarkPhoto>()
    var pendingPhotoCount by mutableStateOf(0)
        private set

    /** Set when two captured landmarks are too close for GPS to separate. */
    var ambiguityWarning by mutableStateOf<String?>(null)

    // ---- Demo mode ------------------------------------------------------

    /**
     * Demo mode replaces GPS with a simulated position.
     *
     * It exists because the whole system is otherwise unjudgeable without
     * physically standing on the campus. Real landmarks are kept untouched
     * and restored on exit, so trying the demo can never damage a survey.
     */
    var demoActive by mutableStateOf(false)
        private set

    private var simulatedPosition: LatLng? = null
    private var simulatedHeading: Double = 0.0
    private var realLandmarksBackup: List<Landmark> = emptyList()

    val photoDir get() = store.photoDir

    fun load() {
        landmarks.clear()
        landmarks.addAll(store.load())
        Log.i(TAG, "Loaded ${landmarks.size} landmarks")
        refreshAmbiguityWarning()
    }

    fun startSensors(): Boolean {
        heading.start()
        heading.onHeading = { deg ->
            // Demo mode normally owns the heading, since a live reading would
            // fight the simulated turns. But when the user explicitly asks to
            // test the real compass, it wins.
            if (!demoActive || useRealHeading) {
                headingDegrees = deg
                recomputeGuidance()
            }
        }

        location.onFix = { f ->
            // Demo mode supplies its own position; a real fix arriving here
            // would teleport the user out of the simulated campus.
            if (!demoActive) {
                fix = f
                // Feed declination to the compass so its heading is
                // true-north, matching the GPS bearings we navigate by.
                heading.magneticDeclination = declinationFor(f.position)

                if (mode == AppMode.SURVEY) {
                    surveySamples.add(f)
                    // Keep only recent samples: older ones may be from before
                    // the surveyor reached the entrance.
                    if (surveySamples.size > 12) surveySamples.removeAt(0)
                    surveySampleCount = surveySamples.size
                }
                recomputeGuidance()
            }
        }
        return location.start()
    }

    fun stopSensors() {
        location.stop()
        heading.stop()
    }

    fun isGpsEnabled() = location.isGpsEnabled()

    fun selectTarget(landmark: Landmark?) {
        target = landmark
        recomputeGuidance()
    }

    /**
     * Starts demo mode, building a fake campus around the current position.
     *
     * Falls back to a default coordinate when there is no GPS fix yet, so the
     * demo works indoors, on a plane, or anywhere GPS has not locked on --
     * which is precisely when someone needs it most.
     */
    fun startDemo() {
        if (demoActive) return

        realLandmarksBackup = landmarks.toList()

        val centre = fix?.position?.takeIf { it.isValid }
            ?: LatLng(24.4672, 39.6111) // fallback so the demo always works
        simulatedPosition = centre
        simulatedHeading = headingDegrees ?: 0.0

        val demoLandmarks = DemoCampus.generate(centre, simulatedHeading)
        landmarks.clear()
        landmarks.addAll(demoLandmarks)

        demoActive = true
        // Preselect the far landmark so the compass and distant marker are
        // the first things seen.
        target = demoLandmarks.firstOrNull { it.name.contains("الملعب") }
        publishSimulatedFix()
        Log.i(TAG, "Demo started at $centre with ${demoLandmarks.size} landmarks")
    }

    /** Ends demo mode and restores the real survey untouched. */
    fun stopDemo() {
        if (!demoActive) return
        demoActive = false
        simulatedPosition = null

        landmarks.clear()
        landmarks.addAll(realLandmarksBackup)
        target = null

        // Fall back to whatever the real providers last reported.
        fix = location.lastFix
        headingDegrees = null
        recomputeGuidance()
        Log.i(TAG, "Demo stopped; restored ${landmarks.size} real landmarks")
    }

    /** Simulated walking. Negative values step backwards. */
    fun demoWalk(metres: Double) {
        if (!demoActive) return
        val from = simulatedPosition ?: return

        simulatedPosition = if (metres >= 0) {
            val t = target?.position
            if (t != null) stepTowards(from, t, metres)
            else stepAlongBearing(from, simulatedHeading, metres)
        } else {
            // Walking "backwards" means away from the target, which is the
            // useful thing to test (does the arrow turn around?).
            val t = target?.position
            val awayBearing = if (t != null) {
                (bearingDegrees(from, t) + 180.0) % 360.0
            } else {
                (simulatedHeading + 180.0) % 360.0
            }
            stepAlongBearing(from, awayBearing, -metres)
        }
        publishSimulatedFix()
    }

    /** Jumps to just short of the target, to test arrival and ambiguity. */
    fun demoTeleportToTarget() {
        if (!demoActive) return
        val t = target?.position ?: return
        simulatedPosition = t
        publishSimulatedFix()
    }

    /** Simulated turning, so the arrow can be checked without moving. */
    fun demoTurn(degrees: Double) {
        if (!demoActive) return
        useRealHeading = false
        simulatedHeading = (simulatedHeading + degrees + 360.0) % 360.0
        headingDegrees = simulatedHeading
        recomputeGuidance()
    }

    /**
     * When true, the real magnetometer drives the arrow even in demo mode.
     *
     * Simulated turning proves the maths; only the real compass proves the
     * phone knows which way it is physically pointing. Turning the phone 180
     * degrees by hand and watching the arrow swing is the convincing test.
     */
    var useRealHeading by mutableStateOf(false)
        private set

    fun toggleRealHeading() {
        useRealHeading = !useRealHeading
        if (!useRealHeading) {
            // Freeze at whatever the compass last read, so the arrow does not
            // jump when handing control back to the buttons.
            simulatedHeading = headingDegrees ?: simulatedHeading
        }
        recomputeGuidance()
    }

    /**
     * Drops a target a few metres ahead, in the direction currently faced.
     *
     * Built for the laptop test: put the printed marker on a screen in front
     * of you, tap this, and the app should guide you straight to it. It is
     * the closest thing to a real destination that fits indoors.
     */
    fun placeTargetAhead(metres: Double = 5.0) {
        val from = fix?.position ?: simulatedPosition ?: return
        if (!from.isValid) return

        val facing = headingDegrees ?: simulatedHeading
        val position = stepAlongBearing(from, facing, metres)

        val marker = Landmark(
            id = "demo-ahead-${System.currentTimeMillis()}",
            name = "الهدف التجريبي",
            category = LandmarkCategory.OTHER,
            position = position,
            detail = "هدف وُضع أمامك مباشرة لاختبار السهم والمسار.",
            capturedAccuracyM = 3f
        )

        landmarks.add(marker)
        target = marker
        recomputeGuidance()
        Log.i(TAG, "Placed demo target ${metres}m ahead at bearing $facing")
    }

    private fun publishSimulatedFix() {
        val p = simulatedPosition ?: return
        fix = GpsFix(position = p, accuracyMeters = 5f, timestampMs = System.currentTimeMillis())
        headingDegrees = simulatedHeading
        recomputeGuidance()
    }

    private fun recomputeGuidance() {
        val position = fix?.position
        val t = target
        guidance = if (position == null || t == null) {
            GuidanceMode.NoFix
        } else {
            guidanceFor(position, headingDegrees, t, landmarks)
        }
        onUpdate?.invoke()
    }

    // ---- Survey ---------------------------------------------------------

    fun beginCapture() {
        surveySamples.clear()
        surveySampleCount = 0
        pendingPhotos.clear()
        pendingPhotoCount = 0
    }

    fun addPendingPhoto(fileName: String, viewpoint: Viewpoint) {
        val currentFix = fix
        val currentTargetPos = surveyAveragePosition()

        pendingPhotos.add(
            LandmarkPhoto(
                file = fileName,
                viewpoint = viewpoint,
                takenFrom = currentFix?.position,
                bearingDegrees = headingDegrees,
                // Distance from where the photo was taken to the landmark
                // position, so the app can later show the shot that matches
                // the direction a user is approaching from.
                distanceMeters = if (currentFix != null && currentTargetPos != null) {
                    distanceMeters(currentFix.position, currentTargetPos)
                } else {
                    null
                }
            )
        )
        pendingPhotoCount = pendingPhotos.size
    }

    private fun surveyAveragePosition(): LatLng? =
        averageFixes(surveySamples) ?: fix?.position

    /**
     * Saves the landmark being surveyed.
     *
     * Uses the averaged position rather than a single fix: individual GPS
     * readings scatter by several metres, and that error is baked in
     * permanently once saved.
     */
    fun saveLandmark(name: String, category: LandmarkCategory, detail: String): Boolean {
        val position = surveyAveragePosition() ?: return false
        if (!position.isValid) return false

        val bestAccuracy = surveySamples.minByOrNull { it.accuracyMeters }?.accuracyMeters
            ?: fix?.accuracyMeters
            ?: return false

        val landmark = Landmark(
            id = "lm-${System.currentTimeMillis()}",
            name = name,
            category = category,
            position = position,
            detail = detail,
            photos = pendingPhotos.toList(),
            capturedAccuracyM = bestAccuracy
        )

        landmarks.clear()
        landmarks.addAll(store.add(landmark))
        beginCapture()
        refreshAmbiguityWarning()
        Log.i(TAG, "Saved landmark '$name' at $position (±${bestAccuracy}m)")
        return true
    }

    fun exportJson(): String = store.exportJson()

    /**
     * Warns the surveyor as soon as two landmarks are too close for GPS.
     *
     * Better to learn this while still standing there -- distinctive photos
     * of each entrance can be taken immediately -- than to discover it when
     * students start arriving at the wrong building.
     */
    private fun refreshAmbiguityWarning() {
        val pairs = findAmbiguousPairs(landmarks)
        ambiguityWarning = if (pairs.isEmpty()) {
            null
        } else {
            pairs.joinToString("، ") { (a, b) ->
                val d = distanceMeters(a.position, b.position).toInt()
                "${a.name} و${b.name} ($d م)"
            }
        }
    }

    /**
     * Magnetic declination for a position.
     *
     * Uses the platform's geomagnetic model, which is offline. Without this
     * correction the compass points at magnetic north while GPS bearings are
     * relative to true north -- a discrepancy large enough to aim the user at
     * the wrong building across a campus.
     */
    private fun declinationFor(position: LatLng): Float = try {
        android.hardware.GeomagneticField(
            position.latitude.toFloat(),
            position.longitude.toFloat(),
            0f,
            System.currentTimeMillis()
        ).declination
    } catch (e: Exception) {
        0f
    }

    /** Bearing from the user to the current target, if both are known. */
    fun targetBearing(): Double? {
        val p = fix?.position ?: return null
        val t = target?.position ?: return null
        if (!p.isValid) return null
        return bearingDegrees(p, t)
    }
}
