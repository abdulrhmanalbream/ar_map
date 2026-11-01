import Foundation

// Which bundled Arabic clips (and which TTS fallbacks) make up one spoken cue.
// Clip ids match the WAV file names in Android's assets/navigation/ar/.

public enum NavigationSpeechSegment: Equatable {
    case clip(id: String, fallbackText: String)
    case text(String)
}

public enum NavigationDirection: String, CaseIterable {
    case forward = "FORWARD"
    case right = "RIGHT"
    case left = "LEFT"
    case back = "BACK"
    case slightRight = "SLIGHT_RIGHT"
    case slightLeft = "SLIGHT_LEFT"

    public var clipId: String {
        switch self {
        case .forward: return "nav_ar_forward"
        case .right: return "nav_ar_right"
        case .left: return "nav_ar_left"
        case .back: return "nav_ar_back"
        case .slightRight: return "nav_ar_slight_right"
        case .slightLeft: return "nav_ar_slight_left"
        }
    }

    public var spokenText: String {
        switch self {
        case .forward: return "تابع السير إلى الأمام."
        case .right: return "اتجه إلى اليمين."
        case .left: return "اتجه إلى اليسار."
        case .back: return "استدر إلى الخلف."
        case .slightRight: return "اتجه قليلاً إلى اليمين."
        case .slightLeft: return "اتجه قليلاً إلى اليسار."
        }
    }
}

/// Same thresholds as voiceCueAr/instructionAr; playback never decides the route.
public func navigationDirection(_ relativeDegrees: Double) -> NavigationDirection {
    if abs(relativeDegrees) <= 15 { return .forward }
    if relativeDegrees > 125 || relativeDegrees < -125 { return .back }
    if relativeDegrees > 55 { return .right }
    if relativeDegrees < -55 { return .left }
    if relativeDegrees > 0 { return .slightRight }
    return .slightLeft
}

let NAVIGATION_DISTANCE_WORDS: [Int: String] = [
    25: "خمسة وعشرون مترًا", 50: "خمسون مترًا", 75: "خمسة وسبعون مترًا",
    100: "مئة متر", 150: "مئة وخمسون مترًا", 200: "مئتا متر", 250: "مئتان وخمسون مترًا",
    300: "ثلاثمئة متر", 400: "أربعمئة متر", 500: "خمسمئة متر", 600: "ستمئة متر",
    700: "سبعمئة متر", 800: "ثمانمئة متر", 900: "تسعمئة متر", 1000: "ألف متر",
    1100: "ألف ومئة متر", 1200: "ألف ومئتا متر", 1300: "ألف وثلاثمئة متر",
    1400: "ألف وأربعمئة متر", 1500: "ألف وخمسمئة متر", 1600: "ألف وستمئة متر",
    1700: "ألف وسبعمئة متر", 1800: "ألف وثمانمئة متر", 1900: "ألف وتسعمئة متر", 2000: "ألفا متر",
]

public let NAVIGATION_DISTANCE_CLIPS: Set<Int> = Set(NAVIGATION_DISTANCE_WORDS.keys)

public let NAVIGATION_CLIP_IDS: Set<String> = {
    var ids = Set(NavigationDirection.allCases.map { $0.clipId })
    ids.formUnion(["nav_ar_approach", "nav_ar_arrived", "nav_ar_ambiguous", "nav_ar_selected", "nav_ar_no_fix"])
    ids.formUnion(NAVIGATION_DISTANCE_CLIPS.map { "nav_ar_distance_\($0)" })
    return ids
}()

/// Complete spoken units, never the compact م/كم strings used by the display.
public func navigationDistanceSpeech(_ meters: Double) -> NavigationSpeechSegment {
    let bucket = voiceDistanceBucket(meters)
    let words = NAVIGATION_DISTANCE_WORDS[bucket] ?? "\(bucket) متراً"
    let sentence = "المسافة المتبقية، \(words)."
    if NAVIGATION_DISTANCE_CLIPS.contains(bucket) {
        return .clip(id: "nav_ar_distance_\(bucket)", fallbackText: sentence)
    }
    return .text(sentence)
}

private func withDestination(_ clip: NavigationSpeechSegment, _ targetName: String) -> [NavigationSpeechSegment] {
    let name = targetName.trimmingCharacters(in: .whitespacesAndNewlines)
    return name.isEmpty ? [clip] : [clip, .text(name)]
}

/// Called only after the existing voice timing policy accepts a cue, or for an explicit repeat.
public func navigationSpeechPlan(
    _ mode: GuidanceMode,
    _ targetName: String,
    manualRepeat: Bool = false
) -> [NavigationSpeechSegment] {
    switch mode {
    case .noFix:
        return manualRepeat ? [.clip(id: "nav_ar_no_fix", fallbackText: "ننتظر تحديد موقعك.")] : []
    case let .compass(distance, _, rel):
        let direction = navigationDirection(rel)
        return [.clip(id: direction.clipId, fallbackText: direction.spokenText), navigationDistanceSpeech(distance)]
    case .arApproach:
        return withDestination(
            .clip(id: "nav_ar_approach", fallbackText: "وجهتك أمامك. اتبع المسار الظاهر على الأرض."), targetName)
    case .arrived:
        return withDestination(.clip(id: "nav_ar_arrived", fallbackText: "لقد وصلت إلى وجهتك."), targetName)
    case .ambiguous:
        return [.clip(id: "nav_ar_ambiguous", fallbackText: "أنت قريب من عدة مبانٍ. تأكد من وجهتك بالنظر.")]
    }
}

public func navigationSelectionSpeech(_ targetName: String) -> [NavigationSpeechSegment] {
    return withDestination(.clip(id: "nav_ar_selected", fallbackText: "تم اختيار وجهتك."), targetName)
}
