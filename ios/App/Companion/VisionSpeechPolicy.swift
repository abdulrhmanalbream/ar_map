import Foundation

/// New results may narrate once; neither reopening nor unmuting replays a stale result.
final class VisionNarrationGate {
    private var consumedScene: String?
    private var lastSpoken: [(key: String, at: Int64)] = []

    func shouldSpeak(sceneId: String, key: String, capturedAt: Int64, now: Int64, resumedAt: Int64,
                     muted: Bool, busy: Bool) -> Bool {
        if capturedAt < resumedAt || now < capturedAt || now - capturedAt > 60_000 || muted {
            consumedScene = sceneId
            return false
        }
        if consumedScene == sceneId || busy { return false }
        consumedScene = sceneId
        if let last = lastSpoken.first(where: { $0.key == key }), now - last.at < 120_000 { return false }
        lastSpoken.removeAll { $0.key == key }
        lastSpoken.append((key: key, at: now))
        if lastSpoken.count > 32 { lastSpoken.removeFirst() }
        return true
    }
}

func visionLandmarkNarration(_ item: VisionItem, language: String) -> String {
    let devotional = item.devotionals.first
    let scripture = language == "ar" ? devotional?.arabicText : devotional?.translation
    return [item.description, devotional?.title, devotional?.note, scripture]
        .compactMap { $0 }
        .filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        .joined(separator: "\n\n")
}

/// Existing and background replies must not speak when the screen is reopened.
final class VisionReplyReadGate {
    private var consumedRevision: Int64

    init(initialRevision: Int64) { consumedRevision = initialRevision }

    func consume(_ revision: Int64, foreground: Bool) -> Bool {
        if revision <= consumedRevision { return false }
        consumedRevision = revision
        return foreground
    }
}

func visionUtteranceMatches(_ generation: Int64, _ utteranceId: String?) -> Bool {
    utteranceId?.hasPrefix("vision-\(generation)-") == true
}

/// Keep a complete answer while respecting a bounded utterance length. Lengths are
/// UTF-16 units like the Android engine limit; Swift Characters are never split.
func visionSpeechChunks(_ text: String, maxLength: Int) -> [String] {
    precondition(maxLength >= 2)
    let characters = Array(text.trimmingCharacters(in: .whitespacesAndNewlines))
    var chunks: [String] = []
    var start = 0
    while start < characters.count {
        var end = start
        var used = 0
        while end < characters.count {
            let size = String(characters[end]).utf16.count
            if used + size > maxLength && end > start { break }
            used += size
            end += 1
        }
        if end < characters.count {
            var boundary = end - 1
            while boundary > start && characters[boundary] != " " { boundary -= 1 }
            var prefixLength = 0
            for i in start..<boundary { prefixLength += String(characters[i]).utf16.count }
            if boundary > start && prefixLength > maxLength / 2 { end = boundary }
        }
        chunks.append(String(characters[start..<end]))
        start = end
        while start < characters.count && characters[start].isWhitespace { start += 1 }
    }
    return chunks
}

/// Ceiling seconds keeps controls disabled for the final partial second without overflow.
func visionRetrySeconds(_ retryAtElapsedMs: Int64, now nowElapsedMs: Int64) -> Int64 {
    if retryAtElapsedMs <= 0 || nowElapsedMs < 0 || retryAtElapsedMs <= nowElapsedMs { return 0 }
    let remaining = retryAtElapsedMs - nowElapsedMs
    return remaining / 1_000 + (remaining % 1_000 > 0 ? 1 : 0)
}
