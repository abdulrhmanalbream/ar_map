import Foundation
@testable import MutawwifCore

// Case predicates so ported assertions stay one-liners like Kotlin's `is` checks.
extension GuidanceMode {
    var isNoFix: Bool { if case .noFix = self { return true }; return false }
    var isCompass: Bool { if case .compass = self { return true }; return false }
    var isArApproach: Bool { if case .arApproach = self { return true }; return false }
    var isAmbiguous: Bool { if case .ambiguous = self { return true }; return false }
    var isArrived: Bool { if case .arrived = self { return true }; return false }

    var relativeDegrees: Double? {
        if case let .compass(_, _, r) = self { return r }
        return nil
    }

    var bearingDegrees: Double? {
        if case let .compass(_, b, _) = self { return b }
        return nil
    }

    var candidates: [Landmark] {
        if case let .ambiguous(_, c) = self { return c }
        return []
    }
}

/// A landmark `northMeters` north and `eastMeters` east of `origin`.
func landmarkAt(
    _ name: String,
    _ northMeters: Double,
    _ eastMeters: Double,
    origin: LatLng = LatLng(24.500000, 39.500000),
    category: LandmarkCategory = .faculty
) -> Landmark {
    let dLat = northMeters / 111_195.0
    let dLon = eastMeters / (111_195.0 * cos(origin.latitude * Double.pi / 180.0))
    return Landmark(
        id: name,
        name: name,
        category: category,
        position: LatLng(origin.latitude + dLat, origin.longitude + dLon)
    )
}
