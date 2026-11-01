import Foundation

struct VisionPayloadError: Error {}

/// JSONSerialization represents booleans as NSNumber too; a JSON `true` must not pass as 1.
func jsonNumber(_ value: Any?) -> Double? {
    guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
    return number.doubleValue
}

func jsonBool(_ value: Any?) -> Bool? {
    guard let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else { return nil }
    return number.boolValue
}

/// Server bounds follow JavaScript (UTF-16) string length, so local truncation does too,
/// without ever splitting a character.
func utf16Prefix(_ text: String, _ limit: Int) -> String {
    if text.utf16.count <= limit { return text }
    var result = ""
    var used = 0
    for character in text {
        let size = String(character).utf16.count
        if used + size > limit { break }
        result.append(character)
        used += size
    }
    return result
}

/// Treat model output as untrusted display data; malformed boxes never reach the overlay.
enum VisionPayloads {
    static let landmarkIds: Set<String> = ["kaaba", "maqam_ibrahim", "black_stone", "safa_marwa"]
    static let kinds: Set<String> = ["landmark", "text", "object"]

    static func analysis(_ json: [String: Any]) throws -> VisionAnalysis {
        let sources = self.sources(json["sources"] as? [Any])
        let sourceIds = Set(sources.map { $0.id })
        var seen = Set<String>()
        let entries = (json["items"] as? [Any]) ?? []
        guard entries.count <= 40 else { throw VisionPayloadError() }
        var items: [VisionItem] = []
        for raw in entries {
            guard let item = try? parseItem(raw, sourceIds: sourceIds), seen.insert(item.id).inserted else { continue }
            items.append(item)
            if items.count == 20 { break }
        }
        return VisionAnalysis(sceneId: try required(json, "sceneId", 120), summary: try required(json, "summary", 4000),
                              items: items, sources: sources, provider: try required(json, "provider", 40))
    }

    private static func parseItem(_ raw: Any, sourceIds: Set<String>) throws -> VisionItem {
        guard let entry = raw as? [String: Any] else { throw VisionPayloadError() }
        let id = try required(entry, "id", 120)
        let kind = try required(entry, "kind", 20)
        guard kinds.contains(kind), let coordinates = entry["box"] as? [Any], coordinates.count == 4 else {
            throw VisionPayloadError()
        }
        var v: [Double] = []
        for c in coordinates {
            guard let d = jsonNumber(c) else { throw VisionPayloadError() }
            v.append(d)
        }
        guard v.allSatisfy({ $0.isFinite && $0 >= 0 && $0 <= 1000 }), v[0] < v[2], v[1] < v[3] else {
            throw VisionPayloadError()
        }
        let refs = (entry["sourceIds"] as? [Any]) ?? []
        var refsList: [String] = []
        for ref in refs.prefix(12) {
            if let s = ref as? String, sourceIds.contains(s), !refsList.contains(s) { refsList.append(s) }
        }
        var landmarkId: String? = nil
        if kind == "landmark", let candidate = entry["landmarkId"] as? String, landmarkIds.contains(candidate) {
            landmarkId = candidate
        }
        let devotionals = landmarkId == nil ? [] : self.devotionals(entry["devotionals"] as? [Any], sourceIds: sourceIds)
        return VisionItem(id: id, kind: kind, label: try required(entry, "label", 200),
                          description: try optional(entry, "description", 1600),
                          box: VisionBox(yMin: v[0], xMin: v[1], yMax: v[2], xMax: v[3]),
                          sourceIds: refsList, landmarkId: landmarkId, devotionals: devotionals)
    }

    static func answer(_ json: [String: Any]) throws -> VisionAnswer {
        VisionAnswer(reply: try required(json, "reply", 6000), sources: sources(json["sources"] as? [Any]),
                     provider: try required(json, "provider", 40))
    }

    static func image(_ jpegBase64: String) -> [String: Any] {
        ["mimeType": "image/jpeg", "data": jpegBase64]
    }

    /// Devotionals and sources are retrieved server-side; sending them back fails validation.
    static func selected(_ item: VisionItem) -> [String: Any] {
        var body: [String: Any] = ["id": item.id, "label": utf16Prefix(item.label, 160),
                                   "description": utf16Prefix(item.description, 600), "kind": item.kind]
        if let landmark = item.landmarkId { body["landmarkId"] = landmark }
        return body
    }

    static func history(_ entries: [VisionHistoryEntry]) -> [[String: Any]] {
        entries.suffix(6).map { ["role": $0.role, "text": utf16Prefix($0.text, 1000)] }
    }

    private static func devotionals(_ array: [Any]?, sourceIds: Set<String>) -> [VisionDevotional] {
        guard let array = array, array.count <= 2 else { return [] }
        var result: [VisionDevotional] = []
        for raw in array {
            guard let entry = raw as? [String: Any], let refs = entry["sourceIds"] as? [Any],
                  refs.count >= 1, refs.count <= 3 else { continue }
            var known: [String] = []
            var valid = true
            for ref in refs {
                guard let s = ref as? String, sourceIds.contains(s) else { valid = false; break }
                if !known.contains(s) { known.append(s) }
            }
            guard valid,
                  let id = try? required(entry, "id", 80), let title = try? required(entry, "title", 120),
                  let arabic = try? required(entry, "arabicText", 600),
                  let translation = try? required(entry, "translation", 900),
                  let note = try? required(entry, "note", 600),
                  !result.contains(where: { $0.id == id }) else { continue }
            result.append(VisionDevotional(id: id, title: title, arabicText: arabic, translation: translation,
                                           note: note, sourceIds: known))
        }
        return result
    }

    private static func sources(_ array: [Any]?) -> [VisionSource] {
        guard let array = array else { return [] }
        var seen = Set<String>()
        var result: [VisionSource] = []
        for raw in array.prefix(16) {
            guard let value = raw as? [String: Any],
                  let id = try? required(value, "id", 120),
                  let urlText = try? required(value, "url", 2048),
                  let title = try? required(value, "title", 240),
                  let components = URLComponents(string: urlText),
                  components.scheme == "https", let host = components.host, !host.isEmpty,
                  components.user == nil, components.password == nil,
                  let url = components.url,
                  seen.insert(id).inserted else { continue }
            result.append(VisionSource(id: id, title: title, url: url.absoluteString))
        }
        return result
    }

    static func required(_ json: [String: Any], _ key: String, _ maximum: Int) throws -> String {
        guard let raw = json[key] as? String, !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              raw.utf16.count <= maximum else { throw VisionPayloadError() }
        return raw.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func optional(_ json: [String: Any], _ key: String, _ maximum: Int) throws -> String {
        guard let value = json[key] else { return "" }
        guard let raw = value as? String, raw.utf16.count <= maximum else { throw VisionPayloadError() }
        return raw.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
