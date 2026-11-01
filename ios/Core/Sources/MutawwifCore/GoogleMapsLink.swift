import Foundation

// Extracts coordinates from a pasted Google Maps link.
//
// Pasting a link is far more reliable than typing latitude and longitude by
// hand, and it lets places be added from a laptop without standing at the door.
//
// ## The ordering trap
//
// Google writes coordinates as **(lat, lng)** while GeoJSON, MapLibre and the
// web project's storage all use **[lng, lat]**. Reversing them silently puts
// a Saudi campus in the Indian Ocean, which is why the parser returns a typed
// `LatLng` rather than a bare pair.
//
// ## Short links
//
// `maps.app.goo.gl` links carry no coordinates at all -- they must be
// expanded by the server first. That needs the network, so it is reported
// honestly via `isShortLink` instead of failing with a confusing message.

/// Result of parsing a pasted link.
public enum MapsLinkResult: Equatable {
    case found(position: LatLng)
    /// A short link that must be expanded online before it can be parsed.
    case needsNetwork
    case notFound(reason: String)
}

/// True for links that redirect and therefore carry no coordinates inline.
public func isShortLink(_ raw: String) -> Bool {
    let s = raw.lowercased()
    return s.contains("maps.app.goo.gl") || s.contains("goo.gl/maps") || s.contains("g.co/kgs")
}

// `[0-9]` rather than `\d`: Java's \d is ASCII-only, NSRegularExpression's is
// all Unicode digits, and Double() could not parse the latter anyway.
private let NUM = "(-?[0-9]+\\.?[0-9]*)"

/// Tries every coordinate format Google Maps produces, most precise first.
///
/// The `!3d<lat>!4d<lng>` form is preferred because it is the actual pin,
/// whereas `@lat,lng` is only where the camera happened to be pointing and can
/// be tens of metres off.
public func parseGoogleMapsLink(_ raw: String) -> MapsLinkResult {
    let input = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    if input.isEmpty { return .notFound(reason: "الرابط فارغ") }

    // 1. The exact dropped pin. Most accurate; try it first.
    if let g = firstGroups("!3d\(NUM)!4d\(NUM)", input), let r = toResult(g.0, g.1) { return r }

    // 2. Query parameters that carry an explicit coordinate.
    for key in ["q", "query", "ll", "destination", "center", "daddr", "saddr"] {
        if let g = firstGroups("[?&]\(key)=(?:loc:)?\(NUM),\(NUM)", input, caseInsensitive: true),
           let r = toResult(g.0, g.1) { return r }
    }

    // 3. The camera position. Less precise: this is the map centre, not the
    //    pin, so it is only used when nothing better is present.
    if let g = firstGroups("@\(NUM),\(NUM)", input), let r = toResult(g.0, g.1) { return r }

    // 4. Coordinates embedded in the path, e.g. /place/24.46,39.61
    if let g = firstGroups("/(?:place|search|dir)/\(NUM),\(NUM)", input), let r = toResult(g.0, g.1) { return r }

    // 5. A bare pasted pair, which is what people copy out of the app itself.
    if let g = firstGroups("^\\s*\(NUM)\\s*,\\s*\(NUM)\\s*$", input), let r = toResult(g.0, g.1) { return r }

    // Only now report the short-link case: a short link that happened to
    // include coordinates should still be parsed rather than rejected.
    if isShortLink(input) { return .needsNetwork }

    return .notFound(reason: "لم يتم العثور على إحداثيات في الرابط")
}

/// First match's two capture groups (Kotlin `Regex.find(...).groupValues[1..2]`).
private func firstGroups(_ pattern: String, _ input: String, caseInsensitive: Bool = false) -> (String, String)? {
    guard let re = try? NSRegularExpression(pattern: pattern, options: caseInsensitive ? [.caseInsensitive] : []) else {
        return nil
    }
    let ns = input as NSString
    guard let m = re.firstMatch(in: input, options: [], range: NSRange(location: 0, length: ns.length)),
          m.numberOfRanges >= 3 else { return nil }
    let r1 = m.range(at: 1)
    let r2 = m.range(at: 2)
    if r1.location == NSNotFound || r2.location == NSNotFound { return nil }
    return (ns.substring(with: r1), ns.substring(with: r2))
}

/// Validates and packages a parsed pair.
///
/// Range-checked, so a mistyped or mismatched pair is rejected rather than
/// quietly placing a landmark somewhere impossible.
private func toResult(_ latText: String, _ lngText: String) -> MapsLinkResult? {
    guard let lat = Double(latText), let lng = Double(lngText) else { return nil }
    let position = LatLng(lat, lng)
    return position.isValid ? .found(position: position) : nil
}

/// Builds a Google Maps link for a position, for sharing a place onward.
public func googleMapsLinkFor(_ position: LatLng) -> String {
    return "https://www.google.com/maps/search/?api=1&query=" +
        "\(position.latitude),\(position.longitude)"
}
