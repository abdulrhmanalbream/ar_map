package com.sarab.vision.core

import kotlin.math.roundToInt

/**
 * When to speak, and what to say -- the policy behind voice guidance.
 *
 * The hard part of spoken navigation is not producing speech, it is shutting
 * up. Distance updates arrive every GPS tick and heading jitters around
 * instruction thresholds; speaking every change would produce a continuous
 * babble that gets the volume turned to zero on day one. So:
 *
 *  - distances are spoken in coarse buckets, so the phrase only changes at
 *    meaningful progress, not every metre
 *  - a phrase is only spoken when it differs from the last one spoken
 *  - non-urgent phrases keep a minimum gap; arrival and ambiguity cut in
 *    immediately because they are the two moments worth interrupting for
 *
 * Pure logic, mirrored on [instructionAr]'s thresholds so ears and eyes
 * never disagree about which way to go.
 */

/** Minimum silence between routine announcements. */
const val VOICE_MIN_GAP_MS = 6_000L

/**
 * Rounds a distance to the step a walker actually cares about at that range.
 *
 * Compass guidance only runs beyond the AR handoff (30m), so the finest
 * bucket is 25m: below that the ground path and the arrival flow carry the
 * message, and voice repeating single metres would be noise.
 */
fun voiceDistanceBucket(meters: Double): Int {
    val step = when {
        meters >= 300 -> 100
        meters >= 100 -> 50
        else -> 25
    }
    return ((meters / step).roundToInt() * step).coerceAtLeast(step)
}

/** The spoken phrase for the current guidance, or null for silence. */
fun voiceCueAr(mode: GuidanceMode, targetName: String): String? = when (mode) {
    // Nagging "still no GPS" out loud helps nobody; the HUD already says it.
    is GuidanceMode.NoFix -> null

    is GuidanceMode.Compass -> {
        val direction = when {
            kotlin.math.abs(mode.relativeDegrees) <= 15 -> "امشِ للأمام"
            mode.relativeDegrees > 125 || mode.relativeDegrees < -125 -> "استدر للخلف"
            mode.relativeDegrees > 55 -> "اتجه يميناً"
            mode.relativeDegrees < -55 -> "اتجه يساراً"
            mode.relativeDegrees > 0 -> "مِل يميناً قليلاً"
            else -> "مِل يساراً قليلاً"
        }
        "$direction، على بعد ${voiceDistanceBucket(mode.distanceMeters)} متر"
    }

    is GuidanceMode.ArApproach -> "$targetName أمامك، اتبع المسار على الأرض"

    is GuidanceMode.Ambiguous -> "أنت قريب من عدة مبانٍ، تأكد بالنظر"

    is GuidanceMode.Arrived -> "وصلت إلى $targetName"
}

/** What has been said so far, threaded through [nextVoiceCue]. */
data class VoiceState(
    val lastText: String? = null,
    val lastAtMs: Long = 0L
)

/**
 * Decides whether [cue] should be spoken now.
 *
 * Returns the updated state and the text to speak, or null for silence.
 * [urgent] bypasses the minimum gap -- arrival and ambiguity must not wait
 * six seconds while the user walks past the door.
 */
fun nextVoiceCue(
    state: VoiceState,
    cue: String?,
    urgent: Boolean,
    nowMs: Long
): Pair<VoiceState, String?> {
    if (cue == null) return state to null
    if (cue == state.lastText) return state to null
    if (!urgent && nowMs - state.lastAtMs < VOICE_MIN_GAP_MS) return state to null
    return VoiceState(cue, nowMs) to cue
}
