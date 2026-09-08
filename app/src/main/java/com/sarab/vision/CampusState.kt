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
import com.sarab.vision.core.Entrance
import com.sarab.vision.core.LandmarkPhoto
import com.sarab.vision.core.LatLng
import com.sarab.vision.core.Viewpoint
import com.sarab.vision.core.DemoCampus
import com.sarab.vision.core.PathNetwork
import com.sarab.vision.core.TourAction
import com.sarab.vision.core.TourState
import com.sarab.vision.core.advanceTour
import com.sarab.vision.core.tourTurnDegrees
import com.sarab.vision.core.tourWalkDistance
import com.sarab.vision.core.Route
import com.sarab.vision.core.TravelMode
import com.sarab.vision.core.averageFixes
import com.sarab.vision.core.routeTo
import com.sarab.vision.core.bearingDegrees
import com.sarab.vision.core.distanceMeters
import com.sarab.vision.core.stepAlongBearing
import com.sarab.vision.core.stepTowards
import com.sarab.vision.core.findAmbiguousPairs
import com.sarab.vision.core.guidanceFor
import com.sarab.vision.data.LandmarkStore
import com.sarab.vision.data.PathNetworkStore
import com.sarab.vision.loc.HeadingProvider
import com.sarab.vision.loc.LocationProvider

private const val TAG = "SarabCampus"

/**
 * Accuracy quoted for a point dropped by hand on satellite imagery.
 *
 * Esri's imagery is georeferenced to a few metres, and a careful tap adds a
 * couple more. It is deliberately not better than a good GPS fix: a manual
 * placement is a reasonable estimate, not a survey.
 */
private const val MANUAL_PLACEMENT_ACCURACY_M = 8f

/** Which screen the user is on. */
enum class AppMode { NAVIGATE, LIST, MAP, SURVEY, PATHS }

/** Identifies the device that owns navigation orientation, including while waiting for a sample. */
enum class CampusHeadingSource { PHONE, GLASSES, SIMULATED }

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

    /** A compass reference for an explicit glasses-to-north alignment, never automatic head tracking. */
    var phoneHeadingDegrees by mutableStateOf<Double?>(null)
        private set

    val phoneHeadingReliable: Boolean
        get() = phoneHeadingDegrees != null && !heading.needsCalibration &&
            heading.source == com.sarab.vision.core.HeadingSource.CAMERA &&
            heading.cameraTilt < 0.72f

    val hasNorthReference: Boolean
        get() = location.lastFix != null && !demoActive

    var externalTrackingEnabled by mutableStateOf(false)
        private set

    val headingSource: CampusHeadingSource
        get() = when {
            externalTrackingEnabled -> CampusHeadingSource.GLASSES
            demoActive && !useRealHeading -> CampusHeadingSource.SIMULATED
            else -> CampusHeadingSource.PHONE
        }

    /** Selecting glasses must also work before they connect, without borrowing the phone pose. */
    fun enableExternalTracking(enabled: Boolean) {
        if (externalTrackingEnabled == enabled) return
        externalTrackingEnabled = enabled
        headingDegrees = if (enabled) null else {
            if (demoActive && !useRealHeading) simulatedHeading else phoneHeadingDegrees
        }
        cameraTilt = if (enabled) 1f else heading.cameraTilt
        compassNeedsCalibration = if (enabled) true else heading.needsCalibration
        recomputeGuidance()
    }

    /** Main-thread input from the glasses provider after its explicit north alignment. */
    fun updateExternalHeading(degrees: Double?, tilt: Float = 1f) {
        if (!externalTrackingEnabled) return
        headingDegrees = degrees?.takeIf { it.isFinite() }?.let { ((it % 360.0) + 360.0) % 360.0 }
        cameraTilt = if (tilt.isFinite()) tilt.coerceIn(0f, 1f) else 1f
        compassNeedsCalibration = headingDegrees == null
        recomputeGuidance()
    }

    /**
     * True when the magnetometer reports itself unreliable.
     *
     * Worth surfacing rather than hiding: a phone near metal or a magnetic
     * case gives a confidently wrong heading, and the only fix is a physical
     * figure-of-eight wave that the user has to be told to perform.
     */
    var compassNeedsCalibration by mutableStateOf(false)
        private set

    /**
     * Camera tilt from level: 0 = looking at the horizon, 1 = straight down.
     *
     * Drives the start-up sequence, which needs to know when the phone has
     * actually been raised into the navigating pose.
     */
    var cameraTilt by mutableStateOf(1f)
        private set

    var target by mutableStateOf<Landmark?>(null)
        private set

    var guidance by mutableStateOf<GuidanceMode>(GuidanceMode.NoFix)
        private set

    /** How the user is travelling; changes which paths are routable. */
    var travelMode by mutableStateOf(TravelMode.WALK)
        private set

    /**
     * Named `chooseTravelMode` rather than `setTravelMode`: the latter clashes
     * with the setter Kotlin generates for the `travelMode` property.
     */
    fun chooseTravelMode(mode: TravelMode) {
        if (mode == travelMode) return
        travelMode = mode
        // Force a fresh route: the same start and end can yield a very
        // different path once cars are barred from footpaths.
        lastRoutedFrom = null
        recomputeGuidance()
    }

    /** The drawn campus path network, loaded from storage. */
    var pathNetwork by mutableStateOf(PathNetwork())
        private set

    private val pathStore = PathNetworkStore(context)

    /** Replaces and persists the network, then re-routes against it. */
    fun updatePathNetwork(network: PathNetwork) {
        pathNetwork = network
        pathStore.save(network)
        // The existing route was computed against the old graph, so it is
        // stale the moment the network changes.
        lastRoutedFrom = null
        recomputeGuidance()
    }

    fun exportPathsJson(): String = pathStore.exportJson()

    /** Current route to the target, or null when there is nothing to show. */
    var route by mutableStateOf<Route?>(null)
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

    /**
     * Snapshot of the pending photos, for the UI to render thumbnails from.
     *
     * A copy rather than the mutable list: Compose cannot observe mutations
     * to a plain list, so handing it out directly would show a stale gallery.
     */
    var pendingPhotoList by mutableStateOf<List<LandmarkPhoto>>(emptyList())
        private set

    /** Set when two captured landmarks are too close for GPS to separate. */
    var ambiguityWarning by mutableStateOf<String?>(null)

    /**
     * Hand-picked position for the landmark being surveyed.
     *
     * Survey mode used to demand a live fix better than 20m before it would
     * save anything. Indoors that fix never arrives, so the screen sat on
     * "waiting for GPS" forever and the app could not record a single place
     * unless the surveyor was physically standing outside the building. This
     * is the way out: pick the point on satellite imagery, or paste a Google
     * Maps link, and save.
     *
     * When set it overrides the averaged GPS samples entirely.
     */
    var manualPosition by mutableStateOf<LatLng?>(null)
        private set

    /** True while the full-screen map picker is showing. */
    var pickingLocation by mutableStateOf(false)

    /**
     * The fix the survey screen should trust.
     *
     * Null while the demo is running. The demo publishes a simulated fix with
     * a flattering 5m accuracy, and showing that on a screen whose entire job
     * is recording real coordinates would invite someone to save a landmark at
     * a position the phone never measured.
     */
    val surveyFix: GpsFix? get() = if (demoActive) null else fix

    fun chooseManualPosition(position: LatLng?) {
        manualPosition = position?.takeIf { it.isValid }
    }

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
        pathNetwork = pathStore.load()
        Log.i(
            TAG,
            "Loaded ${landmarks.size} landmarks and ${pathNetwork.edges.size} path edges"
        )
        refreshAmbiguityWarning()

        // An empty campus is a blank, useless screen. Until a real survey
        // exists, start in demo mode so there is always something to navigate
        // to -- otherwise the app looks broken to anyone who has not yet
        // walked the campus.
        if (landmarks.size < 2) {
            Log.i(TAG, "Too few surveyed landmarks; starting demo automatically")
            startDemo()
        }
    }

    fun startSensors(): Boolean {
        heading.onHeading = { deg ->
            phoneHeadingDegrees = deg.takeIf { it.isFinite() }
            // Demo mode normally owns the heading, since a live reading would
            // fight the simulated turns. But when the user explicitly asks to
            // test the real compass, it wins. Neither can replace glasses data.
            if (!externalTrackingEnabled && (!demoActive || useRealHeading)) {
                headingDegrees = phoneHeadingDegrees
                compassNeedsCalibration = heading.needsCalibration
                recomputeGuidance()
            }
            // Tilt is a property of how the phone is physically held, so it
            // stays live in phone/demo mode, but cannot stand in for head tilt.
            if (!externalTrackingEnabled) cameraTilt = heading.cameraTilt
        }
        heading.start()

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
        phoneHeadingDegrees = null
    }

    fun isGpsEnabled() = location.isGpsEnabled()

    fun selectTarget(landmark: Landmark?) {
        if (target?.id != landmark?.id) {
            lastRoutedFrom = null
            route = null
        }
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
        if (!externalTrackingEnabled) headingDegrees = null
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

    // ---- Presentation tour ----------------------------------------------

    var tour by mutableStateOf(TourState(0, 0, running = false))
        private set

    /**
     * Starts the scripted demo.
     *
     * Forces demo mode on first, so the tour never depends on a real GPS fix
     * or a network — the entire point is that it cannot fail in a meeting
     * room.
     */
    fun startTour() {
        if (!demoActive) startDemo()
        tour = TourState(0, 0, running = true)
        applyTourStep()
    }

    fun stopTour() {
        tour = tour.copy(running = false)
    }

    /** Called on a timer; drives the whole scripted sequence. */
    fun tickTour(deltaMs: Long) {
        if (!tour.running) return

        val previousStep = tour.stepIndex
        tour = advanceTour(tour, deltaMs)

        if (tour.stepIndex != previousStep) {
            applyTourStep()
            return
        }

        // Continuous actions run every tick rather than once per beat.
        val walk = tourWalkDistance(tour, deltaMs)
        if (walk > 0) demoWalk(walk)

        val turn = tourTurnDegrees(tour, deltaMs)
        if (turn != 0.0) demoTurn(turn)
    }

    /** Applies the one-shot action at the start of a beat. */
    private fun applyTourStep() {
        when (val action = tour.step?.action) {
            is TourAction.SelectTarget -> {
                landmarks.firstOrNull { it.name.contains(action.nameFragment) }
                    ?.let { selectTarget(it) }
            }

            is TourAction.JumpNear -> {
                // Place the walker a set distance short of the target, so the
                // beat lands on exactly the state being demonstrated.
                val target = target?.position ?: return
                val from = simulatedPosition ?: return
                val remaining = distanceMeters(from, target)
                val toTravel = (remaining - action.metresShort).coerceAtLeast(0.0)
                simulatedPosition = stepTowards(from, target, toTravel)
                publishSimulatedFix()
            }

            is TourAction.SetMode -> chooseTravelMode(action.mode)

            is TourAction.ShowMap -> mode = AppMode.MAP
            is TourAction.ShowCamera -> mode = AppMode.NAVIGATE

            else -> Unit
        }
    }

    /** Simulated turning, so the arrow can be checked without moving. */
    fun demoTurn(degrees: Double) {
        if (!demoActive) return
        useRealHeading = false
        simulatedHeading = (simulatedHeading + degrees + 360.0) % 360.0
        if (!externalTrackingEnabled) headingDegrees = simulatedHeading
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
        if (!useRealHeading && !externalTrackingEnabled) {
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

        val facing = headingDegrees ?: if (externalTrackingEnabled) return else simulatedHeading
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
        if (!externalTrackingEnabled) headingDegrees = simulatedHeading
        recomputeGuidance()
    }

    private fun recomputeGuidance() {
        val position = fix?.position
        val t = target
        // guidanceFor's legacy phone fallback treats an unknown heading as
        // straight ahead. A missing glasses sample must never invent that turn.
        guidance = if (position == null || t == null ||
            (externalTrackingEnabled && headingDegrees == null)
        ) {
            GuidanceMode.NoFix
        } else {
            guidanceFor(position, headingDegrees, t, landmarks)
        }

        // Recompute the route only when the user has moved meaningfully.
        // Re-running A* on every GPS tick and every compass degree would be
        // wasted work on a phone that already runs hot.
        val movedEnough = position != null && lastRoutedFrom.let { previous ->
            previous == null || distanceMeters(previous, position) > 5.0
        }
        if (movedEnough || route == null) {
            lastRoutedFrom = position
            route = if (position == null || t == null || !position.isValid) null
            else routeTo(pathNetwork, position, t.approachPoint(position), travelMode)
        }

        onUpdate?.invoke()
    }

    /** Position the current route was computed from. */
    private var lastRoutedFrom: LatLng? = null

    // ---- Survey ---------------------------------------------------------

    fun beginCapture() {
        surveySamples.clear()
        surveySampleCount = 0
        pendingPhotos.clear()
        pendingPhotoCount = 0
        pendingPhotoList = emptyList()
        manualPosition = null
        pickingLocation = false
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
        pendingPhotoList = pendingPhotos.toList()
    }

    /**
     * The position the landmark will be saved at.
     *
     * A hand-picked point wins outright: it was chosen deliberately on the
     * imagery, whereas the GPS samples may be from wherever the phone happened
     * to be when the picker was opened.
     */
    private fun surveyAveragePosition(): LatLng? =
        manualPosition ?: averageFixes(surveySamples) ?: fix?.position

    /**
     * Saves the landmark being surveyed.
     *
     * Uses the averaged position rather than a single fix: individual GPS
     * readings scatter by several metres, and that error is baked in
     * permanently once saved.
     */
    fun saveLandmark(name: String, category: LandmarkCategory, detail: String): Boolean {
        // Leave demo mode first, otherwise a real capture would be mixed into
        // the fake campus and then wiped when demo mode restores its backup.
        if (demoActive) stopDemo()

        val manual = manualPosition
        val position = surveyAveragePosition() ?: return false
        if (!position.isValid) return false

        // A hand-placed point has no measured accuracy, so it is quoted at the
        // realistic limit of dropping a pin on satellite imagery rather than
        // borrowing whatever the GPS happened to read somewhere else. Refusing
        // to save without a fix is what made the whole screen unusable, so
        // that is no longer a failure -- it is recorded honestly instead.
        val bestAccuracy = if (manual != null) {
            MANUAL_PLACEMENT_ACCURACY_M
        } else {
            surveySamples.minByOrNull { it.accuracyMeters }?.accuracyMeters
                ?: fix?.accuracyMeters
                ?: return false
        }

        val landmark = Landmark(
            id = "lm-${System.currentTimeMillis()}",
            name = name,
            category = category,
            position = position,
            detail = detail,
            photos = pendingPhotos.toList(),
            capturedAccuracyM = bestAccuracy,
            placedManually = manual != null
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
        val t = target ?: return null
        if (!p.isValid) return null
        return bearingDegrees(p, t.approachPoint(p))
    }

    /** Which door the user is currently being sent to, if any. */
    fun targetEntrance(): Entrance? {
        val p = fix?.position ?: return null
        return target?.nearestEntrance(p)
    }
}
