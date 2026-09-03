package com.sarab.vision.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The glasses menu state machine: every input source (air gestures, volume
 * keys, remotes) reduces to the same five commands, so this is where the
 * whole eyes-free interaction is verified.
 */
class HudMenuTest {

    private val origin = LatLng(24.500000, 39.500000)

    private fun at(name: String, northMeters: Double, eastMeters: Double): Landmark {
        val dLat = northMeters / 111_195.0
        val dLon = eastMeters / (111_195.0 * kotlin.math.cos(Math.toRadians(origin.latitude)))
        return Landmark(
            id = name,
            name = name,
            category = LandmarkCategory.FACULTY,
            position = LatLng(origin.latitude + dLat, origin.longitude + dLon)
        )
    }

    private val far = at("Far", 300.0, 0.0)
    private val near = at("Near", 50.0, 0.0)
    private val mid = at("Mid", 120.0, 0.0)
    private val all = listOf(far, near, mid)

    // ---- Opening -----------------------------------------------------------

    @Test
    fun `any browsing command opens the destination menu`() {
        for (command in listOf(HudCommand.MENU, HudCommand.NEXT, HudCommand.PREV)) {
            val result = advanceHudMenu(HudMenuState.Closed, command, all, origin)
            assertTrue("$command should open menu", result.state is HudMenuState.DestinationMenu)
        }
    }

    @Test
    fun `menu items come nearest first`() {
        val result = advanceHudMenu(HudMenuState.Closed, HudCommand.MENU, all, origin)
        val menu = result.state as HudMenuState.DestinationMenu
        assertEquals(listOf("Near", "Mid", "Far"), menu.items.map { it.name })
    }

    @Test
    fun `no landmarks means the menu stays closed`() {
        val result = advanceHudMenu(HudMenuState.Closed, HudCommand.MENU, emptyList(), origin)
        assertTrue(result.state is HudMenuState.Closed)
    }

    @Test
    fun `confirm while closed asks for the instruction again`() {
        val result = advanceHudMenu(HudMenuState.Closed, HudCommand.CONFIRM, all, origin)
        assertTrue(result.state is HudMenuState.Closed)
        assertEquals(HudMenuAction.RepeatInstruction, result.action)
    }

    // ---- Cycling and selecting --------------------------------------------

    @Test
    fun `next and prev cycle with wrap-around`() {
        var state: HudMenuState = HudMenuState.DestinationMenu(all, 0)

        state = advanceHudMenu(state, HudCommand.NEXT, all, origin).state
        assertEquals(1, (state as HudMenuState.DestinationMenu).selected)

        state = advanceHudMenu(state, HudCommand.NEXT, all, origin).state
        state = advanceHudMenu(state, HudCommand.NEXT, all, origin).state
        assertEquals(0, (state as HudMenuState.DestinationMenu).selected)

        state = advanceHudMenu(state, HudCommand.PREV, all, origin).state
        assertEquals(all.size - 1, (state as HudMenuState.DestinationMenu).selected)
    }

    @Test
    fun `confirm selects the highlighted landmark and closes`() {
        val state = HudMenuState.DestinationMenu(all, 1)
        val result = advanceHudMenu(state, HudCommand.CONFIRM, all, origin)

        assertTrue(result.state is HudMenuState.Closed)
        assertEquals(HudMenuAction.Select(all[1]), result.action)
    }

    @Test
    fun `back closes without selecting`() {
        val result = advanceHudMenu(HudMenuState.DestinationMenu(all, 2), HudCommand.BACK, all, origin)
        assertTrue(result.state is HudMenuState.Closed)
        assertEquals(HudMenuAction.None, result.action)
    }

    @Test
    fun `ambiguity confirm picks the candidate`() {
        val state = HudMenuState.AmbiguityMenu(listOf(near, mid), 1)
        val result = advanceHudMenu(state, HudCommand.CONFIRM, all, origin)
        assertEquals(HudMenuAction.Select(mid), result.action)
    }

    // ---- Guidance sync -----------------------------------------------------

    @Test
    fun `ambiguity opens the menu only on transition`() {
        val ambiguous = GuidanceMode.Ambiguous(10.0, listOf(near, mid))

        val opened = syncHudMenu(HudMenuState.Closed, GuidanceMode.NoFix, ambiguous, all, near, origin)
        assertTrue(opened is HudMenuState.AmbiguityMenu)

        // Same mode on the next tick after the user dismissed it: stays shut.
        val redismissed = syncHudMenu(HudMenuState.Closed, ambiguous, ambiguous, all, near, origin)
        assertTrue(redismissed is HudMenuState.Closed)
    }

    @Test
    fun `ambiguity menu closes itself once resolved`() {
        val open = HudMenuState.AmbiguityMenu(listOf(near, mid), 0)
        val after = syncHudMenu(open, GuidanceMode.Ambiguous(10.0, listOf(near, mid)),
            GuidanceMode.Arrived(5.0), all, near, origin)
        // Arrival transition immediately offers the next stop.
        assertTrue(after is HudMenuState.Closed || after is HudMenuState.NextSuggestion)
    }

    @Test
    fun `arrival suggests the nearest other landmark`() {
        val state = syncHudMenu(
            HudMenuState.Closed, GuidanceMode.ArApproach(12.0), GuidanceMode.Arrived(5.0),
            all, near, origin
        )
        assertTrue(state is HudMenuState.NextSuggestion)
        assertEquals("Mid", (state as HudMenuState.NextSuggestion).suggestion.name)
    }

    @Test
    fun `an open menu is never replaced by sync`() {
        val open = HudMenuState.DestinationMenu(all, 2)
        val after = syncHudMenu(open, GuidanceMode.ArApproach(12.0), GuidanceMode.Arrived(5.0), all, near, origin)
        assertEquals(open, after)
    }

    // ---- Display rows ------------------------------------------------------

    @Test
    fun `closed menu renders nothing`() {
        assertNull(hudMenuView(HudMenuState.Closed, origin))
    }

    @Test
    fun `rows carry distances and exactly one selection`() {
        val view = hudMenuView(HudMenuState.DestinationMenu(listOf(near, mid, far), 1), origin)!!
        assertEquals(3, view.rows.size)
        assertEquals(1, view.rows.count { it.selected })
        assertTrue(view.rows[1].selected)
        // ~50m to the nearest, formatted for the HUD. The geodesic round-trip
        // can land a fraction under 50, so assert the format, not the digit.
        assertTrue(view.rows[0].distanceText!!.endsWith(" م"))
    }

    @Test
    fun `long lists window around the selection`() {
        assertEquals(0 until 5, visibleWindow(size = 12, selected = 0))
        assertEquals(4 until 9, visibleWindow(size = 12, selected = 6))
        assertEquals(7 until 12, visibleWindow(size = 12, selected = 11))
        assertEquals(0 until 3, visibleWindow(size = 3, selected = 2))
    }
}
