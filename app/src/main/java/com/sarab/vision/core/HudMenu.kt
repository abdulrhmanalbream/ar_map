package com.sarab.vision.core

/**
 * The eyes-free menu shown on display glasses, as a pure state machine.
 *
 * ## Why one abstract command set
 *
 * The glasses HUD can be driven by several very different inputs: the XREAL
 * Eye's native gesture recognition (which reaches Android as mouse click and
 * scroll events), the phone's volume keys as a sunlight-proof fallback, and
 * Bluetooth remotes via media buttons. Each of those is an unreliable,
 * platform-specific stream -- but all any of them can really say is "next",
 * "previous", "yes", "no", "menu". So the sources are dumb translators into
 * [HudCommand], and everything that matters happens here, where it can be
 * unit-tested without a single Android import.
 *
 * ## Why actions are returned, not performed
 *
 * Confirming a menu item must change the navigation target, which lives in
 * Android-side state. Returning the intent as data keeps this file pure and
 * makes "confirm selects the highlighted landmark" a one-line assertion.
 */

/** What any input source can say. */
enum class HudCommand { NEXT, PREV, CONFIRM, BACK, MENU }

/** What the glasses menu is currently showing. */
sealed interface HudMenuState {

    /** No menu: the HUD shows plain navigation. */
    data object Closed : HudMenuState

    /** The destination picker, nearest-first. */
    data class DestinationMenu(
        val items: List<Landmark>,
        val selected: Int
    ) : HudMenuState

    /**
     * The "which building is actually in front of you" question, raised when
     * GPS cannot distinguish nearby candidates. Same honesty rule as the
     * phone UI: ask, never guess.
     */
    data class AmbiguityMenu(
        val candidates: List<Landmark>,
        val selected: Int
    ) : HudMenuState

    /** Shown on arrival: offer the nearest other landmark as the next stop. */
    data class NextSuggestion(val suggestion: Landmark) : HudMenuState
}

/** A side effect the caller must perform. */
sealed interface HudMenuAction {
    data object None : HudMenuAction

    /** Make this landmark the navigation target. */
    data class Select(val landmark: Landmark) : HudMenuAction

    /**
     * Say the current instruction again. Confirm with no menu open means
     * "what was that?", which costs nothing and is exactly what someone
     * walking with their phone half-lowered actually wants.
     */
    data object RepeatInstruction : HudMenuAction
}

data class HudMenuResult(
    val state: HudMenuState,
    val action: HudMenuAction = HudMenuAction.None
)

/**
 * Destination list for the glasses menu: nearest first, because cycling is
 * linear. On a phone the eye jumps anywhere in a list; through a scroll
 * gesture every extra position is another flick of the hand, so the likely
 * choices must cost the fewest commands.
 */
fun hudDestinationItems(landmarks: List<Landmark>, userPosition: LatLng?): List<Landmark> {
    if (userPosition == null || !userPosition.isValid) return landmarks
    return rankByDistance(landmarks, userPosition).map { it.landmark }
}

/** Advances the menu by one command from any input source. */
fun advanceHudMenu(
    state: HudMenuState,
    command: HudCommand,
    landmarks: List<Landmark>,
    userPosition: LatLng?
): HudMenuResult = when (state) {

    is HudMenuState.Closed -> when (command) {
        // Any browsing gesture opens the menu: requiring a distinct "open"
        // gesture first would mean teaching two gestures where one will do.
        HudCommand.MENU, HudCommand.NEXT, HudCommand.PREV -> {
            val items = hudDestinationItems(landmarks, userPosition)
            if (items.isEmpty()) HudMenuResult(state)
            else HudMenuResult(HudMenuState.DestinationMenu(items, 0))
        }
        HudCommand.CONFIRM -> HudMenuResult(state, HudMenuAction.RepeatInstruction)
        HudCommand.BACK -> HudMenuResult(state)
    }

    is HudMenuState.DestinationMenu -> when (command) {
        HudCommand.NEXT -> HudMenuResult(state.copy(selected = wrap(state.selected + 1, state.items.size)))
        HudCommand.PREV -> HudMenuResult(state.copy(selected = wrap(state.selected - 1, state.items.size)))
        HudCommand.CONFIRM -> HudMenuResult(
            HudMenuState.Closed,
            HudMenuAction.Select(state.items[state.selected])
        )
        HudCommand.BACK, HudCommand.MENU -> HudMenuResult(HudMenuState.Closed)
    }

    is HudMenuState.AmbiguityMenu -> when (command) {
        HudCommand.NEXT -> HudMenuResult(state.copy(selected = wrap(state.selected + 1, state.candidates.size)))
        HudCommand.PREV -> HudMenuResult(state.copy(selected = wrap(state.selected - 1, state.candidates.size)))
        HudCommand.CONFIRM -> HudMenuResult(
            HudMenuState.Closed,
            HudMenuAction.Select(state.candidates[state.selected])
        )
        HudCommand.BACK, HudCommand.MENU -> HudMenuResult(HudMenuState.Closed)
    }

    is HudMenuState.NextSuggestion -> when (command) {
        HudCommand.CONFIRM -> HudMenuResult(
            HudMenuState.Closed,
            HudMenuAction.Select(state.suggestion)
        )
        // Browsing away from the suggestion lands in the full picker.
        HudCommand.NEXT, HudCommand.PREV, HudCommand.MENU -> {
            val items = hudDestinationItems(landmarks, userPosition)
            if (items.isEmpty()) HudMenuResult(HudMenuState.Closed)
            else HudMenuResult(HudMenuState.DestinationMenu(items, 0))
        }
        HudCommand.BACK -> HudMenuResult(HudMenuState.Closed)
    }
}

/**
 * Reconciles the menu with guidance changes.
 *
 * Keyed on the TRANSITION (previous vs current), not the current mode alone:
 * keying on the mode would reopen a menu the user just dismissed on every
 * sensor tick, turning "no thanks" into an argument with the app.
 */
fun syncHudMenu(
    state: HudMenuState,
    previous: GuidanceMode?,
    current: GuidanceMode,
    landmarks: List<Landmark>,
    target: Landmark?,
    userPosition: LatLng?
): HudMenuState {
    // A resolved ambiguity takes its question with it.
    if (state is HudMenuState.AmbiguityMenu && current !is GuidanceMode.Ambiguous) {
        return HudMenuState.Closed
    }
    if (state !is HudMenuState.Closed) return state

    if (current is GuidanceMode.Ambiguous && previous !is GuidanceMode.Ambiguous) {
        if (current.candidates.isNotEmpty()) {
            return HudMenuState.AmbiguityMenu(current.candidates, 0)
        }
    }

    if (current is GuidanceMode.Arrived && previous !is GuidanceMode.Arrived) {
        val next = hudDestinationItems(landmarks, userPosition)
            .firstOrNull { it.id != target?.id }
        if (next != null) return HudMenuState.NextSuggestion(next)
    }

    return state
}

private fun wrap(index: Int, size: Int): Int =
    if (size <= 0) 0 else ((index % size) + size) % size

// ---- Display rows ---------------------------------------------------------

/** One line of the glasses menu, ready to draw. */
data class HudMenuRow(
    val name: String,
    val distanceText: String?,
    val selected: Boolean
)

/** The glasses menu, ready to draw. */
data class HudMenuView(
    val title: String,
    val rows: List<HudMenuRow>,
    val hint: String
)

/** How many rows fit the glasses panel comfortably at HUD text sizes. */
const val HUD_MENU_VISIBLE_ROWS = 5

/**
 * Projects the menu state to display rows.
 *
 * Long lists are windowed around the selection rather than scrolled: the
 * glasses have no scrollbar affordance, and a fixed window that slides with
 * the highlight is the only scrolling that reads clearly at a glance.
 */
fun hudMenuView(state: HudMenuState, userPosition: LatLng?): HudMenuView? = when (state) {
    is HudMenuState.Closed -> null

    is HudMenuState.DestinationMenu -> HudMenuView(
        title = "اختر وجهتك",
        rows = windowedRows(state.items, state.selected, userPosition),
        hint = "مرّر للتنقل · اضغط للاختيار · رجوع للإغلاق"
    )

    is HudMenuState.AmbiguityMenu -> HudMenuView(
        title = "أي مبنى أمامك؟",
        rows = windowedRows(state.candidates, state.selected, userPosition),
        hint = "دقة GPS لا تكفي للتفريق — تأكد بالنظر ثم اختر"
    )

    is HudMenuState.NextSuggestion -> HudMenuView(
        title = "وصلت — الوجهة التالية؟",
        rows = listOf(rowFor(state.suggestion, userPosition, selected = true)),
        hint = "اضغط للتوجه إليه · رجوع للإنهاء"
    )
}

private fun windowedRows(
    items: List<Landmark>,
    selected: Int,
    userPosition: LatLng?
): List<HudMenuRow> {
    val window = visibleWindow(items.size, selected)
    return window.map { i -> rowFor(items[i], userPosition, selected = i == selected) }
}

/** The slice of indices to draw, keeping the selection inside the window. */
fun visibleWindow(size: Int, selected: Int, visible: Int = HUD_MENU_VISIBLE_ROWS): IntRange {
    if (size <= visible) return 0 until size
    val start = (selected - visible / 2).coerceIn(0, size - visible)
    return start until start + visible
}

private fun rowFor(landmark: Landmark, userPosition: LatLng?, selected: Boolean): HudMenuRow {
    val distance = userPosition?.takeIf { it.isValid }?.let {
        formatDistanceAr(distanceMeters(it, landmark.approachPoint(it)))
    }
    return HudMenuRow(landmark.name, distance, selected)
}
