import Foundation

// The horizontal compass ribbon across the top of the camera view.
//
// Shows the cardinal points at their true angular positions and slides as the
// user turns, with the destination pinned to its real bearing. It is the
// cheapest way to answer "which way am I facing and where is my target"
// without covering the camera with a map.
//
// Pure Swift so the angular maths is unit-tested rather than eyeballed on a
// phone -- a sign error here points every tick the wrong way while still
// looking like a plausible compass.

/// One mark on the ribbon.
public struct CompassTick: Equatable {
    /// Bearing this tick represents, degrees from true north.
    public var bearingDeg: Double
    /// Position across the strip: -1 = left edge, 0 = centre, +1 = right.
    public var x: Float
    public var label: String
    public var isCardinal: Bool

    public init(bearingDeg: Double, x: Float, label: String, isCardinal: Bool) {
        self.bearingDeg = bearingDeg
        self.x = x
        self.label = label
        self.isCardinal = isCardinal
    }
}

/// The destination marker on the ribbon.
public struct CompassTarget: Equatable {
    public var x: Float
    /// True when the target lies within the strip's span.
    public var visible: Bool
    /// -1 = off to the left, +1 = off to the right. Only when not visible.
    public var offScreenDirection: Int
    public var distanceMeters: Double

    public init(x: Float, visible: Bool, offScreenDirection: Int, distanceMeters: Double) {
        self.x = x
        self.visible = visible
        self.offScreenDirection = offScreenDirection
        self.distanceMeters = distanceMeters
    }
}

/// Arabic cardinal labels, in the order the ribbon walks clockwise.
/// (The joiner between letters is U+200C ZERO WIDTH NON-JOINER, as on Android.)
let CARDINALS: [(Double, String)] = [
    (0.0, "ش"),            // شمال
    (45.0, "ش\u{200C}ق"),  // شمال شرق
    (90.0, "ق"),           // شرق
    (135.0, "ج\u{200C}ق"), // جنوب شرق
    (180.0, "ج"),          // جنوب
    (225.0, "ج\u{200C}غ"), // جنوب غرب
    (270.0, "غ"),          // غرب
    (315.0, "ش\u{200C}غ"), // شمال غرب
]

/// How many degrees the strip spans end to end.
///
/// Deliberately wider than the camera's ~65 degree field of view: a strip that
/// matched the lens exactly would show barely two cardinal points, making it
/// useless for orienting yourself. 140 degrees keeps three or four in view
/// while still moving in step with the world.
public let COMPASS_STRIP_SPAN_DEG: Double = 140.0

/// Builds the visible ticks for the current heading.
///
/// Ticks are emitted every `stepDeg` degrees, with cardinal points labelled.
public func compassTicks(
    headingDeg: Double,
    stepDeg: Double = 15.0,
    spanDeg: Double = COMPASS_STRIP_SPAN_DEG
) -> [CompassTick] {
    if stepDeg <= 0 || spanDeg <= 0 { return [] }

    let half = spanDeg / 2.0
    var ticks: [CompassTick] = []

    // Walk the whole circle and keep whatever falls inside the span. Cheap
    // (24 iterations at the default step) and avoids wrap-around bugs that
    // creep in when trying to iterate only the visible arc.
    var bearing = 0.0
    while bearing < 360.0 {
        let delta = relativeBearing(headingDeg, bearing)
        if abs(delta) <= half {
            let b = bearing
            let cardinal = CARDINALS.first(where: { abs(relativeBearing($0.0, b)) < 0.01 })
            ticks.append(
                CompassTick(
                    bearingDeg: bearing,
                    x: Float(delta / half),
                    label: cardinal?.1 ?? "",
                    isCardinal: cardinal != nil
                )
            )
        }
        bearing += stepDeg
    }
    return ticks.enumerated()
        .sorted { $0.element.x != $1.element.x ? $0.element.x < $1.element.x : $0.offset < $1.offset }
        .map { $0.element }
}

/// Places the destination on the ribbon.
///
/// Returns an off-screen direction when the target is behind the span, so the
/// UI can show an arrow at the edge rather than silently dropping the marker.
public func compassTarget(
    headingDeg: Double,
    targetBearingDeg: Double,
    distanceMeters: Double,
    spanDeg: Double = COMPASS_STRIP_SPAN_DEG
) -> CompassTarget {
    let half = spanDeg / 2.0
    let delta = relativeBearing(headingDeg, targetBearingDeg)
    let within = abs(delta) <= half

    return CompassTarget(
        // Clamp so an off-screen target parks at the edge instead of being
        // drawn far outside the strip.
        x: Float(delta / half).coerced(-1, 1),
        visible: within,
        offScreenDirection: delta >= 0 ? 1 : -1,
        distanceMeters: distanceMeters
    )
}

/// How the user is travelling. Affects which paths are routable and the
/// speed used for time estimates. Raw value is the Kotlin enum name.
public enum TravelMode: String, CaseIterable, Codable {
    case walk = "WALK"
    case bike = "BIKE"
    case car = "CAR"

    public var labelAr: String {
        switch self {
        case .walk: return "مشي"
        case .bike: return "دراجة"
        case .car: return "سيارة"
        }
    }

    /// Typical speed in metres per second, for time estimates.
    public var speedMps: Double {
        switch self {
        case .walk: return 1.3
        case .bike: return 4.2
        case .car: return 8.3
        }
    }

    public var name: String { rawValue }

    /// Whether this mode may use a path with the given permissions.
    ///
    /// Bicycles are the interesting case and the reason this is not a simple
    /// equality check: they are allowed on both roads and pedestrian paths,
    /// which the user called out explicitly.
    public func canUse(_ allowsFoot: Bool, _ allowsBike: Bool, _ allowsCar: Bool) -> Bool {
        switch self {
        case .walk: return allowsFoot
        case .bike: return allowsBike
        case .car: return allowsCar
        }
    }
}

/// Estimated travel time in minutes, never below one.
public func travelMinutes(_ distanceMeters: Double, _ mode: TravelMode) -> Int {
    return max(1, kToInt((distanceMeters / mode.speedMps / 60.0).rounded(.up)))
}
