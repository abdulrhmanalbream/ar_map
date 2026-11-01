import XCTest
@testable import MutawwifCore

/// The glasses menu state machine: every input source reduces to the same five
/// commands, so this is where the whole eyes-free interaction is verified.
final class HudMenuTests: XCTestCase {

    private let origin = LatLng(24.500000, 39.500000)
    private let far = landmarkAt("Far", 300, 0)
    private let near = landmarkAt("Near", 50, 0)
    private let mid = landmarkAt("Mid", 120, 0)
    private var all: [Landmark] { [far, near, mid] }

    private func selectedIndex(_ s: HudMenuState) -> Int? {
        if case let .destinationMenu(_, sel) = s { return sel }
        return nil
    }

    func testAnyBrowsingCommandOpensTheDestinationMenu() {
        for command in [HudCommand.menu, .next, .prev] {
            XCTAssertNotNil(selectedIndex(advanceHudMenu(.closed, command, all, origin).state), "\(command) should open menu")
        }
    }

    func testMenuItemsComeNearestFirst() {
        guard case let .destinationMenu(items, _) = advanceHudMenu(.closed, .menu, all, origin).state else {
            return XCTFail("expected menu")
        }
        XCTAssertEqual(["Near", "Mid", "Far"], items.map { $0.name })
    }

    func testNoLandmarksMeansTheMenuStaysClosed() {
        XCTAssertEqual(.closed, advanceHudMenu(.closed, .menu, [], origin).state)
    }

    func testConfirmWhileClosedAsksForTheInstructionAgain() {
        let r = advanceHudMenu(.closed, .confirm, all, origin)
        XCTAssertEqual(.closed, r.state)
        XCTAssertEqual(.repeatInstruction, r.action)
    }

    func testNextAndPrevCycleWithWrapAround() {
        var state = HudMenuState.destinationMenu(items: all, selected: 0)
        state = advanceHudMenu(state, .next, all, origin).state
        XCTAssertEqual(1, selectedIndex(state))
        state = advanceHudMenu(state, .next, all, origin).state
        state = advanceHudMenu(state, .next, all, origin).state
        XCTAssertEqual(0, selectedIndex(state))
        state = advanceHudMenu(state, .prev, all, origin).state
        XCTAssertEqual(all.count - 1, selectedIndex(state))
    }

    func testConfirmSelectsTheHighlightedLandmarkAndCloses() {
        let r = advanceHudMenu(.destinationMenu(items: all, selected: 1), .confirm, all, origin)
        XCTAssertEqual(.closed, r.state)
        XCTAssertEqual(.select(landmark: all[1]), r.action)
    }

    func testBackClosesWithoutSelecting() {
        let r = advanceHudMenu(.destinationMenu(items: all, selected: 2), .back, all, origin)
        XCTAssertEqual(.closed, r.state)
        XCTAssertEqual(HudMenuAction.none, r.action)
    }

    func testAmbiguityConfirmPicksTheCandidate() {
        let r = advanceHudMenu(.ambiguityMenu(candidates: [near, mid], selected: 1), .confirm, all, origin)
        XCTAssertEqual(.select(landmark: mid), r.action)
    }

    func testAmbiguityOpensTheMenuOnlyOnTransition() {
        let ambiguous = GuidanceMode.ambiguous(distanceMeters: 10.0, candidates: [near, mid])
        let opened = syncHudMenu(.closed, previous: .noFix, current: ambiguous, landmarks: all, target: near, userPosition: origin)
        if case .ambiguityMenu = opened {} else { XCTFail("expected ambiguity menu, got \(opened)") }
        let again = syncHudMenu(.closed, previous: ambiguous, current: ambiguous, landmarks: all, target: near, userPosition: origin)
        XCTAssertEqual(.closed, again)
    }

    func testAmbiguityMenuClosesItselfOnceResolved() {
        let after = syncHudMenu(
            .ambiguityMenu(candidates: [near, mid], selected: 0),
            previous: .ambiguous(distanceMeters: 10.0, candidates: [near, mid]),
            current: .arrived(distanceMeters: 5.0),
            landmarks: all, target: near, userPosition: origin
        )
        var ok = after == .closed
        if case .nextSuggestion = after { ok = true }
        XCTAssertTrue(ok)
    }

    func testArrivalSuggestsTheNearestOtherLandmark() {
        let state = syncHudMenu(.closed, previous: .arApproach(distanceMeters: 12.0), current: .arrived(distanceMeters: 5.0),
                                landmarks: all, target: near, userPosition: origin)
        guard case let .nextSuggestion(suggestion) = state else { return XCTFail("expected suggestion") }
        XCTAssertEqual("Mid", suggestion.name)
    }

    func testAnOpenMenuIsNeverReplacedBySync() {
        let open = HudMenuState.destinationMenu(items: all, selected: 2)
        let after = syncHudMenu(open, previous: .arApproach(distanceMeters: 12.0), current: .arrived(distanceMeters: 5.0),
                                landmarks: all, target: near, userPosition: origin)
        XCTAssertEqual(open, after)
    }

    func testClosedMenuRendersNothing() {
        XCTAssertNil(hudMenuView(.closed, origin))
    }

    func testRowsCarryDistancesAndExactlyOneSelection() {
        let view = hudMenuView(.destinationMenu(items: [near, mid, far], selected: 1), origin)!
        XCTAssertEqual(3, view.rows.count)
        XCTAssertEqual(1, view.rows.filter { $0.selected }.count)
        XCTAssertTrue(view.rows[1].selected)
        XCTAssertTrue(view.rows[0].distanceText!.hasSuffix(" م"))
    }

    func testLongListsWindowAroundTheSelection() {
        XCTAssertEqual(0..<5, visibleWindow(12, 0))
        XCTAssertEqual(4..<9, visibleWindow(12, 6))
        XCTAssertEqual(7..<12, visibleWindow(12, 11))
        XCTAssertEqual(0..<3, visibleWindow(3, 2))
    }
}
