package com.sarab.vision.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Turn-by-turn guidance over a routed polyline: which corners are real turns,
 * where the user is along the route, what comes next, and when they have left
 * it.
 *
 * Pure Kotlin so the thresholds -- where "keep going" becomes "turn right" --
 * are unit tested rather than discovered on a walk.
 */

/** Below this net heading change a corner is just the path bending. */
const val TURN_STRAIGHT_MAX_DEG = 20.0

/** Up to here it is a "slight" turn: a fork, a gentle bend at a junction. */
const val TURN_SLIGHT_MAX_DEG = 45.0

/** Up to here an ordinary turn; beyond it "sharp", doubling back. */
const val TURN_NORMAL_MAX_DEG = 135.0

/** Beyond this the route really does reverse: a U-turn at a dead end. */
const val TURN_SHARP_MAX_DEG = 165.0

/**
 * Corners closer together than this are one manoeuvre. OSM traces jog by a
 * few metres where a footway meets a kerb; summing the jog's turns makes it
 * cancel out instead of producing "right, then immediately left".
 */
const val TURN_CLUSTER_GAP_M = 8.0

/** Vertex turns smaller than this are trace noise and never start a cluster. */
private const val TURN_NOISE_DEG = 3.0

/** A turn is named after a landmark only when it is visibly at that landmark. */
const val TURN_LANDMARK_RADIUS_M = 25.0

/**
 * Further than this from the route counts as off it. Campus GPS error is
 * 5-15 m, so a tighter limit would reroute people who are exactly on the path.
 */
const val OFF_ROUTE_DISTANCE_M = 20.0

/** Consecutive off-route fixes before rerouting: one bad fix is just GPS. */
const val OFF_ROUTE_FIXES = 3

/** "In 40 m turn right" is spoken from this distance... */
const val TURN_PREPARE_DISTANCE_M = 45.0

/** ...and "turn right" itself here, at the corner. */
const val TURN_NOW_DISTANCE_M = 12.0

/** The compass arrow aims this far ahead along the route, not at the door. */
const val ROUTE_LOOKAHEAD_M = 15.0

/**
 * The glasses show a turn only from this close. Further out the chevrons on
 * the ground already say "keep going", and a far instruction is clutter.
 */
const val GLASSES_TURN_VISIBLE_M = 120.0

/** Progress never jumps backwards further than this along a looping route. */
private const val PROGRESS_BACKTRACK_TOLERANCE_M = 10.0

enum class ManeuverType {
    DEPART,
    SLIGHT_RIGHT, RIGHT, SHARP_RIGHT,
    SLIGHT_LEFT, LEFT, SHARP_LEFT,
    U_TURN,
    ARRIVE;

    val isTurn: Boolean get() = this != DEPART && this != ARRIVE
}

/**
 * One instruction along a route.
 *
 * @property alongMeters distance from the route start to this point.
 * @property legMeters distance walked from the previous manoeuvre to this one.
 * @property turnDegrees net heading change; positive is to the right.
 */
data class Maneuver(
    val type: ManeuverType,
    val position: LatLng,
    val alongMeters: Double,
    val legMeters: Double,
    val turnDegrees: Double = 0.0,
    val landmarkName: String? = null,
)

/** Classifies a net heading change; null means "carry straight on". */
fun classifyTurn(degrees: Double): ManeuverType? {
    val magnitude = abs(degrees)
    return when {
        magnitude < TURN_STRAIGHT_MAX_DEG -> null
        magnitude >= TURN_SHARP_MAX_DEG -> ManeuverType.U_TURN
        degrees > 0 -> when {
            magnitude < TURN_SLIGHT_MAX_DEG -> ManeuverType.SLIGHT_RIGHT
            magnitude <= TURN_NORMAL_MAX_DEG -> ManeuverType.RIGHT
            else -> ManeuverType.SHARP_RIGHT
        }
        else -> when {
            magnitude < TURN_SLIGHT_MAX_DEG -> ManeuverType.SLIGHT_LEFT
            magnitude <= TURN_NORMAL_MAX_DEG -> ManeuverType.LEFT
            else -> ManeuverType.SHARP_LEFT
        }
    }
}

/** Removes repeated points, which would give a zero-length segment no bearing. */
private fun distinctPoints(points: List<LatLng>): List<LatLng> {
    val out = ArrayList<LatLng>(points.size)
    for (p in points) if (out.isEmpty() || distanceMeters(out.last(), p) > 0.3) out.add(p)
    return out
}

private fun cumulative(points: List<LatLng>): DoubleArray {
    val along = DoubleArray(points.size)
    for (i in 1 until points.size) along[i] = along[i - 1] + distanceMeters(points[i - 1], points[i])
    return along
}

/**
 * Turns a routed polyline into DEPART, the turns, and ARRIVE.
 *
 * Each corner's heading change is measured, corners within
 * [TURN_CLUSTER_GAP_M] of each other are summed into one manoeuvre (so a jog
 * cancels and a bend drawn as three short segments reads as one turn), and
 * only net changes of [TURN_STRAIGHT_MAX_DEG] or more become instructions.
 */
fun routeManeuvers(points: List<LatLng>, landmarks: List<Landmark> = emptyList()): List<Maneuver> {
    val pts = distinctPoints(points)
    if (pts.size < 2) return emptyList()
    val along = cumulative(pts)

    val turns = DoubleArray(pts.size)
    for (i in 1 until pts.size - 1) {
        turns[i] = relativeBearing(bearingDegrees(pts[i - 1], pts[i]), bearingDegrees(pts[i], pts[i + 1]))
    }

    val maneuvers = mutableListOf(Maneuver(ManeuverType.DEPART, pts.first(), 0.0, 0.0))
    var i = 1
    while (i < pts.size - 1) {
        if (abs(turns[i]) < TURN_NOISE_DEG) { i++; continue }
        var sum = turns[i]
        var strongest = i
        var lastAlong = along[i]
        var j = i + 1
        while (j < pts.size - 1 && along[j] - lastAlong < TURN_CLUSTER_GAP_M) {
            if (abs(turns[j]) >= TURN_NOISE_DEG) {
                sum += turns[j]
                if (abs(turns[j]) > abs(turns[strongest])) strongest = j
                lastAlong = along[j]
            }
            j++
        }
        val type = classifyTurn(sum)
        if (type != null) {
            val position = pts[strongest]
            maneuvers.add(
                Maneuver(
                    type = type,
                    position = position,
                    alongMeters = along[strongest],
                    legMeters = along[strongest] - maneuvers.last().alongMeters,
                    turnDegrees = sum,
                    landmarkName = landmarkNear(position, landmarks),
                )
            )
        }
        i = j
    }
    val total = along.last()
    maneuvers.add(Maneuver(ManeuverType.ARRIVE, pts.last(), total, total - maneuvers.last().alongMeters))
    return maneuvers
}

private fun landmarkNear(position: LatLng, landmarks: List<Landmark>): String? =
    landmarks.map { it to distanceMeters(position, it.approachPoint(position)) }
        .filter { it.second <= TURN_LANDMARK_RADIUS_M }
        .minByOrNull { it.second }
        ?.first?.name

/** Where the user is along a route. */
data class RouteProgress(
    val alongMeters: Double,
    /** Distance from the user to the route line. */
    val offRouteMeters: Double,
    val remainingMeters: Double,
)

/**
 * Projects [position] onto the route.
 *
 * With [previousAlongMeters], candidates behind the user's last progress are
 * penalised, so a route that doubles back near itself does not make progress
 * jump to the later leg (or back to the start) on one noisy fix.
 */
fun routeProgress(points: List<LatLng>, position: LatLng, previousAlongMeters: Double? = null): RouteProgress? {
    val pts = distinctPoints(points)
    if (pts.isEmpty() || !position.isValid) return null
    val along = cumulative(pts)
    val total = along.last()
    if (pts.size == 1) {
        val d = distanceMeters(position, pts[0])
        return RouteProgress(0.0, d, 0.0)
    }
    var bestCost = Double.MAX_VALUE
    var bestAlong = 0.0
    var bestDistance = 0.0
    for (i in 0 until pts.size - 1) {
        val (ax, ay) = toLocalMeters(position, pts[i])
        val (bx, by) = toLocalMeters(position, pts[i + 1])
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared <= 1e-9) 0.0 else ((-ax * dx - ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
        val distance = hypot(ax + t * dx, ay + t * dy)
        val candidateAlong = along[i] + t * (along[i + 1] - along[i])
        val backtrack = previousAlongMeters?.let { (it - PROGRESS_BACKTRACK_TOLERANCE_M - candidateAlong).coerceAtLeast(0.0) } ?: 0.0
        val cost = distance + backtrack * 0.5
        if (cost < bestCost) {
            bestCost = cost
            bestAlong = candidateAlong
            bestDistance = distance
        }
    }
    return RouteProgress(bestAlong, bestDistance, (total - bestAlong).coerceAtLeast(0.0))
}

/** The point [meters] along the route from its start, clamped to the ends. */
fun pointAlongRoute(points: List<LatLng>, meters: Double): LatLng? {
    val pts = distinctPoints(points)
    if (pts.isEmpty()) return null
    if (meters <= 0.0 || pts.size == 1) return pts.first()
    var walked = 0.0
    for (i in 0 until pts.size - 1) {
        val leg = distanceMeters(pts[i], pts[i + 1])
        if (walked + leg >= meters) {
            val t = if (leg <= 1e-9) 0.0 else (meters - walked) / leg
            return LatLng(
                pts[i].latitude + (pts[i + 1].latitude - pts[i].latitude) * t,
                pts[i].longitude + (pts[i + 1].longitude - pts[i].longitude) * t,
            )
        }
        walked += leg
    }
    return pts.last()
}

/**
 * The part of the route still ahead: the user's projected point, then every
 * later vertex. Renderers that start drawing from the user (the AR ground
 * ribbon) need this now that a route is followed rather than recomputed from
 * wherever the user stands.
 */
fun remainingRoute(points: List<LatLng>, alongMeters: Double): List<LatLng> {
    val pts = distinctPoints(points)
    if (pts.size < 2) return pts
    val start = pointAlongRoute(pts, alongMeters) ?: return pts
    val out = mutableListOf(start)
    var walked = 0.0
    for (i in 1 until pts.size) {
        walked += distanceMeters(pts[i - 1], pts[i])
        if (walked > alongMeters + 0.5) out.add(pts[i])
    }
    return if (out.size >= 2) out else listOf(start, pts.last())
}

/** The next instruction ahead of the user, with the distance to it. */
data class UpcomingManeuver(
    val maneuver: Maneuver,
    val index: Int,
    val distanceMeters: Double,
    /** The instruction after this one, for "then turn left" on the card. */
    val then: Maneuver?,
)

fun upcomingManeuver(maneuvers: List<Maneuver>, alongMeters: Double): UpcomingManeuver? {
    // One metre of slack: standing on the corner itself still shows that turn.
    val index = maneuvers.indexOfFirst { it.type != ManeuverType.DEPART && it.alongMeters > alongMeters - 1.0 }
    if (index < 0) return null
    val m = maneuvers[index]
    return UpcomingManeuver(m, index, (m.alongMeters - alongMeters).coerceAtLeast(0.0), maneuvers.getOrNull(index + 1))
}

/** Off-route strikes so far; a single bad fix must not trigger a reroute. */
data class OffRouteState(val strikes: Int = 0)

/**
 * Feeds one fix's distance from the route. Returns the next state and whether
 * to reroute now. Poor accuracy widens the limit: a 30 m fix that lands 22 m
 * away says nothing about where the user really is.
 */
fun offRouteStep(state: OffRouteState, offRouteMeters: Double, accuracyMeters: Double? = null): Pair<OffRouteState, Boolean> {
    val limit = OFF_ROUTE_DISTANCE_M + ((accuracyMeters ?: 0.0).coerceIn(0.0, 30.0) - 10.0).coerceAtLeast(0.0)
    if (offRouteMeters <= limit) return OffRouteState() to false
    val strikes = state.strikes + 1
    return if (strikes >= OFF_ROUTE_FIXES) OffRouteState() to true else OffRouteState(strikes) to false
}

/**
 * Re-aims compass guidance along the route: the arrow points at the route
 * [ROUTE_LOOKAHEAD_M] ahead instead of through buildings at the door, and the
 * distance becomes what is left to walk. Other modes pass through unchanged --
 * the mode itself is still decided by distance to the door.
 */
fun routedGuidance(
    mode: GuidanceMode,
    position: LatLng,
    headingDegrees: Double?,
    points: List<LatLng>,
    progress: RouteProgress?,
): GuidanceMode {
    if (mode !is GuidanceMode.Compass || progress == null || points.size < 2) return mode
    val aim = pointAlongRoute(points, progress.alongMeters + ROUTE_LOOKAHEAD_M) ?: return mode
    if (distanceMeters(position, aim) < 1.0) return mode
    val bearing = bearingDegrees(position, aim)
    val relative = headingDegrees?.let { relativeBearing(it, bearing) } ?: 0.0
    return GuidanceMode.Compass(progress.remainingMeters.coerceAtLeast(mode.distanceMeters), bearing, relative)
}

// ---- Words -----------------------------------------------------------------

private val ARABIC_DIGITS = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')

private fun arabicDigits(value: Int): String =
    value.toString().map { if (it in '0'..'9') ARABIC_DIGITS[it - '0'] else it }.joinToString("")

/** Rounded the way people judge distance on foot: 5 m steps near, 10 m far. */
fun roundedWalkMeters(meters: Double): Int {
    val step = if (meters >= 100.0) 10 else 5
    return ((meters / step).roundToInt() * step).coerceAtLeast(step)
}

/** "١٢٠ م" / "١٫٢ كم" for the turn card. */
fun formatTurnDistanceAr(meters: Double): String {
    if (meters >= 1000.0) {
        val tenths = (meters / 100.0).roundToInt()
        return "${arabicDigits(tenths / 10)}٫${arabicDigits(tenths % 10)} كم"
    }
    return "${arabicDigits(roundedWalkMeters(meters))} م"
}

/** The bare verb for a manoeuvre, without distance. */
fun maneuverVerbAr(type: ManeuverType): String = when (type) {
    ManeuverType.DEPART -> "امشِ مستقيماً"
    ManeuverType.SLIGHT_RIGHT -> "انعطف يميناً قليلاً"
    ManeuverType.RIGHT -> "انعطف يميناً"
    ManeuverType.SHARP_RIGHT -> "انعطف يميناً بحدّة"
    ManeuverType.SLIGHT_LEFT -> "انعطف يساراً قليلاً"
    ManeuverType.LEFT -> "انعطف يساراً"
    ManeuverType.SHARP_LEFT -> "انعطف يساراً بحدّة"
    ManeuverType.U_TURN -> "استدر للخلف"
    ManeuverType.ARRIVE -> "وصلت إلى وجهتك"
}

/**
 * The instruction for the next manoeuvre at [distanceMeters] from it:
 * «بعد ٤٠ م انعطف يميناً عند كلية الشريعة», or just «انعطف يميناً» at the corner.
 */
fun maneuverInstructionAr(m: Maneuver, distanceMeters: Double?): String {
    val far = distanceMeters != null && distanceMeters > TURN_NOW_DISTANCE_M
    return when (m.type) {
        ManeuverType.DEPART -> if (distanceMeters != null) "امشِ مستقيماً ${formatTurnDistanceAr(distanceMeters)}"
            else maneuverVerbAr(m.type)
        ManeuverType.ARRIVE -> if (far) "بعد ${formatTurnDistanceAr(distanceMeters!!)} تصل إلى وجهتك"
            else maneuverVerbAr(m.type)
        else -> {
            val at = m.landmarkName?.let { " عند $it" }.orEmpty()
            if (far) "بعد ${formatTurnDistanceAr(distanceMeters!!)} ${maneuverVerbAr(m.type)}$at"
            else "${maneuverVerbAr(m.type)}$at"
        }
    }
}

/** The compact glasses wording: «بعد ٤٠ م يميناً», then «انعطف يميناً». */
fun glassesTurnTextAr(type: ManeuverType, distanceMeters: Double): String {
    val direction = when (type) {
        ManeuverType.SLIGHT_RIGHT -> "يميناً قليلاً"
        ManeuverType.RIGHT -> "يميناً"
        ManeuverType.SHARP_RIGHT -> "يميناً بحدّة"
        ManeuverType.SLIGHT_LEFT -> "يساراً قليلاً"
        ManeuverType.LEFT -> "يساراً"
        ManeuverType.SHARP_LEFT -> "يساراً بحدّة"
        else -> return maneuverVerbAr(type)
    }
    return if (distanceMeters > TURN_NOW_DISTANCE_M) "بعد ${formatTurnDistanceAr(distanceMeters)} $direction"
    else "انعطف $direction"
}

/**
 * The route as a list for the steps sheet: each step's instruction and the
 * distance walked after it, so the list reads «امشِ مستقيماً · ١٢٠ م»,
 * «انعطف يميناً · ٤٠ م», «وصلت إلى وجهتك».
 */
fun routeStepList(maneuvers: List<Maneuver>): List<RouteStep> = maneuvers.mapIndexed { i, m ->
    val after = maneuvers.getOrNull(i + 1)?.legMeters ?: 0.0
    val text = when {
        m.type == ManeuverType.ARRIVE -> maneuverVerbAr(m.type)
        m.landmarkName != null && m.type.isTurn -> "${maneuverVerbAr(m.type)} عند ${m.landmarkName}"
        else -> maneuverVerbAr(m.type)
    }
    RouteStep(text, after, m.position)
}

// ---- Voice policy ----------------------------------------------------------

enum class TurnCueStage { PREPARE, NOW }

/** A turn announcement due now. [key] identifies it so it is spoken once. */
data class TurnCue(val maneuver: Maneuver, val stage: TurnCueStage, val distanceMeters: Double) {
    val key: String get() = "${maneuver.alongMeters.roundToInt()}-${maneuver.type}-$stage"
}

/** Which turn announcement, if any, the user's position calls for. */
fun turnCueFor(next: UpcomingManeuver?): TurnCue? {
    val m = next?.maneuver ?: return null
    if (!m.type.isTurn) return null
    return when {
        next.distanceMeters <= TURN_NOW_DISTANCE_M -> TurnCue(m, TurnCueStage.NOW, next.distanceMeters)
        next.distanceMeters <= TURN_PREPARE_DISTANCE_M -> TurnCue(m, TurnCueStage.PREPARE, next.distanceMeters)
        else -> null
    }
}

/** Turn announcements already made on the current route. */
data class TurnVoiceState(val spoken: Set<String> = emptySet())

/** True when [cue] has not been announced yet on this route. */
fun turnCueIsNew(state: TurnVoiceState, cue: TurnCue?): Boolean = cue != null && cue.key !in state.spoken

fun TurnVoiceState.after(cue: TurnCue): TurnVoiceState = TurnVoiceState(spoken + cue.key)

/** The text compared by the shared voice policy, so turns dedupe like other cues. */
fun turnCueTextAr(cue: TurnCue): String =
    maneuverInstructionAr(cue.maneuver, if (cue.stage == TurnCueStage.PREPARE) cue.distanceMeters else null)
