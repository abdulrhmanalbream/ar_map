import Foundation

// Offline geographic maths for campus-scale navigation.
//
// No platform imports, so this is unit-testable anywhere and shared with the
// Android build's logic line for line.
//
// ## Why GPS at all?
//
// AR motion tracking drifts roughly a metre every 10-20m walked. On a campus
// with 100-300m between buildings that error compounds until the path is
// drawn in the wrong place entirely. GPS gives an absolute fix that never
// drifts, so we use GPS for the long haul and AR only for the last few
// metres, where its precision actually beats GPS.
//
// GPS itself needs no internet: the satellites broadcast everything required.
// Only the *time to first fix* improves with a network (assisted GPS).

/// Mean Earth radius in metres (WGS-84 authalic radius).
let EARTH_RADIUS_M: Double = 6_371_008.8

/// A WGS-84 coordinate.
public struct LatLng: Equatable, Hashable, Codable {
    public var latitude: Double
    public var longitude: Double

    public init(_ latitude: Double, _ longitude: Double) {
        self.latitude = latitude
        self.longitude = longitude
    }

    public init(latitude: Double, longitude: Double) {
        self.latitude = latitude
        self.longitude = longitude
    }

    /// True when the value is a plausible coordinate (guards bad input).
    public var isValid: Bool {
        return (-90.0...90.0).contains(latitude) &&
            (-180.0...180.0).contains(longitude) &&
            !(latitude == 0.0 && longitude == 0.0) // null island == no fix
    }
}

/// Great-circle distance between two points, in metres (haversine).
///
/// Accurate to well under a metre at campus scale, which is far below GPS's
/// own error, so the simpler formula is the right choice over Vincenty.
public func distanceMeters(_ from: LatLng, _ to: LatLng) -> Double {
    let dLat = toRadians(to.latitude - from.latitude)
    let dLon = toRadians(to.longitude - from.longitude)
    let lat1 = toRadians(from.latitude)
    let lat2 = toRadians(to.latitude)

    let sLat = sin(dLat / 2)
    let sLon = sin(dLon / 2)
    let a = sLat * sLat + cos(lat1) * cos(lat2) * sLon * sLon
    return 2 * EARTH_RADIUS_M * asin(a.coerced(0.0, 1.0).squareRoot())
}

public func distanceMeters(from: LatLng, to: LatLng) -> Double { distanceMeters(from, to) }

/// Initial bearing from `from` to `to`, in degrees clockwise from true north
/// (0 = north, 90 = east). Always normalised to [0, 360).
public func bearingDegrees(_ from: LatLng, _ to: LatLng) -> Double {
    let lat1 = toRadians(from.latitude)
    let lat2 = toRadians(to.latitude)
    let dLon = toRadians(to.longitude - from.longitude)

    let y = sin(dLon) * cos(lat2)
    let x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return kRem(toDegrees(atan2(y, x)) + 360.0, 360.0)
}

public func bearingDegrees(from: LatLng, to: LatLng) -> Double { bearingDegrees(from, to) }

/// Smallest signed angle from `fromDeg` to `toDeg`, in (-180, 180].
///
/// This is what drives the on-screen arrow: negative means turn left,
/// positive means turn right. Naively subtracting the two would make a
/// 350 deg -> 10 deg turn look like a 340 deg spin instead of 20 deg right.
public func relativeBearing(_ fromDeg: Double, _ toDeg: Double) -> Double {
    var diff = kRem(toDeg - fromDeg, 360.0)
    if diff > 180.0 { diff -= 360.0 }
    if diff <= -180.0 { diff += 360.0 }
    return diff
}

public func relativeBearing(fromDeg: Double, toDeg: Double) -> Double { relativeBearing(fromDeg, toDeg) }

/// Converts a geographic offset into local metres east/north of `origin`.
///
/// Uses an equirectangular approximation, which is entirely adequate over a
/// few kilometres and is what lets us place AR content and draw the 2D map
/// with plain 2D maths. Returns (east, north) -- Kotlin's Pair(first, second).
public func toLocalMeters(_ origin: LatLng, _ point: LatLng) -> (Double, Double) {
    let meanLat = toRadians((origin.latitude + point.latitude) / 2.0)
    let east = toRadians(point.longitude - origin.longitude) * cos(meanLat) * EARTH_RADIUS_M
    let north = toRadians(point.latitude - origin.latitude) * EARTH_RADIUS_M
    return (east, north)
}

public func toLocalMeters(origin: LatLng, point: LatLng) -> (Double, Double) { toLocalMeters(origin, point) }

/// Human-friendly distance: "12 m", "450 m", "1.2 km".
public func formatDistance(_ meters: Double) -> String {
    if meters < 1000 { return "\(kToInt(meters)) m" }
    return formatFixed("%.1f km", meters / 1000.0)
}

/// Rough walking time at ~1.3 m/s, never less than one minute.
public func walkMinutes(_ meters: Double) -> Int {
    return max(1, kToInt((meters / 1.3 / 60.0).rounded(.up)))
}

/// The eight compass points, for spoken-style guidance.
public func compassPoint(_ bearingDeg: Double) -> String {
    let names = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"]
    let normalised = kRem(kRem(bearingDeg, 360.0) + 360.0, 360.0) / 45.0
    let idx = kRound(normalised) % 8
    return names[idx]
}

/// Turn instruction from a relative bearing, e.g. "Turn right", "Ahead".
///
/// The dead-ahead band is deliberately wide (30 deg): GPS heading is noisy,
/// and a narrow band makes the instruction flicker between "ahead" and
/// "slight left" while the user walks in a straight line.
public func turnInstruction(_ relativeDeg: Double) -> String {
    let a = relativeDeg
    if abs(a) <= 15 { return "Straight ahead" }
    if abs(a) <= 55 { return a > 0 ? "Slightly right" : "Slightly left" }
    if abs(a) <= 125 { return a > 0 ? "Turn right" : "Turn left" }
    return "Turn around"
}

/// Averages several GPS fixes into one, weighting by accuracy.
///
/// Used when capturing a landmark: a single fix can be several metres out,
/// but the error is largely random, so a weighted mean of a handful of
/// samples is markedly better. Returns nil if nothing usable was supplied.
public func averageFixes(_ fixes: [GpsFix]) -> LatLng? {
    let usable = fixes.filter { $0.position.isValid && $0.accuracyMeters > 0 }
    if usable.isEmpty { return nil }

    // Weight by 1/accuracy^2: a 3m fix should count far more than a 15m one.
    var sumW = 0.0
    var sumLat = 0.0
    var sumLon = 0.0
    for f in usable {
        let acc = Double(f.accuracyMeters)
        let w = 1.0 / (acc * acc)
        sumW += w
        sumLat += f.position.latitude * w
        sumLon += f.position.longitude * w
    }
    if sumW <= 0.0 { return nil }
    return LatLng(sumLat / sumW, sumLon / sumW)
}

/// A single GPS reading with its reported accuracy.
public struct GpsFix: Equatable, Hashable, Codable {
    public var position: LatLng
    public var accuracyMeters: Float
    public var timestampMs: Int64

    public init(position: LatLng, accuracyMeters: Float, timestampMs: Int64) {
        self.position = position
        self.accuracyMeters = accuracyMeters
        self.timestampMs = timestampMs
    }
}
