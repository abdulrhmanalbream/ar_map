import Foundation

// Small helpers that reproduce Kotlin standard-library semantics the ported
// code relies on. Kept internal: they exist so the transliteration stays
// line-for-line comparable with the Kotlin original, not as public API.

extension Comparable {
    /// Kotlin `coerceIn`.
    @inline(__always)
    func coerced(_ lo: Self, _ hi: Self) -> Self {
        if self < lo { return lo }
        if self > hi { return hi }
        return self
    }
}

/// Kotlin `Math.round(Double)`: floor(x + 0.5), so -2.5 rounds to -2.
@inline(__always)
func kRound(_ x: Double) -> Int {
    return Int((x + 0.5).rounded(.down))
}

/// Kotlin `Double % Double` (sign follows the dividend), i.e. fmod.
@inline(__always)
func kRem(_ a: Double, _ b: Double) -> Double {
    return a.truncatingRemainder(dividingBy: b)
}

@inline(__always)
func toRadians(_ deg: Double) -> Double { deg * Double.pi / 180.0 }

@inline(__always)
func toDegrees(_ rad: Double) -> Double { rad * 180.0 / Double.pi }

/// Kotlin `String.format("%.1f", x)` with a stable decimal point. Kotlin used
/// the default locale; the app is RTL Arabic but always rendered ASCII digits
/// with '.', so POSIX matches what the Android build actually showed.
func formatFixed(_ format: String, _ args: CVarArg...) -> String {
    return String(format: format, locale: Locale(identifier: "en_US_POSIX"), arguments: args)
}

/// Kotlin `Double.toInt()`: truncates toward zero, NaN -> 0, clamps at Int range.
@inline(__always)
func kToInt(_ x: Double) -> Int {
    if x.isNaN { return 0 }
    if x >= Double(Int.max) { return Int.max }
    if x <= Double(Int.min) { return Int.min }
    return Int(x)
}

@inline(__always)
func kToInt(_ x: Float) -> Int { kToInt(Double(x)) }
