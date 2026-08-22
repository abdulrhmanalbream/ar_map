package com.sarab.vision.ar

import android.media.Image
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.sarab.vision.core.Landmark
import com.sarab.vision.core.SignMatch
import com.sarab.vision.core.matchSign
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "SarabSignReader"

/**
 * How often a frame is sent for recognition, in milliseconds.
 *
 * Text recognition is far too heavy to run per frame. A person pointing a
 * phone at a sign holds it there for seconds, so twice a second is plenty and
 * leaves the GL thread alone -- this device already runs warm with ARCore up.
 */
private const val THROTTLE_MS = 500L

/**
 * How many consecutive agreeing reads are needed before the answer is shown.
 *
 * A single frame can be blurred, half-lit, or catch a passing bus. Requiring
 * the same building twice in a row costs the user under a second and removes
 * nearly every one-frame misread.
 */
private const val CONFIRMATIONS_REQUIRED = 2

/**
 * Reads building name plaques through the camera.
 *
 * ## Why this exists
 *
 * GPS puts the user in the right area and no closer. On this campus that is
 * not enough: the colleges are built to one template, so being "at the right
 * spot" still leaves four identical sandstone facades and no way to tell which
 * door is which. The plaque above each entrance is the one thing that differs.
 *
 * ## What it deliberately does not do
 *
 * It never overrides the user's chosen destination, and it never silently
 * re-targets. It answers one question -- "which building am I looking at?" --
 * and hands that to the UI to show. Navigation stays under the user's control.
 */
class SignReader {

    private val recogniser = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** True while a frame is in flight, so frames are dropped rather than queued. */
    private val busy = AtomicBoolean(false)

    private var lastRunMs = 0L

    /** The last building read, and how many times running it has agreed. */
    private var pendingId: String? = null
    private var agreements = 0

    /**
     * Called with a settled result. Fired on the ML Kit callback thread.
     *
     * Only fires when the answer CHANGES, so the UI is not rewritten twice a
     * second with the same string.
     */
    var onResult: ((SignMatch) -> Unit)? = null

    private var lastPublished: String? = null

    /**
     * Offers an ARCore camera image for recognition.
     *
     * The image is closed by this method in every path -- ARCore hands out a
     * small fixed pool of images and failing to close one stalls the session
     * within a couple of seconds.
     *
     * @param rotationDegrees display rotation, from the ARCore display geometry
     * @param candidates buildings in contention; narrowing by GPS first makes
     *   the rare-word weighting sharper, so pass the nearby ones, not all
     */
    fun offer(image: Image, rotationDegrees: Int, candidates: List<Landmark>) {
        val now = System.currentTimeMillis()
        if (candidates.isEmpty() || now - lastRunMs < THROTTLE_MS || !busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastRunMs = now

        val input = try {
            InputImage.fromMediaImage(image, rotationDegrees)
        } catch (e: Exception) {
            Log.w(TAG, "Could not wrap camera image", e)
            image.close()
            busy.set(false)
            return
        }

        recogniser.process(input)
            .addOnSuccessListener { text ->
                // Line by line rather than the whole block: a plaque's two
                // language lines are separate, and so is any unrelated text
                // that happened to be in frame.
                val lines = text.textBlocks.flatMap { block ->
                    block.lines.map { it.text }
                }
                val match = matchSign(lines, candidates)

                // Logged because there is no other way to tell a reader that
                // saw nothing from one that read the sign perfectly and failed
                // to match it. Those need completely different fixes, and
                // guessing between them from an empty screen wastes hours.
                if (lines.isNotEmpty()) {
                    Log.i(
                        TAG,
                        "read ${lines.size} line(s): " +
                            lines.joinToString(" | ") { it.take(60) } +
                            " -> " + describe(match)
                    )
                } else {
                    Log.d(TAG, "no text in frame (${candidates.size} candidates)")
                }

                settle(match)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Recognition failed", e)
            }
            .addOnCompleteListener {
                // Closing here rather than in the success listener: ML Kit
                // only guarantees it is done with the image once the whole
                // task completes, and closing early corrupts the read.
                image.close()
                busy.set(false)
            }
    }

    /**
     * Requires the same answer twice before believing it.
     *
     * Also resets the streak on disagreement, so a reader that flickers
     * between two neighbouring buildings never reaches a confident state --
     * which is the correct outcome, since flickering means it cannot tell.
     */
    private fun settle(match: SignMatch) {
        val id = (match as? SignMatch.Found)?.landmark?.id

        if (id != null && id == pendingId) {
            agreements++
        } else {
            pendingId = id
            agreements = 1
        }

        val settled = when {
            id == null -> match
            agreements >= CONFIRMATIONS_REQUIRED -> match
            // Seen once but not yet confirmed: say nothing rather than
            // flashing a name that may be withdrawn a moment later.
            else -> return
        }

        val key = when (settled) {
            is SignMatch.Found -> "found:${settled.landmark.id}"
            is SignMatch.Unsure -> "unsure:${settled.reason}"
            SignMatch.NoText -> "none"
        }
        if (key == lastPublished) return
        lastPublished = key
        onResult?.invoke(settled)
    }

    private fun describe(match: SignMatch): String = when (match) {
        is SignMatch.Found ->
            "FOUND ${match.landmark.name} (score %.2f, margin %.2f)"
                .format(match.score, match.margin)
        is SignMatch.Unsure ->
            "unsure: ${match.reason}${match.bestGuess?.let { " (guess: ${it.name})" } ?: ""}"
        SignMatch.NoText -> "no usable text"
    }

    /** Forgets the current streak, e.g. when the camera is put away. */
    fun reset() {
        pendingId = null
        agreements = 0
        lastPublished = null
    }

    fun close() {
        runCatching { recogniser.close() }
    }
}
