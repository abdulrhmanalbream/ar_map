import Foundation

/// A campus landmark anchored to real-world coordinates.
///
/// Unlike the V2 `Destination`, which held a route in metres relative to a
/// printed marker, a landmark is defined by its GPS position. That is what
/// makes campus-scale navigation possible: there is no accumulated drift, and
/// the user can start from anywhere rather than from one specific board.
public struct Landmark: Equatable, Hashable, Identifiable {
    public var id: String
    public var name: String
    public var category: LandmarkCategory
    public var position: LatLng
    /// Free text shown on the detail card.
    public var detail: String
    /// Opening hours, if relevant.
    public var hours: String
    /// Facility chips (step-free, wifi, ...).
    public var amenities: [String]
    /// Photos of this landmark, stored in the app's photo directory.
    ///
    /// Several shots from different angles and distances are expected: they
    /// help the user recognise the building on arrival, and give the optional
    /// visual-recognition layer more chances to match. The first entry is
    /// treated as the primary (entrance) shot.
    public var photos: [LandmarkPhoto]
    /// Accuracy of the fix this landmark was captured with, in metres.
    public var capturedAccuracyM: Float
    /// True when the coordinate was placed by hand on the map instead of
    /// measured by standing there.
    ///
    /// Worth recording rather than silently blending the two: a hand-placed
    /// point is only as good as the satellite imagery it was dropped on, and
    /// when a route later sends someone to the wrong door this is the first
    /// thing anyone needs to know.
    public var placedManually: Bool
    /// The text on this building's entrance plaque, as it actually appears.
    ///
    /// This is what makes the buildings distinguishable. On this campus the
    /// facades are near-identical sandstone with the same pointed arches, and
    /// no amount of image similarity will separate them -- but every entrance
    /// carries a high-contrast plaque naming the college in Arabic and English,
    /// mounted in the same place on every building.
    ///
    /// Stored verbatim, misspellings included: OCR reads what is on the wall,
    /// not what should have been written there.
    public var signTexts: [String]
    /// The doors, where known.
    ///
    /// A building is not a point. Surveying showed the map pin sitting 40-50m
    /// from the actual entrance on three of four colleges, which meant the app
    /// announced arrival while the user was still walking past a blank wall,
    /// and a second entrance on the far side went unrecognised entirely.
    ///
    /// Routing and arrival both work against the nearest of these when any
    /// exist. Empty is fine and falls back to `position`.
    public var entrances: [Entrance]

    public init(
        id: String,
        name: String,
        category: LandmarkCategory,
        position: LatLng,
        detail: String = "",
        hours: String = "",
        amenities: [String] = [],
        photos: [LandmarkPhoto] = [],
        capturedAccuracyM: Float = 0,
        placedManually: Bool = false,
        signTexts: [String] = [],
        entrances: [Entrance] = []
    ) {
        self.id = id
        self.name = name
        self.category = category
        self.position = position
        self.detail = detail
        self.hours = hours
        self.amenities = amenities
        self.photos = photos
        self.capturedAccuracyM = capturedAccuracyM
        self.placedManually = placedManually
        self.signTexts = signTexts
        self.entrances = entrances
    }

    /// Primary photo, used for list thumbnails and the detail card header.
    public var primaryPhoto: LandmarkPhoto? {
        return photos.first(where: { $0.viewpoint == .entrance }) ?? photos.first
    }

    /// The nearest door to `from`, or nil when none are recorded.
    public func nearestEntrance(_ from: LatLng) -> Entrance? {
        if !from.isValid { return entrances.first }
        return entrances
            .filter { $0.position.isValid }
            .min(by: { distanceMeters(from, $0.position) < distanceMeters(from, $1.position) })
    }

    /// Where to actually send someone standing at `from`.
    ///
    /// The centre of a building is never the answer: you cannot walk into a
    /// wall. This is the point every distance, bearing and route should be
    /// computed against.
    public func approachPoint(_ from: LatLng) -> LatLng {
        return nearestEntrance(from)?.position ?? position
    }
}

/// A door into a building.
///
/// Named because "you have arrived" is far more useful when it can say WHICH
/// way in -- these buildings have entrances on opposite sides and arriving at
/// the wrong one means walking the length of the block.
public struct Entrance: Equatable, Hashable, Codable, Identifiable {
    public var id: String
    public var nameAr: String
    public var position: LatLng

    public init(id: String, nameAr: String, position: LatLng) {
        self.id = id
        self.nameAr = nameAr
        self.position = position
    }
}

/// One photo of a landmark, tagged with where it was taken from.
///
/// The viewpoint is not decoration: on arrival the app can show the shot that
/// matches the direction the user is approaching from, which is far more
/// useful for recognising a building than a single canonical photo.
public struct LandmarkPhoto: Equatable, Hashable, Codable {
    public var file: String
    public var viewpoint: Viewpoint
    /// Where the photographer stood, if known. Enables angle matching.
    public var takenFrom: LatLng?
    /// Compass bearing the camera faced, in degrees from true north.
    public var bearingDegrees: Double?
    /// Roughly how far the photographer was, in metres.
    public var distanceMeters: Double?

    public init(
        file: String,
        viewpoint: Viewpoint,
        takenFrom: LatLng? = nil,
        bearingDegrees: Double? = nil,
        distanceMeters: Double? = nil
    ) {
        self.file = file
        self.viewpoint = viewpoint
        self.takenFrom = takenFrom
        self.bearingDegrees = bearingDegrees
        self.distanceMeters = distanceMeters
    }
}

/// Where a landmark photo was taken from. Raw value is the Kotlin enum name,
/// which is what the stored JSON uses.
public enum Viewpoint: String, CaseIterable, Codable {
    /// Close up at the door -- the shot that confirms "this is the way in".
    case entrance = "ENTRANCE"
    /// From one side, showing the building's shape.
    case side = "SIDE"
    /// From a distance, showing the building in its surroundings.
    case far = "FAR"
    case other = "OTHER"

    public var label: String {
        switch self {
        case .entrance: return "Entrance"
        case .side: return "Side view"
        case .far: return "From afar"
        case .other: return "Other"
        }
    }

    /// Kotlin `Enum.name`.
    public var name: String { rawValue }
}

/// Landmark type. Drives the marker colour and icon letter, and lets the user
/// filter the list. Raw value is the Kotlin enum name (stored in JSON).
public enum LandmarkCategory: String, CaseIterable, Codable {
    case housing = "HOUSING"
    case sports = "SPORTS"
    case faculty = "FACULTY"
    case services = "SERVICES"
    case gate = "GATE"
    case other = "OTHER"

    public var label: String {
        switch self {
        case .housing: return "Housing"
        case .sports: return "Sports"
        case .faculty: return "Faculty"
        case .services: return "Services"
        case .gate: return "Gate"
        case .other: return "Other"
        }
    }

    public var labelAr: String {
        switch self {
        case .housing: return "سكن"
        case .sports: return "ملاعب"
        case .faculty: return "كلية"
        case .services: return "خدمات"
        case .gate: return "بوابة"
        case .other: return "أخرى"
        }
    }

    public var letter: String {
        switch self {
        case .housing: return "H"
        case .sports: return "S"
        case .faculty: return "F"
        case .services: return "V"
        case .gate: return "G"
        case .other: return "O"
        }
    }

    /// Kotlin `Enum.name`.
    public var name: String { rawValue }

    public static func fromName(_ name: String) -> LandmarkCategory {
        return LandmarkCategory(rawValue: name) ?? .other
    }
}

/// A landmark paired with its live distance and bearing from the user.
///
/// Computed fresh on each GPS update rather than stored, so the UI can sort
/// by "nearest" without the list going stale as the user walks.
public struct LandmarkFix: Equatable, Hashable {
    public var landmark: Landmark
    public var distanceMeters: Double
    public var bearingDegrees: Double

    public init(landmark: Landmark, distanceMeters: Double, bearingDegrees: Double) {
        self.landmark = landmark
        self.distanceMeters = distanceMeters
        self.bearingDegrees = bearingDegrees
    }
}

/// Sorts landmarks by distance from `from`, attaching distance and bearing.
public func rankByDistance(_ landmarks: [Landmark], _ from: LatLng) -> [LandmarkFix] {
    if !from.isValid {
        // No fix yet: still return everything so the list is browsable,
        // just without meaningful distances.
        return landmarks.map { LandmarkFix(landmark: $0, distanceMeters: Double.nan, bearingDegrees: Double.nan) }
    }
    let fixes = landmarks.map { lm -> LandmarkFix in
        // To the door, so "nearest" means nearest to walk to rather than
        // nearest as the crow flies to a point inside a wall.
        let approach = lm.approachPoint(from)
        return LandmarkFix(
            landmark: lm,
            distanceMeters: distanceMeters(from, approach),
            bearingDegrees: bearingDegrees(from, approach)
        )
    }
    // Stable, like Kotlin's sortedBy.
    return fixes.enumerated()
        .sorted { a, b in
            if a.element.distanceMeters != b.element.distanceMeters {
                return a.element.distanceMeters < b.element.distanceMeters
            }
            return a.offset < b.offset
        }
        .map { $0.element }
}

public func rankByDistance(landmarks: [Landmark], from: LatLng) -> [LandmarkFix] {
    rankByDistance(landmarks, from)
}

/// How close the user must be for the app to switch from GPS guidance to
/// close-range AR, in metres.
///
/// Below this, GPS error (5-10m) is comparable to the remaining distance, so
/// an arrow becomes useless; AR's local precision takes over instead.
public let AR_HANDOFF_DISTANCE_M: Double = 30.0

/// Distance at which the user is considered to have arrived.
public let ARRIVAL_DISTANCE_M: Double = 8.0

/// Normalises Arabic text so search behaves the way users expect.
///
/// Handles the three things that otherwise make Arabic search feel broken:
///  - Arabic-Indic digits (١٣) and Persian digits are folded to ASCII, so
///    typing "13" finds "وحدة ١٣" and vice versa
///  - alef variants (أ إ آ) fold to ا, and ة folds to ه, since users rarely
///    type the exact form
///  - diacritics (tashkeel) are stripped
public func normaliseForSearch(_ input: String) -> String {
    var out = String.UnicodeScalarView()
    for ch in input.lowercased().unicodeScalars {
        let v = ch.value
        switch v {
        // Arabic-Indic digits
        case 0x0660...0x0669:
            out.append(Unicode.Scalar(0x30 + (v - 0x0660))!)
        // Extended Arabic-Indic (Persian) digits
        case 0x06F0...0x06F9:
            out.append(Unicode.Scalar(0x30 + (v - 0x06F0))!)
        case 0x0623, 0x0625, 0x0622, 0x0671: // أ إ آ ٱ
            out.append("ا")
        case 0x0629: // ة
            out.append("ه")
        case 0x0649: // ى
            out.append("ي")
        case 0x0624: // ؤ
            out.append("و")
        case 0x0626: // ئ
            out.append("ي")
        // Strip tashkeel and tatweel entirely.
        case 0x064B...0x065F, 0x0640, 0x0670:
            continue
        default:
            out.append(ch)
        }
    }
    return String(out).trimmingCharacters(in: .whitespacesAndNewlines)
}

/// Matches a landmark against a search query.
///
/// Numbers are compared as whole tokens, so searching "13" matches
/// "وحدة 13" but NOT "وحدة 1" or "وحدة 3" -- exactly the confusion a numbered
/// housing block would otherwise cause.
public func matchesQuery(_ landmark: Landmark, _ query: String) -> Bool {
    let q = normaliseForSearch(query)
    if q.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return true }

    let haystack = normaliseForSearch(
        "\(landmark.name) \(landmark.category.label) \(landmark.category.labelAr) \(landmark.detail)"
    )

    // A purely numeric query must match a standalone number, not a substring.
    if q.unicodeScalars.allSatisfy({ $0.properties.numericType == .decimal }) {
        let pattern = "(?<![0-9])" + NSRegularExpression.escapedPattern(for: q) + "(?![0-9])"
        guard let re = try? NSRegularExpression(pattern: pattern) else { return false }
        let range = NSRange(haystack.startIndex..<haystack.endIndex, in: haystack)
        return re.firstMatch(in: haystack, options: [], range: range) != nil
    }
    return haystack.contains(q)
}

public func matchesQuery(landmark: Landmark, query: String) -> Bool { matchesQuery(landmark, query) }

// MARK: - Stored JSON (same format as Android's LandmarkStore / assets/landmarks.json)

/// Bump when entries are added to the bundled seed, or existing installs never
/// see them: the seed merges into on-device data without clobbering surveys.
/// Mirrors `CURRENT_SEED_VERSION` in Android's LandmarkStore.kt.
public let LANDMARK_SEED_VERSION: Int = 4

public enum LandmarkJSONError: Error {
    case notAnArray
}

extension Landmark {
    /// Parses the landmark store / seed JSON with the same leniency as the
    /// Android parser: entries without a valid coordinate are dropped, unknown
    /// categories become OTHER, the legacy single `photo` field is honoured.
    public static func decodeStore(_ data: Data) throws -> [Landmark] {
        guard let arr = try JSONSerialization.jsonObject(with: data) as? [Any] else {
            throw LandmarkJSONError.notAnArray
        }
        var out: [Landmark] = []
        for (i, item) in arr.enumerated() {
            guard let o = item as? [String: Any] else { continue }
            guard let lat = jsonDouble(o["lat"]), let lon = jsonDouble(o["lon"]) else { continue }
            let position = LatLng(lat, lon)
            if !position.isValid { continue }

            let amenities = (o["amenities"] as? [Any])?.compactMap { $0 as? String } ?? []
            let signs = ((o["signs"] as? [Any])?.compactMap { $0 as? String } ?? [])
                .filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

            var entrances: [Entrance] = []
            for (j, eAny) in ((o["entrances"] as? [Any]) ?? []).enumerated() {
                guard let e = eAny as? [String: Any],
                      let elat = jsonDouble(e["lat"]), let elon = jsonDouble(e["lon"]) else { continue }
                let pos = LatLng(elat, elon)
                if !pos.isValid { continue }
                let eid = nonBlank(e["id"] as? String) ?? "en-\(UUID().uuidString)-\(j)"
                entrances.append(Entrance(id: eid, nameAr: (e["name"] as? String) ?? "المدخل", position: pos))
            }

            var photos: [LandmarkPhoto] = []
            for pAny in (o["photos"] as? [Any]) ?? [] {
                guard let p = pAny as? [String: Any], let file = nonBlank(p["file"] as? String) else { continue }
                var takenFrom: LatLng? = nil
                if let fLat = jsonDouble(p["fromLat"]), let fLon = jsonDouble(p["fromLon"]) {
                    takenFrom = LatLng(fLat, fLon)
                }
                photos.append(
                    LandmarkPhoto(
                        file: file,
                        viewpoint: Viewpoint(rawValue: (p["viewpoint"] as? String) ?? "") ?? .other,
                        takenFrom: takenFrom,
                        bearingDegrees: jsonDouble(p["bearing"]),
                        distanceMeters: jsonDouble(p["distance"])
                    )
                )
            }
            // Backwards compatibility with the single-photo format.
            if let legacy = nonBlank(o["photo"] as? String), !photos.contains(where: { $0.file == legacy }) {
                photos.insert(LandmarkPhoto(file: legacy, viewpoint: .entrance), at: 0)
            }

            out.append(
                Landmark(
                    id: nonBlank(o["id"] as? String) ?? "lm-\(UUID().uuidString)-\(i)",
                    name: (o["name"] as? String) ?? "Unnamed",
                    category: LandmarkCategory.fromName((o["category"] as? String) ?? ""),
                    position: position,
                    detail: (o["detail"] as? String) ?? "",
                    hours: (o["hours"] as? String) ?? "",
                    amenities: amenities,
                    photos: photos,
                    capturedAccuracyM: Float(jsonDouble(o["accuracy"]) ?? 0.0),
                    placedManually: (o["manual"] as? Bool) ?? false,
                    signTexts: signs,
                    entrances: entrances
                )
            )
        }
        return out
    }

    /// Serialises in the format `decodeStore` (and Android) reads.
    public static func encodeStore(_ landmarks: [Landmark]) throws -> Data {
        // Closure return type spelled out so the heterogeneous literal below is
        // checked against [String: Any] directly instead of being inferred.
        let arr: [[String: Any]] = landmarks.map { l -> [String: Any] in
            [
                "id": l.id,
                "name": l.name,
                "category": l.category.name,
                "lat": l.position.latitude,
                "lon": l.position.longitude,
                "detail": l.detail,
                "hours": l.hours,
                "accuracy": Double(l.capturedAccuracyM),
                "manual": l.placedManually,
                "signs": l.signTexts,
                "entrances": l.entrances.map { e -> [String: Any] in
                    ["id": e.id, "name": e.nameAr, "lat": e.position.latitude, "lon": e.position.longitude]
                },
                "amenities": l.amenities,
                "photos": l.photos.map { p -> [String: Any] in
                    var d: [String: Any] = ["file": p.file, "viewpoint": p.viewpoint.name]
                    if let t = p.takenFrom {
                        d["fromLat"] = t.latitude
                        d["fromLon"] = t.longitude
                    }
                    if let b = p.bearingDegrees { d["bearing"] = b }
                    if let dm = p.distanceMeters { d["distance"] = dm }
                    return d
                },
            ]
        }
        return try JSONSerialization.data(withJSONObject: arr, options: [.prettyPrinted])
    }
}

private func jsonDouble(_ v: Any?) -> Double? {
    if let n = v as? NSNumber {
        let d = n.doubleValue
        return d.isNaN ? nil : d
    }
    if let s = v as? String, let d = Double(s), !d.isNaN { return d }
    return nil
}

private func nonBlank(_ s: String?) -> String? {
    guard let s = s, !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
    return s
}
