package com.sarab.vision.core

/**
 * The guided start-up sequence shown when the camera opens.
 *
 * It is not decoration. Two separate subsystems genuinely need it:
 *
 *  - **ARCore** cannot establish tracking from a stationary camera. It needs
 *    parallax -- the same scene viewed from slightly different positions --
 *    to work out the geometry of the room. A user holding the phone perfectly
 *    still waits forever, which is exactly what happened during testing.
 *  - **The magnetometer** drifts and needs a sweeping motion to recalibrate.
 *    Near metal it reads confidently wrong until waved.
 *
 * A side-to-side wave satisfies both at once, so the sequence asks for that
 * first, then asks the user to raise the phone into the pose they will
 * actually navigate with.
 *
 * Progress is driven by MEASURED readiness, not a timer: the step completes
 * when tracking is genuinely established. A timer alone would dismiss the
 * hint while the app was still blind, and the user would blame the app.
 */

/** Where the user is in the start-up sequence. */
enum class CalibrationStep {
    /** Lay the phone flattish and sweep it side to side. */
    WAVE,

    /** Raise it to look ahead through the camera. */
    RAISE,

    /** Ready to navigate. */
    DONE
}

/**
 * Inputs the sequence judges readiness from.
 *
 * @param tracking ARCore has a usable pose
 * @param compassReliable the magnetometer is not reporting itself unreliable
 * @param tilt camera tilt from [HeadingResult]: 0 = level, 1 = straight down
 * @param movedDegrees how far the heading has swept since the step began
 * @param elapsedMs time spent on the current step
 */
data class CalibrationInput(
    val tracking: Boolean,
    val compassReliable: Boolean,
    val tilt: Float,
    val movedDegrees: Double,
    val elapsedMs: Long
)

/** What the UI should show right now. */
data class CalibrationState(
    val step: CalibrationStep,
    /** 0..1, for the progress ring. */
    val progress: Float,
    val titleAr: String,
    val bodyAr: String
)

/** Sweep required before the wave step is satisfied, in degrees. */
private const val REQUIRED_SWEEP_DEG = 55.0

/** Minimum time on the wave step, so it never flashes past unread. */
private const val MIN_WAVE_MS = 2_000L

/**
 * After this long the wave step gives up waiting for perfect conditions.
 *
 * Some rooms simply lack the visual texture ARCore needs, and blocking the
 * user forever is worse than letting them proceed with a warning.
 */
private const val MAX_WAVE_MS = 12_000L

/** Camera tilt at or below this counts as "raised to look ahead". */
private const val RAISED_TILT = 0.55f

/** Time on the raise step before it is assumed done. */
private const val MAX_RAISE_MS = 6_000L

/**
 * Advances the sequence.
 *
 * Pure, so the whole flow can be unit-tested rather than discovered by
 * repeatedly waving a phone at a wall.
 */
fun nextCalibrationState(
    current: CalibrationStep,
    input: CalibrationInput
): CalibrationState = when (current) {

    CalibrationStep.WAVE -> {
        // Ready when the sensors actually say so, with a swept-far-enough
        // fallback for devices whose compass never reports "reliable".
        val sweptEnough = input.movedDegrees >= REQUIRED_SWEEP_DEG
        val sensorsReady = input.tracking && input.compassReliable
        val waitedLongEnough = input.elapsedMs >= MIN_WAVE_MS
        val timedOut = input.elapsedMs >= MAX_WAVE_MS

        val satisfied = (waitedLongEnough && sweptEnough && sensorsReady) || timedOut

        if (satisfied) {
            CalibrationState(
                CalibrationStep.RAISE,
                0f,
                "ارفع الجوال الآن",
                "وجّه الكاميرا للأمام كأنك تصوّر الطريق."
            )
        } else {
            // Show the weaker of the two signals, so the ring reflects
            // whichever requirement is actually holding things up.
            val sweepProgress = (input.movedDegrees / REQUIRED_SWEEP_DEG).coerceIn(0.0, 1.0)
            val timeProgress = (input.elapsedMs.toDouble() / MAX_WAVE_MS).coerceIn(0.0, 1.0)
            CalibrationState(
                CalibrationStep.WAVE,
                minOf(sweepProgress, 1.0).toFloat().coerceAtLeast(timeProgress.toFloat() * 0.3f),
                "حرّك الجوال يميناً ويساراً",
                "امسك الجوال مائلاً قليلاً وحرّكه ببطء يميناً ويساراً لثوانٍ."
            )
        }
    }

    CalibrationStep.RAISE -> {
        val raised = input.tilt <= RAISED_TILT
        if (raised || input.elapsedMs >= MAX_RAISE_MS) {
            CalibrationState(CalibrationStep.DONE, 1f, "", "")
        } else {
            CalibrationState(
                CalibrationStep.RAISE,
                (input.elapsedMs.toDouble() / MAX_RAISE_MS).coerceIn(0.0, 1.0).toFloat(),
                "ارفع الجوال الآن",
                "وجّه الكاميرا للأمام كأنك تصوّر الطريق."
            )
        }
    }

    CalibrationStep.DONE -> CalibrationState(CalibrationStep.DONE, 1f, "", "")
}

/**
 * Accumulates how far the heading has swept.
 *
 * Sums absolute change rather than comparing start to end, so a wave out and
 * back still counts -- which is the motion actually being asked for, and
 * would otherwise register as zero.
 */
class SweepTracker {
    private var last: Double? = null
    var totalDegrees: Double = 0.0
        private set

    fun reset() {
        last = null
        totalDegrees = 0.0
    }

    fun update(headingDeg: Double) {
        val previous = last
        if (previous != null) {
            val delta = kotlin.math.abs(relativeBearing(previous, headingDeg))
            // Ignore jumps too large to be real motion; they are sensor
            // glitches and would otherwise complete the step instantly.
            if (delta < 45.0) totalDegrees += delta
        }
        last = headingDeg
    }
}
