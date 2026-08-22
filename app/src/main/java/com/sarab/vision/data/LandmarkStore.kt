package com.sarab.vision.data

import android.content.Context
import android.util.Log
import com.sarab.vision.core.LatLng
import com.sarab.vision.core.Landmark
import com.sarab.vision.core.LandmarkCategory
import com.sarab.vision.core.LandmarkPhoto
import com.sarab.vision.core.Viewpoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "SarabLandmarks"
private const val FILE_NAME = "landmarks.json"
private const val PHOTO_DIR = "landmark_photos"
private const val SEED_STAMP = "seed_version"

/**
 * Bump when assets/landmarks.json gains entries that existing installs need.
 *
 * Seeding only on first run is not enough. Any phone that has already opened
 * the app has a landmarks.json, so new bundled campus data would never reach
 * it -- the update would install and appear to change nothing. This lets new
 * seed entries merge into an existing survey without touching what the user
 * recorded themselves.
 */
private const val CURRENT_SEED_VERSION = 2

/**
 * Offline storage for captured landmarks.
 *
 * Plain JSON in the app's private files directory -- no database, no network,
 * no dependency. The dataset is tens of landmarks, so a document store would
 * be over-engineering, and JSON has the practical advantage that the user can
 * export it, edit it by hand, and commit it into the app as seed data.
 *
 * Bundled seed data (assets/landmarks.json) is loaded on first run, so a
 * finished survey can ship with the app while still being editable on device.
 */
class LandmarkStore(private val context: Context) {

    private val file: File
        get() = File(context.filesDir, FILE_NAME)

    val photoDir: File
        get() = File(context.filesDir, PHOTO_DIR).apply { if (!exists()) mkdirs() }

    private val seedStampFile: File
        get() = File(context.filesDir, SEED_STAMP)

    /** Loads all landmarks, merging in any bundled seed data not yet applied. */
    fun load(): List<Landmark> {
        if (!file.exists()) {
            val seeded = loadSeedFromAssets()
            if (seeded.isNotEmpty()) {
                Log.i(TAG, "Seeded ${seeded.size} landmarks from assets")
                save(seeded)
            }
            markSeedApplied()
            return seeded
        }

        val existing = try {
            parse(file.readText())
        } catch (e: Exception) {
            // Never let a corrupt file brick the app -- a survey is
            // replaceable, a crash loop is not.
            Log.e(TAG, "Could not read landmarks; starting empty", e)
            emptyList()
        }

        if (appliedSeedVersion() >= CURRENT_SEED_VERSION) return existing

        // Merge by id, and let anything already on the device win. A landmark
        // the user surveyed standing at the door is worth more than a
        // coordinate shipped in the APK, so an update must never overwrite it.
        val known = existing.mapTo(HashSet()) { it.id }
        val additions = loadSeedFromAssets().filterNot { it.id in known }
        markSeedApplied()

        if (additions.isEmpty()) return existing

        val merged = existing + additions
        Log.i(TAG, "Merged ${additions.size} new seed landmarks (v$CURRENT_SEED_VERSION)")
        save(merged)
        return merged
    }

    private fun appliedSeedVersion(): Int =
        runCatching { seedStampFile.readText().trim().toInt() }.getOrDefault(0)

    private fun markSeedApplied() {
        runCatching { seedStampFile.writeText(CURRENT_SEED_VERSION.toString()) }
    }

    fun save(landmarks: List<Landmark>) {
        try {
            file.writeText(serialise(landmarks))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save landmarks", e)
        }
    }

    /** Adds one landmark and persists immediately. */
    fun add(landmark: Landmark): List<Landmark> {
        val updated = load().filterNot { it.id == landmark.id } + landmark
        save(updated)
        return updated
    }

    fun delete(id: String): List<Landmark> {
        val all = load()
        // Remove orphaned photos too, so a long survey does not slowly fill
        // the device with images for landmarks that no longer exist.
        all.firstOrNull { it.id == id }?.photos?.forEach { photo ->
            runCatching { File(photoDir, photo.file).delete() }
        }
        val remaining = all.filterNot { it.id == id }
        save(remaining)
        return remaining
    }

    /** Serialised JSON, for sharing the survey off the device. */
    fun exportJson(): String = serialise(load())

    private fun loadSeedFromAssets(): List<Landmark> = try {
        context.assets.open("landmarks.json").use { stream ->
            parse(stream.bufferedReader().readText())
        }
    } catch (e: Exception) {
        // No seed file bundled: entirely normal before the first survey.
        emptyList()
    }

    private fun parse(json: String): List<Landmark> {
        val out = mutableListOf<Landmark>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val lat = o.optDouble("lat", Double.NaN)
            val lon = o.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue

            val position = LatLng(lat, lon)
            if (!position.isValid) continue

            val amenities = mutableListOf<String>()
            o.optJSONArray("amenities")?.let { a ->
                for (j in 0 until a.length()) amenities.add(a.getString(j))
            }

            val signs = mutableListOf<String>()
            o.optJSONArray("signs")?.let { a ->
                for (j in 0 until a.length()) {
                    a.optString(j).takeIf { it.isNotBlank() }?.let(signs::add)
                }
            }

            val photos = mutableListOf<LandmarkPhoto>()
            o.optJSONArray("photos")?.let { a ->
                for (j in 0 until a.length()) {
                    val p = a.optJSONObject(j) ?: continue
                    val fileName = p.optString("file").takeIf { it.isNotBlank() } ?: continue
                    val fromLat = p.optDouble("fromLat", Double.NaN)
                    val fromLon = p.optDouble("fromLon", Double.NaN)
                    photos.add(
                        LandmarkPhoto(
                            file = fileName,
                            viewpoint = runCatching {
                                Viewpoint.valueOf(p.optString("viewpoint"))
                            }.getOrDefault(Viewpoint.OTHER),
                            takenFrom = if (fromLat.isNaN() || fromLon.isNaN()) null
                            else LatLng(fromLat, fromLon),
                            bearingDegrees = p.optDouble("bearing", Double.NaN)
                                .takeIf { !it.isNaN() },
                            distanceMeters = p.optDouble("distance", Double.NaN)
                                .takeIf { !it.isNaN() }
                        )
                    )
                }
            }
            // Backwards compatibility with the single-photo format.
            o.optString("photo").takeIf { it.isNotBlank() }?.let {
                if (photos.none { p -> p.file == it }) {
                    photos.add(0, LandmarkPhoto(it, Viewpoint.ENTRANCE))
                }
            }

            out.add(
                Landmark(
                    id = o.optString("id").ifBlank { "lm-${System.nanoTime()}-$i" },
                    name = o.optString("name", "Unnamed"),
                    category = LandmarkCategory.fromName(o.optString("category")),
                    position = position,
                    detail = o.optString("detail", ""),
                    hours = o.optString("hours", ""),
                    amenities = amenities,
                    photos = photos,
                    capturedAccuracyM = o.optDouble("accuracy", 0.0).toFloat(),
                    placedManually = o.optBoolean("manual", false),
                    signTexts = signs
                )
            )
        }
        return out
    }

    private fun serialise(landmarks: List<Landmark>): String {
        val arr = JSONArray()
        for (l in landmarks) {
            arr.put(
                JSONObject().apply {
                    put("id", l.id)
                    put("name", l.name)
                    put("category", l.category.name)
                    put("lat", l.position.latitude)
                    put("lon", l.position.longitude)
                    put("detail", l.detail)
                    put("hours", l.hours)
                    put("accuracy", l.capturedAccuracyM.toDouble())
                    put("manual", l.placedManually)
                    put("signs", JSONArray(l.signTexts))
                    put("amenities", JSONArray(l.amenities))
                    put(
                        "photos",
                        JSONArray().apply {
                            for (p in l.photos) {
                                put(
                                    JSONObject().apply {
                                        put("file", p.file)
                                        put("viewpoint", p.viewpoint.name)
                                        p.takenFrom?.let {
                                            put("fromLat", it.latitude)
                                            put("fromLon", it.longitude)
                                        }
                                        p.bearingDegrees?.let { put("bearing", it) }
                                        p.distanceMeters?.let { put("distance", it) }
                                    }
                                )
                            }
                        }
                    )
                }
            )
        }
        return arr.toString(2)
    }
}
