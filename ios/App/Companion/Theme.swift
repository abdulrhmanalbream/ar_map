import SwiftUI

extension Color {
    /// 0xAARRGGBB, matching the Android Compose color literals.
    init(argb: UInt32) {
        self.init(.sRGB,
                  red: Double((argb >> 16) & 0xFF) / 255,
                  green: Double((argb >> 8) & 0xFF) / 255,
                  blue: Double(argb & 0xFF) / 255,
                  opacity: Double((argb >> 24) & 0xFF) / 255)
    }
}

/// Hedayathon's printed identity: forest, ivory, and restrained gold, plus the
/// translucent hologram palette used over the camera.
enum Hedayah {
    static let forest = Color(argb: 0xFF133F35)
    static let ink = Color(argb: 0xFF173F35)
    static let ivory = Color(argb: 0xFFF5F3EB)
    static let paper = Color(argb: 0xFFFFFEF9)
    static let soft = Color(argb: 0xFFE9EEE3)
    static let gold = Color(argb: 0xFFB49A5B)
    static let darkGold = Color(argb: 0xFF806B38)
    static let muted = Color(argb: 0xFF5B6E61)
    static let outline = Color(argb: 0xFFB8C4B7)
    static let error = Color(argb: 0xFF9C3D2C)
    static let good = Color(argb: 0xFF26664C)
    static let hud = Color(argb: 0xFF102C25)
    static let hudGold = Color(argb: 0xFFE3CA87)
    // The camera remains visible through AR panels; only text and edge highlights are solid.
    static let arPanel = Color(argb: 0x44102C25)
    static let arPanelRaised = Color(argb: 0x80244D40)
    static let arText = Color(argb: 0xFFF3FFF9)
    static let arMuted = Color(argb: 0xFFD8F4E5)
    static let arAccent = Color(argb: 0xFFB7FFE0)
    static let arError = Color(argb: 0xFFFFC4B5)
    static let textShadow = Color(argb: 0xFF04140D)
}

/// Translucent forest panel with a thin mint frame, so the scene shows through.
struct HologramPanel: ViewModifier {
    var cornerRadius: CGFloat = 14
    func body(content: Content) -> some View {
        content
            .foregroundColor(Hedayah.arText)
            .shadow(color: Hedayah.textShadow, radius: 2, x: 0, y: 1)
            .background(RoundedRectangle(cornerRadius: cornerRadius).fill(Hedayah.arPanel))
            .overlay(RoundedRectangle(cornerRadius: cornerRadius).stroke(Hedayah.arAccent.opacity(0.7), lineWidth: 1))
    }
}

extension View {
    func hologramPanel(cornerRadius: CGFloat = 14) -> some View { modifier(HologramPanel(cornerRadius: cornerRadius)) }
}

struct HedayahFilledButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundColor(Hedayah.paper)
            .background(RoundedRectangle(cornerRadius: 14)
                .fill(Hedayah.forest.opacity(isEnabled ? (configuration.isPressed ? 0.8 : 1) : 0.4)))
    }
}

struct HedayahOutlineButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundColor(Hedayah.forest.opacity(isEnabled ? 1 : 0.4))
            .background(RoundedRectangle(cornerRadius: 14).fill(configuration.isPressed ? Hedayah.soft : Hedayah.paper))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(Hedayah.outline, lineWidth: 1))
    }
}

/// Hologram-styled control used over the live camera.
struct HologramButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.callout.weight(.medium))
            .padding(.horizontal, 12)
            .frame(minHeight: 44)
            .foregroundColor(Hedayah.arText.opacity(isEnabled ? 1 : 0.45))
            .background(RoundedRectangle(cornerRadius: 12)
                .fill(configuration.isPressed ? Hedayah.arPanelRaised : Hedayah.arPanel))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Hedayah.arAccent.opacity(0.7), lineWidth: 1))
    }
}

/// The user's chosen companion language, confirmed once on first run.
final class CompanionLanguage: ObservableObject {
    static let shared = CompanionLanguage()

    private let defaults: UserDefaults
    @Published private(set) var code: String
    @Published private(set) var confirmed: Bool

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let stored = defaults.string(forKey: "smart_mutawwif_language.language") ?? "ar"
        code = VisionPolicy.languages.contains(stored) ? stored : "ar"
        confirmed = defaults.bool(forKey: "smart_mutawwif_language.confirmed")
    }

    var rtl: Bool { companionIsRTL(code) }
    var layoutDirection: LayoutDirection { rtl ? .rightToLeft : .leftToRight }

    func confirm(_ language: String) {
        guard VisionPolicy.languages.contains(language) else { return }
        defaults.set(language, forKey: "smart_mutawwif_language.language")
        defaults.set(true, forKey: "smart_mutawwif_language.confirmed")
        code = language
        confirmed = true
    }
}
