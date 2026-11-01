import SwiftUI
import UIKit

/// Smart Mutawwif brand tokens (docs/brand/smart-mutawwif-v1/source/brand-tokens.json).
///
/// Named `NavBrand` so it cannot collide with the companion's own theme file;
/// both read the same three brand colours.
enum NavBrand {
    static let green = Color(red: 0x17 / 255.0, green: 0x3E / 255.0, blue: 0x35 / 255.0)
    static let gold = Color(red: 0xB8 / 255.0, green: 0x9B / 255.0, blue: 0x5C / 255.0)
    static let ivory = Color(red: 0xF6 / 255.0, green: 0xF5 / 255.0, blue: 0xEF / 255.0)
    static let muted = Color(red: 0.78, green: 0.82, blue: 0.79)
    /// Over a live camera image a solid panel hides the very thing the user
    /// is looking for; 0.82 keeps text readable in direct sun.
    static let hudPanel = Color(red: 0x0F / 255.0, green: 0x2A / 255.0, blue: 0x24 / 255.0).opacity(0.82)

    static let uiGreen = UIColor(red: 0x17 / 255.0, green: 0x3E / 255.0, blue: 0x35 / 255.0, alpha: 1)
    static let uiGold = UIColor(red: 0xB8 / 255.0, green: 0x9B / 255.0, blue: 0x5C / 255.0, alpha: 1)
    static let uiIvory = UIColor(red: 0xF6 / 255.0, green: 0xF5 / 255.0, blue: 0xEF / 255.0, alpha: 1)

    /// IBM Plex Sans Arabic PostScript names, as registered via UIAppFonts.
    /// Falls back to the system font if registration ever fails, so a missing
    /// file degrades typography rather than producing blank labels.
    static func font(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        let name: String
        switch weight {
        case .bold, .heavy, .black: name = "IBMPlexSansArabic-Bold"
        case .semibold: name = "IBMPlexSansArabic-SemiBold"
        case .medium: name = "IBMPlexSansArabic-Medium"
        default: name = "IBMPlexSansArabic-Regular"
        }
        if UIFont(name: name, size: size) != nil {
            return Font.custom(name, size: size)
        }
        return Font.system(size: size, weight: weight)
    }

    static func uiFont(_ size: CGFloat, bold: Bool) -> UIFont {
        let name = bold ? "IBMPlexSansArabic-Bold" : "IBMPlexSansArabic-Medium"
        return UIFont(name: name, size: size) ?? UIFont.systemFont(ofSize: size, weight: bold ? .bold : .medium)
    }
}
