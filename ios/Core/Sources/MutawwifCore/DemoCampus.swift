import Foundation

/// Builds a fake campus around wherever the user happens to be standing.
///
/// Exists so the whole system can be seen and judged away from the real
/// campus: the arrow, the floating markers, the distance countdown, the map
/// and — importantly — the ambiguity prompt.
///
/// The distances are not arbitrary. They mirror the real campus (a 20m pair
/// and landmarks around 250m away), so what is seen in demo mode is what will
/// actually happen on site.
public enum DemoCampus {

    /// Offsets in metres (north, east) from the user's position.
    private struct Offset {
        let north: Double
        let east: Double
        let name: String
        let category: LandmarkCategory
        let detail: String
    }

    private static let LAYOUT: [Offset] = [
        // Far landmark, straight ahead (north). Tests the distant AR marker
        // and the "walk forward" instruction.
        Offset(
            north: 250.0, east: 0.0,
            name: "الملعب",
            category: .sports,
            detail: "معلم بعيد لاختبار العلامة الطافية والسهم على مسافة 250 متراً."
        ),

        // The 20m pair. This is the important one: it reproduces the case
        // where GPS genuinely cannot say which building you are at.
        Offset(
            north: 18.0, east: 0.0,
            name: "كلية الحاسب الآلي",
            category: .faculty,
            detail: "أحد مبنيين متقاربين (20 متراً) لاختبار سؤال التأكيد."
        ),
        Offset(
            north: 18.0, east: 20.0,
            name: "كلية الشريعة",
            category: .faculty,
            detail: "المبنى الثاني من الزوج المتقارب — يبعد 20 متراً عن الأول."
        ),

        // Mid-range, off to one side. Tests the off-screen indicator and the
        // GPS -> AR handoff at 30m.
        Offset(
            north: -40.0, east: 60.0,
            name: "سكن طلاب - وحدة 13",
            category: .housing,
            detail: "معلم متوسط المسافة وخلف المستخدم — لاختبار مؤشر خارج الشاشة."
        ),
    ]

    /// Generates the demo landmarks around `centre`.
    ///
    /// - Parameter headingDeg: rotates the whole layout so "الملعب" sits
    ///   straight ahead of wherever the user is currently facing. Without this
    ///   the far landmark could start behind them, which makes the first
    ///   impression confusing rather than illustrative.
    public static func generate(_ centre: LatLng, headingDeg: Double = 0.0) -> [Landmark] {
        if !centre.isValid { return [] }

        let metresPerDegLat = 111_195.0
        let metresPerDegLon = 111_195.0 * cos(toRadians(centre.latitude))
        let rad = toRadians(headingDeg)

        return LAYOUT.enumerated().map { pair -> Landmark in
            let index = pair.offset
            let o = pair.element
            // Rotate the offset into the user's facing direction.
            let north = o.north * cos(rad) - o.east * sin(rad)
            let east = o.north * sin(rad) + o.east * cos(rad)

            let position = LatLng(
                latitude: centre.latitude + north / metresPerDegLat,
                longitude: centre.longitude + east / metresPerDegLon
            )

            return Landmark(
                id: "demo-\(index)",
                name: o.name,
                category: o.category,
                position: position,
                detail: o.detail,
                hours: "وضع تجريبي",
                amenities: ["تجريبي"],
                capturedAccuracyM: 5
            )
        }
    }

    /// True when a landmark came from demo mode rather than a real survey.
    public static func isDemo(_ landmark: Landmark) -> Bool { landmark.id.hasPrefix("demo-") }
}

/// Moves a simulated position forward, for testing without walking.
///
/// Lets the distance count down and the guidance change mode (compass -> AR
/// approach -> arrived) while sitting still, which is the only practical way
/// to check those transitions away from the campus.
public func stepTowards(_ from: LatLng, _ to: LatLng, _ metres: Double) -> LatLng {
    if !from.isValid || !to.isValid { return from }

    let remaining = distanceMeters(from, to)
    if remaining <= 0.5 { return to }

    let fraction = (metres / remaining).coerced(0.0, 1.0)
    return LatLng(
        latitude: from.latitude + (to.latitude - from.latitude) * fraction,
        longitude: from.longitude + (to.longitude - from.longitude) * fraction
    )
}

/// Moves a simulated position along a bearing, for free-roam testing.
public func stepAlongBearing(_ from: LatLng, _ bearingDeg: Double, _ metres: Double) -> LatLng {
    if !from.isValid { return from }

    let metresPerDegLat = 111_195.0
    let metresPerDegLon = 111_195.0 * cos(toRadians(from.latitude))
    let rad = toRadians(bearingDeg)

    return LatLng(
        latitude: from.latitude + (metres * cos(rad)) / metresPerDegLat,
        longitude: from.longitude + (metres * sin(rad)) / metresPerDegLon
    )
}
