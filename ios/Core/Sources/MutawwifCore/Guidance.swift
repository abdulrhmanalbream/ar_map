import Foundation

// Decides how to guide the user, based on distance and on whether nearby
// landmarks are far enough apart for GPS to tell them apart.
//
// The campus has both cases:
//  - buildings hundreds of metres away, where the user cannot even see the
//    target and needs a compass bearing
//  - buildings close together, where GPS error (5-10m) is comparable to the
//    gap between them and a naive "nearest wins" would confidently send
//    someone to the wrong door
//
// Treating those the same is what makes campus AR apps feel unreliable, so
// the mode is chosen explicitly and the uncertainty is shown to the user
// rather than hidden.

/// How the UI should currently guide the user.
///
/// Kotlin's sealed interface becomes an enum with associated values; the
/// associated-value labels match the Kotlin data-class property names.
public enum GuidanceMode: Equatable {
    /// Far away: show a compass arrow, bearing and distance.
    case compass(distanceMeters: Double, bearingDegrees: Double, relativeDegrees: Double)

    /// Close enough for AR to be meaningful; ARKit takes over.
    case arApproach(distanceMeters: Double)

    /// Within arrival range, but another landmark is close enough that GPS
    /// cannot reliably distinguish them. The UI must ask the user to confirm
    /// visually rather than assert which building this is.
    case ambiguous(distanceMeters: Double, candidates: [Landmark])

    /// Arrived, and unambiguously so.
    case arrived(distanceMeters: Double)

    /// No usable GPS fix yet.
    case noFix

    /// Distance carried by every mode except `noFix`.
    public var distanceMeters: Double? {
        switch self {
        case let .compass(d, _, _): return d
        case let .arApproach(d): return d
        case let .ambiguous(d, _): return d
        case let .arrived(d): return d
        case .noFix: return nil
        }
    }
}

/// Minimum separation for GPS to distinguish two landmarks with confidence.
///
/// Derived from real GPS behaviour rather than picked arbitrarily: a typical
/// open-sky fix is accurate to 5-10m, and two independent fixes can be wrong
/// in opposite directions. Below ~25m apart, "which building am I at" is not
/// a question GPS alone can answer.
public let GPS_DISTINGUISHABLE_M: Double = 25.0

/// Chooses the guidance mode for `target` given the user's position.
///
/// - Parameter others: every other landmark, used to detect ambiguity
public func guidanceFor(
    userPosition: LatLng,
    userHeadingDeg: Double?,
    target: Landmark,
    others: [Landmark]
) -> GuidanceMode {
    if !userPosition.isValid { return .noFix }

    // Measured to the nearest DOOR. Surveying found the map pin 40-50m from
    // the entrance on three of four colleges, so arrival used to be announced
    // while the user was still walking past a blank wall.
    let approach = target.approachPoint(userPosition)
    let distance = distanceMeters(userPosition, approach)

    if distance > AR_HANDOFF_DISTANCE_M {
        let bearing = bearingDegrees(userPosition, approach)
        let relative = userHeadingDeg.map { relativeBearing($0, bearing) } ?? 0.0
        return .compass(distanceMeters: distance, bearingDegrees: bearing, relativeDegrees: relative)
    }

    if distance > ARRIVAL_DISTANCE_M {
        return .arApproach(distanceMeters: distance)
    }

    // Close enough to have arrived -- but is it unambiguous?
    //
    // The test is whether GPS error could plausibly have put us at the other
    // landmark instead. That means comparing against the neighbour's distance
    // plus our own uncertainty, NOT against the arrival radius: standing
    // exactly at building A with building B 20m away must still warn, because
    // a 7m GPS error makes "which one is this" genuinely unanswerable.
    let confusable = others
        .filter { $0.id != target.id }
        .filter { other in
            let gap = distanceMeters(target.position, other.position)
            if gap >= GPS_DISTINGUISHABLE_M { return false }

            // The neighbour is close enough to the target that GPS cannot
            // separate them. It is only a real risk if the user is also in
            // the vicinity of that pair rather than far from both.
            let userToOther = distanceMeters(userPosition, other.position)
            return userToOther <= gap + GPS_DISTINGUISHABLE_M
        }

    if confusable.isEmpty {
        return .arrived(distanceMeters: distance)
    }
    return .ambiguous(distanceMeters: distance, candidates: [target] + confusable)
}

/// Landmarks that sit too close together to be told apart by GPS.
///
/// Surfaced during a survey so the surveyor learns immediately that two
/// entrances need photos distinctive enough to tell them apart, rather than
/// discovering the problem when users get lost.
public func findAmbiguousPairs(_ landmarks: [Landmark]) -> [(Landmark, Landmark)] {
    var pairs: [(Landmark, Landmark)] = []
    for i in landmarks.indices {
        var j = i + 1
        while j < landmarks.count {
            let d = distanceMeters(landmarks[i].position, landmarks[j].position)
            if d < GPS_DISTINGUISHABLE_M {
                pairs.append((landmarks[i], landmarks[j]))
            }
            j += 1
        }
    }
    return pairs
}

/// A short instruction for the current mode, in Arabic.
///
/// Kept here rather than in the UI so it is unit-testable and consistent
/// across the AR view, the map and the voice output.
public func instructionAr(_ mode: GuidanceMode, _ targetName: String) -> String {
    switch mode {
    case .noFix:
        return "جاري تحديد موقعك…"

    case let .compass(distanceMeters, _, rel):
        let dist = formatDistanceAr(distanceMeters)
        if abs(rel) <= 15 { return "امشِ للأمام · \(dist)" }
        if rel > 125 || rel < -125 { return "استدر للخلف · \(dist)" }
        if rel > 55 { return "اتجه يميناً · \(dist)" }
        if rel < -55 { return "اتجه يساراً · \(dist)" }
        if rel > 0 { return "مِل يميناً قليلاً · \(dist)" }
        return "مِل يساراً قليلاً · \(dist)"

    case let .arApproach(d):
        return "\(targetName) أمامك · \(formatDistanceAr(d))"

    case .ambiguous:
        return "أنت قريب من عدة مبانٍ — تأكد من الصورة"

    case .arrived:
        return "وصلت إلى \(targetName)"
    }
}

public func instructionAr(mode: GuidanceMode, targetName: String) -> String { instructionAr(mode, targetName) }

/// Arabic distance formatting: "١٢ م" style but with ASCII digits.
public func formatDistanceAr(_ meters: Double) -> String {
    if meters < 1000 { return "\(kToInt(meters)) م" }
    return formatFixed("%.1f كم", meters / 1000.0)
}
