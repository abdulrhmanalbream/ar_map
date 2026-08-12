package com.sarab.vision.core

/**
 * Decides how to guide the user, based on distance and on whether nearby
 * landmarks are far enough apart for GPS to tell them apart.
 *
 * The campus has both cases:
 *  - buildings hundreds of metres away, where the user cannot even see the
 *    target and needs a compass bearing
 *  - buildings close together, where GPS error (5-10m) is comparable to the
 *    gap between them and a naive "nearest wins" would confidently send
 *    someone to the wrong door
 *
 * Treating those the same is what makes campus AR apps feel unreliable, so
 * the mode is chosen explicitly and the uncertainty is shown to the user
 * rather than hidden.
 */

/** How the UI should currently guide the user. */
sealed interface GuidanceMode {

    /** Far away: show a compass arrow, bearing and distance. */
    data class Compass(
        val distanceMeters: Double,
        val bearingDegrees: Double,
        val relativeDegrees: Double
    ) : GuidanceMode

    /** Close enough for AR to be meaningful; ARCore takes over. */
    data class ArApproach(val distanceMeters: Double) : GuidanceMode

    /**
     * Within arrival range, but another landmark is close enough that GPS
     * cannot reliably distinguish them. The UI must ask the user to confirm
     * visually rather than assert which building this is.
     */
    data class Ambiguous(
        val distanceMeters: Double,
        val candidates: List<Landmark>
    ) : GuidanceMode

    /** Arrived, and unambiguously so. */
    data class Arrived(val distanceMeters: Double) : GuidanceMode

    /** No usable GPS fix yet. */
    data object NoFix : GuidanceMode
}

/**
 * Minimum separation for GPS to distinguish two landmarks with confidence.
 *
 * Derived from real GPS behaviour rather than picked arbitrarily: a typical
 * open-sky fix is accurate to 5-10m, and two independent fixes can be wrong
 * in opposite directions. Below ~25m apart, "which building am I at" is not
 * a question GPS alone can answer.
 */
const val GPS_DISTINGUISHABLE_M = 25.0

/**
 * Chooses the guidance mode for [target] given the user's position.
 *
 * @param others every other landmark, used to detect ambiguity
 */
fun guidanceFor(
    userPosition: LatLng,
    userHeadingDeg: Double?,
    target: Landmark,
    others: List<Landmark>
): GuidanceMode {
    if (!userPosition.isValid) return GuidanceMode.NoFix

    val distance = distanceMeters(userPosition, target.position)

    if (distance > AR_HANDOFF_DISTANCE_M) {
        val bearing = bearingDegrees(userPosition, target.position)
        val relative = userHeadingDeg?.let { relativeBearing(it, bearing) } ?: 0.0
        return GuidanceMode.Compass(distance, bearing, relative)
    }

    if (distance > ARRIVAL_DISTANCE_M) {
        return GuidanceMode.ArApproach(distance)
    }

    // Close enough to have arrived -- but is it unambiguous? Find other
    // landmarks that are also within reach and too close to the target for
    // GPS to separate them.
    val confusable = others
        .filter { it.id != target.id }
        .filter { other ->
            val gap = distanceMeters(target.position, other.position)
            val userToOther = distanceMeters(userPosition, other.position)
            // Ambiguous when the two are close together AND the user is
            // genuinely near both, not merely near one of them.
            gap < GPS_DISTINGUISHABLE_M && userToOther <= ARRIVAL_DISTANCE_M * 2
        }

    return if (confusable.isEmpty()) {
        GuidanceMode.Arrived(distance)
    } else {
        GuidanceMode.Ambiguous(distance, listOf(target) + confusable)
    }
}

/**
 * Landmarks that sit too close together to be told apart by GPS.
 *
 * Surfaced during a survey so the surveyor learns immediately that two
 * entrances need photos distinctive enough to tell them apart, rather than
 * discovering the problem when users get lost.
 */
fun findAmbiguousPairs(landmarks: List<Landmark>): List<Pair<Landmark, Landmark>> {
    val pairs = mutableListOf<Pair<Landmark, Landmark>>()
    for (i in landmarks.indices) {
        for (j in i + 1 until landmarks.size) {
            val d = distanceMeters(landmarks[i].position, landmarks[j].position)
            if (d < GPS_DISTINGUISHABLE_M) {
                pairs.add(landmarks[i] to landmarks[j])
            }
        }
    }
    return pairs
}

/**
 * A short instruction for the current mode, in Arabic.
 *
 * Kept here rather than in the UI so it is unit-testable and consistent
 * across the AR view, the map and any future voice output.
 */
fun instructionAr(mode: GuidanceMode, targetName: String): String = when (mode) {
    is GuidanceMode.NoFix -> "جاري تحديد موقعك…"

    is GuidanceMode.Compass -> {
        val dist = formatDistanceAr(mode.distanceMeters)
        when {
            kotlin.math.abs(mode.relativeDegrees) <= 15 -> "امشِ للأمام · $dist"
            mode.relativeDegrees > 125 || mode.relativeDegrees < -125 -> "استدر للخلف · $dist"
            mode.relativeDegrees > 55 -> "اتجه يميناً · $dist"
            mode.relativeDegrees < -55 -> "اتجه يساراً · $dist"
            mode.relativeDegrees > 0 -> "مِل يميناً قليلاً · $dist"
            else -> "مِل يساراً قليلاً · $dist"
        }
    }

    is GuidanceMode.ArApproach ->
        "$targetName أمامك · ${formatDistanceAr(mode.distanceMeters)}"

    is GuidanceMode.Ambiguous ->
        "أنت قريب من عدة مبانٍ — تأكد من الصورة"

    is GuidanceMode.Arrived -> "وصلت إلى $targetName"
}

/** Arabic distance formatting: "١٢ م" style but with ASCII digits. */
fun formatDistanceAr(meters: Double): String = when {
    meters < 1000 -> "${meters.toInt()} م"
    else -> String.format("%.1f كم", meters / 1000.0)
}
