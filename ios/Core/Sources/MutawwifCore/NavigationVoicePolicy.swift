import Foundation

/// Recognition has its own speech; navigation works on either the phone or connected glasses.
public func navigationVoiceEligible(
    foreground: Bool,
    recognitionMode: Bool,
    hasTarget: Bool,
    eyeSource: Bool,
    glassesConnected: Bool,
    muted: Bool
) -> Bool {
    return foreground && !recognitionMode && hasTarget && !muted && (!eyeSource || glassesConnected)
}

public struct NavigationVoiceOption: Equatable {
    public var id: String
    public var language: String
    public var country: String
    public var quality: Int
    public var requiresNetwork: Bool
    public var installed: Bool

    public init(id: String, language: String, country: String, quality: Int, requiresNetwork: Bool, installed: Bool) {
        self.id = id
        self.language = language
        self.country = country
        self.quality = quality
        self.requiresNetwork = requiresNetwork
        self.installed = installed
    }
}

/// Never route an Arabic time-sensitive cue through an unavailable, remote or foreign voice.
public func selectNavigationVoice(_ voices: [NavigationVoiceOption]) -> NavigationVoiceOption? {
    return voices
        .filter { $0.language == "ar" && $0.installed && !$0.requiresNetwork }
        .sorted { a, b in
            if a.quality != b.quality { return a.quality > b.quality }
            let aSa = a.country == "SA"
            let bSa = b.country == "SA"
            if aSa != bSa { return aSa }
            return a.id < b.id
        }
        .first
}
