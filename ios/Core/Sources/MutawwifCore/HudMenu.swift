import Foundation

// The eyes-free menu shown on display glasses, as a pure state machine.
//
// ## Why one abstract command set
//
// The HUD can be driven by very different inputs: glasses gestures, volume
// keys, Bluetooth remotes via media buttons. Each is an unreliable,
// platform-specific stream -- but all any of them can really say is "next",
// "previous", "yes", "no", "menu". So the sources are dumb translators into
// `HudCommand`, and everything that matters happens here, where it can be
// unit-tested without a single platform import.
//
// ## Why actions are returned, not performed
//
// Confirming a menu item must change the navigation target, which lives in
// app-side state. Returning the intent as data keeps this file pure and makes
// "confirm selects the highlighted landmark" a one-line assertion.

/// What any input source can say.
public enum HudCommand: String, CaseIterable {
    case next = "NEXT"
    case prev = "PREV"
    case confirm = "CONFIRM"
    case back = "BACK"
    case menu = "MENU"
}

/// What the glasses menu is currently showing.
public enum HudMenuState: Equatable {
    /// No menu: the HUD shows plain navigation.
    case closed

    /// The destination picker, nearest-first.
    case destinationMenu(items: [Landmark], selected: Int)

    /// The "which building is actually in front of you" question, raised when
    /// GPS cannot distinguish nearby candidates. Same honesty rule as the
    /// phone UI: ask, never guess.
    case ambiguityMenu(candidates: [Landmark], selected: Int)

    /// Shown on arrival: offer the nearest other landmark as the next stop.
    case nextSuggestion(suggestion: Landmark)
}

/// A side effect the caller must perform.
public enum HudMenuAction: Equatable {
    case none

    /// Make this landmark the navigation target.
    case select(landmark: Landmark)

    /// Say the current instruction again. Confirm with no menu open means
    /// "what was that?", which costs nothing and is exactly what someone
    /// walking with their phone half-lowered actually wants.
    case repeatInstruction
}

public struct HudMenuResult: Equatable {
    public var state: HudMenuState
    public var action: HudMenuAction

    public init(state: HudMenuState, action: HudMenuAction = .none) {
        self.state = state
        self.action = action
    }
}

/// Destination list for the glasses menu: nearest first, because cycling is
/// linear. On a phone the eye jumps anywhere in a list; through a scroll
/// gesture every extra position is another flick of the hand, so the likely
/// choices must cost the fewest commands.
public func hudDestinationItems(_ landmarks: [Landmark], _ userPosition: LatLng?) -> [Landmark] {
    guard let pos = userPosition, pos.isValid else { return landmarks }
    return rankByDistance(landmarks, pos).map { $0.landmark }
}

/// Advances the menu by one command from any input source.
public func advanceHudMenu(
    _ state: HudMenuState,
    _ command: HudCommand,
    _ landmarks: [Landmark],
    _ userPosition: LatLng?
) -> HudMenuResult {
    switch state {
    case .closed:
        switch command {
        // Any browsing gesture opens the menu: requiring a distinct "open"
        // gesture first would mean teaching two gestures where one will do.
        case .menu, .next, .prev:
            let items = hudDestinationItems(landmarks, userPosition)
            if items.isEmpty { return HudMenuResult(state: state) }
            return HudMenuResult(state: .destinationMenu(items: items, selected: 0))
        case .confirm:
            return HudMenuResult(state: state, action: .repeatInstruction)
        case .back:
            return HudMenuResult(state: state)
        }

    case let .destinationMenu(items, selected):
        switch command {
        case .next: return HudMenuResult(state: .destinationMenu(items: items, selected: wrap(selected + 1, items.count)))
        case .prev: return HudMenuResult(state: .destinationMenu(items: items, selected: wrap(selected - 1, items.count)))
        case .confirm: return HudMenuResult(state: .closed, action: .select(landmark: items[selected]))
        case .back, .menu: return HudMenuResult(state: .closed)
        }

    case let .ambiguityMenu(candidates, selected):
        switch command {
        case .next: return HudMenuResult(state: .ambiguityMenu(candidates: candidates, selected: wrap(selected + 1, candidates.count)))
        case .prev: return HudMenuResult(state: .ambiguityMenu(candidates: candidates, selected: wrap(selected - 1, candidates.count)))
        case .confirm: return HudMenuResult(state: .closed, action: .select(landmark: candidates[selected]))
        case .back, .menu: return HudMenuResult(state: .closed)
        }

    case let .nextSuggestion(suggestion):
        switch command {
        case .confirm:
            return HudMenuResult(state: .closed, action: .select(landmark: suggestion))
        // Browsing away from the suggestion lands in the full picker.
        case .next, .prev, .menu:
            let items = hudDestinationItems(landmarks, userPosition)
            if items.isEmpty { return HudMenuResult(state: .closed) }
            return HudMenuResult(state: .destinationMenu(items: items, selected: 0))
        case .back:
            return HudMenuResult(state: .closed)
        }
    }
}

private func isAmbiguous(_ m: GuidanceMode?) -> Bool {
    if case .some(.ambiguous) = m { return true }
    return false
}

private func isArrived(_ m: GuidanceMode?) -> Bool {
    if case .some(.arrived) = m { return true }
    return false
}

/// Reconciles the menu with guidance changes.
///
/// Keyed on the TRANSITION (previous vs current), not the current mode alone:
/// keying on the mode would reopen a menu the user just dismissed on every
/// sensor tick, turning "no thanks" into an argument with the app.
public func syncHudMenu(
    _ state: HudMenuState,
    previous: GuidanceMode?,
    current: GuidanceMode,
    landmarks: [Landmark],
    target: Landmark?,
    userPosition: LatLng?
) -> HudMenuState {
    // A resolved ambiguity takes its question with it.
    if case .ambiguityMenu = state, !isAmbiguous(current) {
        return .closed
    }
    if state != .closed { return state }

    if case let .ambiguous(_, candidates) = current, !isAmbiguous(previous) {
        if !candidates.isEmpty {
            return .ambiguityMenu(candidates: candidates, selected: 0)
        }
    }

    if isArrived(current) && !isArrived(previous) {
        if let next = hudDestinationItems(landmarks, userPosition).first(where: { $0.id != target?.id }) {
            return .nextSuggestion(suggestion: next)
        }
    }

    return state
}

private func wrap(_ index: Int, _ size: Int) -> Int {
    if size <= 0 { return 0 }
    return ((index % size) + size) % size
}

// ---- Display rows ---------------------------------------------------------

/// One line of the glasses menu, ready to draw.
public struct HudMenuRow: Equatable {
    public var name: String
    public var distanceText: String?
    public var selected: Bool

    public init(name: String, distanceText: String?, selected: Bool) {
        self.name = name
        self.distanceText = distanceText
        self.selected = selected
    }
}

/// The glasses menu, ready to draw.
public struct HudMenuView: Equatable {
    public var title: String
    public var rows: [HudMenuRow]
    public var hint: String

    public init(title: String, rows: [HudMenuRow], hint: String) {
        self.title = title
        self.rows = rows
        self.hint = hint
    }
}

/// How many rows fit the glasses panel comfortably at HUD text sizes.
public let HUD_MENU_VISIBLE_ROWS: Int = 5

/// Projects the menu state to display rows.
///
/// Long lists are windowed around the selection rather than scrolled: the
/// glasses have no scrollbar affordance, and a fixed window that slides with
/// the highlight is the only scrolling that reads clearly at a glance.
public func hudMenuView(_ state: HudMenuState, _ userPosition: LatLng?) -> HudMenuView? {
    switch state {
    case .closed:
        return nil
    case let .destinationMenu(items, selected):
        return HudMenuView(
            title: "اختر وجهتك",
            rows: windowedRows(items, selected, userPosition),
            hint: "مرّر للتنقل · اضغط للاختيار · رجوع للإغلاق"
        )
    case let .ambiguityMenu(candidates, selected):
        return HudMenuView(
            title: "أي مبنى أمامك؟",
            rows: windowedRows(candidates, selected, userPosition),
            hint: "دقة GPS لا تكفي للتفريق — تأكد بالنظر ثم اختر"
        )
    case let .nextSuggestion(suggestion):
        return HudMenuView(
            title: "وصلت — الوجهة التالية؟",
            rows: [rowFor(suggestion, userPosition, selected: true)],
            hint: "اضغط للتوجه إليه · رجوع للإنهاء"
        )
    }
}

private func windowedRows(_ items: [Landmark], _ selected: Int, _ userPosition: LatLng?) -> [HudMenuRow] {
    return visibleWindow(items.count, selected).map { i in
        rowFor(items[i], userPosition, selected: i == selected)
    }
}

/// The slice of indices to draw, keeping the selection inside the window.
public func visibleWindow(_ size: Int, _ selected: Int, visible: Int = HUD_MENU_VISIBLE_ROWS) -> Range<Int> {
    if size <= visible { return 0..<max(size, 0) }
    let start = (selected - visible / 2).coerced(0, size - visible)
    return start..<(start + visible)
}

private func rowFor(_ landmark: Landmark, _ userPosition: LatLng?, selected: Bool) -> HudMenuRow {
    var distance: String? = nil
    if let p = userPosition, p.isValid {
        distance = formatDistanceAr(distanceMeters(p, landmark.approachPoint(p)))
    }
    return HudMenuRow(name: landmark.name, distanceText: distance, selected: selected)
}
