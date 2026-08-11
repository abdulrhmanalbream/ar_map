package com.sarab.vision.ar

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Session
import java.io.IOException

private const val TAG = "SarabAugImage"

/** Asset filenames accepted as the reference image, in priority order. */
private val CANDIDATE_ASSETS = listOf(
    "origin_marker.jpg",
    "origin_marker.png",
    "map_board.jpg",
    "map_board.png",
    "qr_code.jpg",
    "qr_code.png"
)

/**
 * The name the reference image is registered under in the database. The
 * renderer matches on this to identify the origin image.
 */
const val ORIGIN_IMAGE_NAME = "sarab_origin"

/**
 * Physical width of the printed reference image, in metres.
 *
 * Telling ARCore the real-world size dramatically improves both detection
 * speed and pose accuracy. If the user prints at a different size they must
 * update this -- V2_SETUP.md says so explicitly.
 */
const val ORIGIN_IMAGE_WIDTH_METERS = 0.30f

/** Outcome of trying to build the augmented image database. */
sealed interface ImageDbResult {
    /** Database built and attached to the session config. */
    data class Ready(val assetName: String) : ImageDbResult

    /** No reference image shipped; the app falls back to plane origin. */
    data object NoImageProvided : ImageDbResult

    /** An image existed but ARCore rejected it (usually too low-contrast). */
    data class Rejected(val assetName: String, val reason: String) : ImageDbResult
}

/**
 * Builds an [AugmentedImageDatabase] from whichever candidate asset exists.
 *
 * Deliberately tolerant: a missing reference image is a normal state (the
 * user may not have printed one yet), not an error. In that case the caller
 * falls back to placing the route on a detected floor plane, so the app is
 * still usable out of the box.
 *
 * Building the database at runtime rather than shipping a prebuilt `.imgdb`
 * keeps setup to "drop a JPG in assets/" with no extra tooling -- the arcore
 * `db-tool` would otherwise be a required manual step.
 */
fun buildOriginImageDatabase(context: Context, session: Session): Pair<AugmentedImageDatabase?, ImageDbResult> {
    val assetName = CANDIDATE_ASSETS.firstOrNull { asset ->
        try {
            context.assets.open(asset).close()
            true
        } catch (e: IOException) {
            false
        }
    } ?: run {
        Log.i(TAG, "No reference image asset found; using plane-based origin.")
        return null to ImageDbResult.NoImageProvided
    }

    return try {
        val bitmap = context.assets.open(assetName).use { BitmapFactory.decodeStream(it) }
            ?: return null to ImageDbResult.Rejected(assetName, "could not be decoded")

        val db = AugmentedImageDatabase(session)
        // Supplying the physical width lets ARCore resolve scale immediately
        // instead of estimating it from motion.
        db.addImage(ORIGIN_IMAGE_NAME, bitmap, ORIGIN_IMAGE_WIDTH_METERS)
        bitmap.recycle()

        Log.i(TAG, "Augmented image database built from $assetName")
        db to ImageDbResult.Ready(assetName)
    } catch (e: Exception) {
        // addImage throws if the image lacks enough trackable features.
        Log.e(TAG, "Rejected reference image $assetName", e)
        null to ImageDbResult.Rejected(
            assetName,
            e.message ?: "not enough visual detail to track"
        )
    }
}
