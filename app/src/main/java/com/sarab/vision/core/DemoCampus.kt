package com.sarab.vision.core

import kotlin.math.cos
import kotlin.math.sin

/**
 * Builds a fake campus around wherever the user happens to be standing.
 *
 * Exists so the whole system can be seen and judged away from the real
 * campus: the arrow, the floating markers, the distance countdown, the map
 * and — importantly — the ambiguity prompt.
 *
 * The distances are not arbitrary. They mirror the real campus the user
 * described (a 20m pair and landmarks around 250m away), so what is seen in
 * demo mode is what will actually happen on site.
 */
object DemoCampus {

    /** Offsets in metres (north, east) from the user's position. */
    private data class Offset(
        val north: Double,
        val east: Double,
        val name: String,
        val category: LandmarkCategory,
        val detail: String
    )

    private val LAYOUT = listOf(
        // Far landmark, straight ahead (north). Tests the distant AR marker
        // and the "walk forward" instruction.
        Offset(
            250.0, 0.0,
            "الملعب",
            LandmarkCategory.SPORTS,
            "معلم بعيد لاختبار العلامة الطافية والسهم على مسافة 250 متراً."
        ),

        // The 20m pair. This is the important one: it reproduces the case
        // where GPS genuinely cannot say which building you are at.
        Offset(
            18.0, 0.0,
            "كلية الحاسب الآلي",
            LandmarkCategory.FACULTY,
            "أحد مبنيين متقاربين (20 متراً) لاختبار سؤال التأكيد."
        ),
        Offset(
            18.0, 20.0,
            "كلية الشريعة",
            LandmarkCategory.FACULTY,
            "المبنى الثاني من الزوج المتقارب — يبعد 20 متراً عن الأول."
        ),

        // Mid-range, off to one side. Tests the off-screen indicator and the
        // GPS -> AR handoff at 30m.
        Offset(
            -40.0, 60.0,
            "سكن طلاب - وحدة 13",
            LandmarkCategory.HOUSING,
            "معلم متوسط المسافة وخلف المستخدم — لاختبار مؤشر خارج الشاشة."
        )
    )

    /**
     * Generates the demo landmarks around [centre].
     *
     * @param headingDeg rotates the whole layout so "الملعب" sits straight
     *   ahead of wherever the user is currently facing. Without this the far
     *   landmark could start behind them, which makes the first impression
     *   confusing rather than illustrative.
     */
    fun generate(centre: LatLng, headingDeg: Double = 0.0): List<Landmark> {
        if (!centre.isValid) return emptyList()

        val metresPerDegLat = 111_195.0
        val metresPerDegLon = 111_195.0 * cos(Math.toRadians(centre.latitude))
        val rad = Math.toRadians(headingDeg)

        return LAYOUT.mapIndexed { index, o ->
            // Rotate the offset into the user's facing direction.
            val north = o.north * cos(rad) - o.east * sin(rad)
            val east = o.north * sin(rad) + o.east * cos(rad)

            val position = LatLng(
                latitude = centre.latitude + north / metresPerDegLat,
                longitude = centre.longitude + east / metresPerDegLon
            )

            Landmark(
                id = "demo-$index",
                name = o.name,
                category = o.category,
                position = position,
                detail = o.detail,
                hours = "وضع تجريبي",
                amenities = listOf("تجريبي"),
                capturedAccuracyM = 5f
            )
        }
    }

    /** True when a landmark came from demo mode rather than a real survey. */
    fun isDemo(landmark: Landmark): Boolean = landmark.id.startsWith("demo-")
}

/**
 * Moves a simulated position forward, for testing without walking.
 *
 * Lets the distance count down and the guidance change mode (compass -> AR
 * approach -> arrived) while sitting still, which is the only practical way
 * to check those transitions away from the campus.
 */
fun stepTowards(from: LatLng, to: LatLng, metres: Double): LatLng {
    if (!from.isValid || !to.isValid) return from

    val remaining = distanceMeters(from, to)
    if (remaining <= 0.5) return to

    val fraction = (metres / remaining).coerceIn(0.0, 1.0)
    return LatLng(
        latitude = from.latitude + (to.latitude - from.latitude) * fraction,
        longitude = from.longitude + (to.longitude - from.longitude) * fraction
    )
}

/** Moves a simulated position along a bearing, for free-roam testing. */
fun stepAlongBearing(from: LatLng, bearingDeg: Double, metres: Double): LatLng {
    if (!from.isValid) return from

    val metresPerDegLat = 111_195.0
    val metresPerDegLon = 111_195.0 * cos(Math.toRadians(from.latitude))
    val rad = Math.toRadians(bearingDeg)

    return LatLng(
        latitude = from.latitude + (metres * cos(rad)) / metresPerDegLat,
        longitude = from.longitude + (metres * sin(rad)) / metresPerDegLon
    )
}
