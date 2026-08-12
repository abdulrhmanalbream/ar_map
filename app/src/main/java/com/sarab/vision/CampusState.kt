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
import com.sarab.vision.core.averageFixes
import com.sarab.vision.core.bearingDegrees
import com.sarab.vision.core.distanceMeters
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
            headingDegrees = deg
            recomputeGuidance()
        }

        location.onFix = { f ->
            fix = f
            // Feed declination to the compass so its heading is true-north,
            // matching the GPS bearings we navigate by.
            heading.magneticDeclination = declinationFor(f.position)

            if (mode == AppMode.SURVEY) {
                surveySamples.add(f)
                // Keep only recent samples: older ones may be from before the
                // surveyor reached the entrance.
                if (surveySamples.size > 12) surveySamples.removeAt(0)
                surveySampleCount = surveySamples.size
            }
            recomputeGuidance()
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

    private fun recomputeGuidance() {
        val position = fix?.position
        val t = target
        guidance = if (position == null || t == null) {
            GuidanceMode.NoFix
        } else {
            guidanceFor(position, headingDegrees, t, landmarks)
        }
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
