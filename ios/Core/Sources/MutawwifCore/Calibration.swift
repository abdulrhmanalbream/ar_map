import Foundation

// The guided start-up sequence shown when the camera opens.
//
// It is not decoration. Two separate subsystems genuinely need it:
//
//  - **AR tracking** cannot be established from a stationary camera. It needs
//    parallax -- the same scene viewed from slightly different positions --
//    to work out the geometry of the room. A user holding the phone perfectly
//    still waits forever, which is exactly what happened during testing.
//  - **The magnetometer** drifts and needs a sweeping motion to recalibrate.
//    Near metal it reads confidently wrong until waved.
//
// A side-to-side wave satisfies both at once, so the sequence asks for that
// first, then asks the user to raise the phone into the pose they will
// actually navigate with.
//
// Progress is driven by MEASURED readiness, not a timer: the step completes
// when tracking is genuinely established. A timer alone would dismiss the
// hint while the app was still blind, and the user would blame the app.

/// Where the user is in the start-up sequence.
public enum CalibrationStep: String, CaseIterable {
    /// Lay the phone flattish and sweep it side to side.
    case wave = "WAVE"
    /// Raise it to look ahead through the camera.
    case raise = "RAISE"
    /// Ready to navigate.
    case done = "DONE"
}

/// Inputs the sequence judges readiness from.
///
/// - tracking: AR has a usable pose
/// - compassReliable: the magnetometer is not reporting itself unreliable
/// - tilt: camera tilt from `HeadingResult`: 0 = level, 1 = straight down
/// - movedDegrees: how far the heading has swept since the step began
/// - elapsedMs: time spent on the current step
public struct CalibrationInput: Equatable {
    public var tracking: Bool
    public var compassReliable: Bool
    public var tilt: Float
    public var movedDegrees: Double
    public var elapsedMs: Int64

    public init(tracking: Bool, compassReliable: Bool, tilt: Float, movedDegrees: Double, elapsedMs: Int64) {
        self.tracking = tracking
        self.compassReliable = compassReliable
        self.tilt = tilt
        self.movedDegrees = movedDegrees
        self.elapsedMs = elapsedMs
    }
}

/// What the UI should show right now.
public struct CalibrationState: Equatable {
    public var step: CalibrationStep
    /// 0..1, for the progress ring.
    public var progress: Float
    public var titleAr: String
    public var bodyAr: String

    public init(step: CalibrationStep, progress: Float, titleAr: String, bodyAr: String) {
        self.step = step
        self.progress = progress
        self.titleAr = titleAr
        self.bodyAr = bodyAr
    }
}

/// Sweep required before the wave step is satisfied, in degrees.
let REQUIRED_SWEEP_DEG: Double = 55.0

/// Minimum time on the wave step, so it never flashes past unread.
let MIN_WAVE_MS: Int64 = 1_200

/// After this long the wave step gives up waiting for perfect conditions.
///
/// Some rooms simply lack the visual texture AR tracking needs, and blocking
/// the user forever is worse than letting them proceed with a warning.
let MAX_WAVE_MS: Int64 = 12_000

/// Camera tilt at or below this counts as "raised to look ahead".
let RAISED_TILT: Float = 0.55

/// Time on the raise step before it is assumed done.
let MAX_RAISE_MS: Int64 = 6_000

private let RAISE_TITLE = "ارفع الجوال الآن"
private let RAISE_BODY = "وجّه الكاميرا للأمام كأنك تصوّر الطريق."

/// Advances the sequence.
///
/// Pure, so the whole flow can be unit-tested rather than discovered by
/// repeatedly waving a phone at a wall.
public func nextCalibrationState(_ current: CalibrationStep, _ input: CalibrationInput) -> CalibrationState {
    switch current {
    case .wave:
        // The requirement is the OUTCOME, not the ritual.
        //
        // This used to demand a 55-degree sweep AND reliable sensors AND a
        // minimum time, all at once. But the sweep exists only in order to
        // make the sensors ready -- so once they are ready, continuing to ask
        // for the wave is asking the user to perform a ceremony for its own
        // sake. The sweep survives purely as a fallback for devices whose
        // magnetometer never reports itself reliable no matter what.
        let sweptEnough = input.movedDegrees >= REQUIRED_SWEEP_DEG
        let sensorsReady = input.tracking && input.compassReliable
        let waitedLongEnough = input.elapsedMs >= MIN_WAVE_MS
        let timedOut = input.elapsedMs >= MAX_WAVE_MS

        let satisfied = timedOut ||
            (waitedLongEnough && (sensorsReady || (sweptEnough && input.tracking)))

        if satisfied {
            return CalibrationState(step: .raise, progress: 0, titleAr: RAISE_TITLE, bodyAr: RAISE_BODY)
        }
        // Show the weaker of the two signals, so the ring reflects whichever
        // requirement is actually holding things up.
        let sweepProgress = (input.movedDegrees / REQUIRED_SWEEP_DEG).coerced(0.0, 1.0)
        let timeProgress = (Double(input.elapsedMs) / Double(MAX_WAVE_MS)).coerced(0.0, 1.0)
        return CalibrationState(
            step: .wave,
            progress: max(Float(min(sweepProgress, 1.0)), Float(timeProgress) * 0.3),
            titleAr: "حرّك الجوال يميناً ويساراً",
            bodyAr: "امسك الجوال مائلاً قليلاً وحرّكه ببطء يميناً ويساراً لثوانٍ."
        )

    case .raise:
        let raised = input.tilt <= RAISED_TILT
        if raised || input.elapsedMs >= MAX_RAISE_MS {
            return CalibrationState(step: .done, progress: 1, titleAr: "", bodyAr: "")
        }
        return CalibrationState(
            step: .raise,
            progress: Float((Double(input.elapsedMs) / Double(MAX_RAISE_MS)).coerced(0.0, 1.0)),
            titleAr: RAISE_TITLE,
            bodyAr: RAISE_BODY
        )

    case .done:
        return CalibrationState(step: .done, progress: 1, titleAr: "", bodyAr: "")
    }
}

/// Where the start-up sequence should begin -- if it needs to run at all.
///
/// The sequence used to be unconditional: every time the camera opened the
/// user was told to wave the phone, even when sensor fusion had the compass
/// calibrated and tracking was up from the first frame. That turns a
/// diagnostic into a superstition, and users learn to swipe past it without
/// reading -- so the one time it genuinely matters, it is ignored too.
///
/// Nothing here calibrates anything. The OS calibrates the magnetometer
/// continuously; this only decides whether the user needs to be asked to move
/// the device so the OS can gather the readings it needs.
public func initialCalibrationStep(_ input: CalibrationInput) -> CalibrationStep {
    // Already tracking, compass trusted, phone already up: say nothing.
    if input.tracking && input.compassReliable && input.tilt <= RAISED_TILT { return .done }
    // Sensors fine, phone still flat in the hand: just ask for the easy part.
    if input.tracking && input.compassReliable { return .raise }
    return .wave
}

/// Accumulates how far the heading has swept.
///
/// Sums absolute change rather than comparing start to end, so a wave out and
/// back still counts -- which is the motion actually being asked for, and
/// would otherwise register as zero.
public final class SweepTracker {
    private var last: Double? = nil
    public private(set) var totalDegrees: Double = 0.0

    public init() {}

    public func reset() {
        last = nil
        totalDegrees = 0.0
    }

    public func update(_ headingDeg: Double) {
        if let previous = last {
            let delta = abs(relativeBearing(previous, headingDeg))
            // Ignore jumps too large to be real motion; they are sensor
            // glitches and would otherwise complete the step instantly.
            if delta < 45.0 { totalDegrees += delta }
        }
        last = headingDeg
    }
}
