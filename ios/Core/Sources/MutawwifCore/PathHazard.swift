import Foundation

/// What the drawn path should say about the stretch ahead.
///
/// Colour is the fastest channel available while someone is walking and only
/// glancing at the screen, and road signage has already trained everyone what
/// these three mean. Using that vocabulary costs nothing and needs no legend.
public enum PathHazard: String, CaseIterable {
    /// Continue as you are.
    case clear = "CLEAR"
    /// A turn is coming up within the visible stretch.
    case turn = "TURN"
    /// Stairs. Blocks wheels entirely and matters for accessibility.
    case stairs = "STAIRS"

    /// RGBA for the ribbon.
    public var ribbonColour: [Float] {
        switch self {
        case .clear: return [1.00, 0.84, 0.10, 0.92]   // yellow
        case .turn: return [1.00, 0.55, 0.05, 0.94]    // orange
        case .stairs: return [0.95, 0.24, 0.20, 0.95]  // red
        }
    }

    /// RGBA for the chevrons.
    ///
    /// Kept brighter than the ribbon so the arrows stay legible on top of it in
    /// direct sun, which is the condition this is actually used in.
    public var arrowColour: [Float] {
        switch self {
        case .clear: return [1.00, 0.97, 0.75, 0.98]
        case .turn: return [1.00, 0.80, 0.45, 0.98]
        case .stairs: return [1.00, 0.72, 0.68, 0.98]
        }
    }

    /// Warning to show on screen, or nil when there is nothing to say.
    public var warningAr: String? {
        switch self {
        case .clear, .turn: return nil
        case .stairs: return "درج في الطريق — غير مناسب للعجلات"
        }
    }
}

/// Turn sharper than this counts as a turn worth colouring for.
let TURN_THRESHOLD_DEG: Double = 30.0

/// Decides what the visible stretch of path should be coloured.
///
/// Stairs win over everything: a turn is a manoeuvre, stairs are a barrier, and
/// someone pushing a wheelchair needs to know before they commit to the ramp.
///
/// - Parameters:
///   - groundPath: the path as drawn, in camera-relative metres
///   - hasStairsAhead: whether the route crosses a stepped edge in this stretch
public func pathHazardFor(_ groundPath: [Vec3], _ hasStairsAhead: Bool) -> PathHazard {
    if hasStairsAhead { return .stairs }
    if groundPathTurnDegrees(groundPath) >= TURN_THRESHOLD_DEG { return .turn }
    return .clear
}

/// Whether the route reaches a stepped edge within `withinMeters`.
///
/// Only the stretch actually being walked matters. Colouring the whole path red
/// because of stairs 200m away would make the warning meaningless long before
/// anyone reached them.
public func stairsAhead(
    _ route: Route?,
    _ network: PathNetwork,
    _ from: LatLng,
    withinMeters: Double = 30.0
) -> Bool {
    guard let route = route, from.isValid else { return false }

    var travelled = 0.0
    var previous: LatLng? = nil

    for nodeId in route.nodeIds {
        guard let node = network.node(nodeId) else { continue }
        if let p = previous { travelled += distanceMeters(p, node.position) }
        previous = node.position
        if travelled > withinMeters { return false }

        // Any stepped edge leaving this node counts: the route may take it.
        let stepped = network.edgesFrom(nodeId).contains { e in
            e.hasStairs && route.nodeIds.contains(network.otherEnd(e, nodeId))
        }
        if stepped { return true }
    }
    return false
}
