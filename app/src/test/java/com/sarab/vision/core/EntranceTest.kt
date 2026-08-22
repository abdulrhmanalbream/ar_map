package com.sarab.vision.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing to the door rather than the middle of the building.
 *
 * Written from a real survey. The map pin on three of four colleges sat 40-50m
 * from the actual entrance, so the app announced arrival while the user was
 * still walking past a blank wall, and a second entrance on the far side of a
 * building went unrecognised entirely.
 */
class EntranceTest {

    /** The real Hadith college: pin, and its gate 49m away. */
    private val hadithCentre = LatLng(24.4821488, 39.5605851)
    private val hadithGate = LatLng(24.481710, 39.560565)

    private fun college(vararg doors: Entrance) = Landmark(
        id = "lm-hadith",
        name = "كلية الحديث الشريف والدراسات الإسلامية",
        category = LandmarkCategory.FACULTY,
        position = hadithCentre,
        entrances = doors.toList()
    )

    private val north = Entrance("n", "المدخل الشمالي", stepAlongBearing(hadithCentre, 0.0, 40.0))
    private val south = Entrance("s", "المدخل الجنوبي", stepAlongBearing(hadithCentre, 180.0, 40.0))

    @Test
    fun `with no entrances the centre is still used`() {
        val plain = college()
        assertEquals(hadithCentre, plain.approachPoint(LatLng(24.4810, 39.5600)))
        assertNull(plain.nearestEntrance(LatLng(24.4810, 39.5600)))
    }

    @Test
    fun `the nearest door wins`() {
        val building = college(north, south)

        val fromNorth = stepAlongBearing(hadithCentre, 0.0, 120.0)
        assertEquals("n", building.nearestEntrance(fromNorth)!!.id)

        val fromSouth = stepAlongBearing(hadithCentre, 180.0, 120.0)
        assertEquals("s", building.nearestEntrance(fromSouth)!!.id)
    }

    @Test
    fun `approaching from the far side does not route through the building`() {
        // The whole point of the second entrance: someone arriving at the back
        // should be sent to the back door, not around the block.
        val building = college(north, south)
        val behind = stepAlongBearing(hadithCentre, 180.0, 60.0)

        val approach = building.approachPoint(behind)
        assertTrue(
            "was sent to the far door",
            distanceMeters(behind, approach) < distanceMeters(behind, north.position)
        )
    }

    @Test
    fun `arrival is judged at the door, not the pin`() {
        // Standing AT the real gate, 49m from the map pin. Measuring to the
        // pin says "still 49m away"; measuring to the door says "arrived".
        val building = college(Entrance("main", "المدخل الرئيسي", hadithGate))

        val atPin = guidanceFor(hadithCentre, 0.0, college(), emptyList())
        assertTrue("standing on the pin with no doors is arrival", atPin is GuidanceMode.Arrived)

        val atGate = guidanceFor(hadithGate, 0.0, building, emptyList())
        assertTrue(
            "standing at the gate should be arrival, got $atGate",
            atGate is GuidanceMode.Arrived
        )
    }

    @Test
    fun `the pin no longer counts as arrival once a door is known`() {
        // Inverse of the above, and the bug as it was actually experienced:
        // the app said "arrived" while the user was beside a wall.
        val building = college(Entrance("main", "المدخل الرئيسي", hadithGate))
        val guidance = guidanceFor(hadithCentre, 0.0, building, emptyList())

        assertTrue(
            "standing 49m from the door should not be arrival, got $guidance",
            guidance !is GuidanceMode.Arrived
        )
    }

    @Test
    fun `distance and bearing are measured to the door`() {
        val building = college(Entrance("main", "المدخل الرئيسي", hadithGate))
        val from = stepAlongBearing(hadithGate, 180.0, 100.0)

        val ranked = rankByDistance(listOf(building), from).single()
        assertEquals(
            "distance should be to the gate",
            distanceMeters(from, hadithGate),
            ranked.distanceMeters,
            1.0
        )
        assertEquals(
            "bearing should point at the gate",
            bearingDegrees(from, hadithGate),
            ranked.bearingDegrees,
            1.0
        )
    }

    @Test
    fun `an invalid position still yields a usable door`() {
        val building = college(north, south)
        // No GPS yet: any door beats crashing or returning the centre.
        assertEquals("n", building.nearestEntrance(LatLng(0.0, 0.0))!!.id)
    }

    @Test
    fun `entrances with broken coordinates are ignored`() {
        val building = college(
            Entrance("bad", "معطوب", LatLng(Double.NaN, Double.NaN)),
            north
        )
        assertEquals("n", building.nearestEntrance(stepAlongBearing(hadithCentre, 0.0, 80.0))!!.id)
    }

    @Test
    fun `the four real gates sit far enough apart for GPS to separate them`() {
        // If two gates were within GPS error the app would have to ask which
        // building the user meant on every arrival.
        val gates = listOf(
            LatLng(24.480899, 39.559647),  // Shariah
            LatLng(24.481676, 39.560255),  // Dawah
            LatLng(24.481710, 39.560565),  // Hadith
            LatLng(24.481676, 39.560948)   // Quran
        )
        for (i in gates.indices) {
            for (j in i + 1 until gates.size) {
                val d = distanceMeters(gates[i], gates[j])
                assertTrue("gates $i and $j are only ${d}m apart", d > GPS_DISTINGUISHABLE_M)
            }
        }
    }
}
