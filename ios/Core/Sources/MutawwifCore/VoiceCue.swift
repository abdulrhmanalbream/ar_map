import Foundation

// When to speak, and what to say -- the policy behind voice guidance.
//
// The hard part of spoken navigation is not producing speech, it is shutting
// up. Distance updates arrive every GPS tick and heading jitters around
// instruction thresholds; speaking every change would produce a continuous
// babble that gets the volume turned to zero on day one. So:
//
//  - distances are spoken in coarse buckets, so the phrase only changes at
//    meaningful progress, not every metre
//  - a phrase is only spoken when it differs from the last one spoken
//  - non-urgent phrases keep a minimum gap; arrival and ambiguity cut in
//    immediately because they are the two moments worth interrupting for
//
// Pure logic, mirrored on `instructionAr`'s thresholds so ears and eyes
// never disagree about which way to go.

/// Minimum silence between routine announcements.
public let VOICE_MIN_GAP_MS: Int64 = 6_000

/// Rounds a distance to the step a walker actually cares about at that range.
///
/// Compass guidance only runs beyond the AR handoff (30m), so the finest
/// bucket is 25m: below that the ground path and the arrival flow carry the
/// message, and voice repeating single metres would be noise.
public func voiceDistanceBucket(_ meters: Double) -> Int {
    let step: Int
    if meters >= 300 { step = 100 }
    else if meters >= 100 { step = 50 }
    else { step = 25 }
    return max(kRound(meters / Double(step)) * step, step)
}

/// The spoken phrase for the current guidance, or nil for silence.
public func voiceCueAr(_ mode: GuidanceMode, _ targetName: String) -> String? {
    switch mode {
    // Nagging "still no GPS" out loud helps nobody; the HUD already says it.
    case .noFix:
        return nil

    case let .compass(distance, _, rel):
        let direction: String
        if abs(rel) <= 15 { direction = "امشِ للأمام" }
        else if rel > 125 || rel < -125 { direction = "استدر للخلف" }
        else if rel > 55 { direction = "اتجه يميناً" }
        else if rel < -55 { direction = "اتجه يساراً" }
        else if rel > 0 { direction = "مِل يميناً قليلاً" }
        else { direction = "مِل يساراً قليلاً" }
        return "\(direction)، على بعد \(voiceDistanceBucket(distance)) متر"

    case .arApproach:
        return "\(targetName) أمامك، اتبع المسار على الأرض"

    case .ambiguous:
        return "أنت قريب من عدة مبانٍ، تأكد بالنظر"

    case .arrived:
        return "وصلت إلى \(targetName)"
    }
}

/// What has been said so far, threaded through `nextVoiceCue`.
public struct VoiceState: Equatable {
    public var lastText: String?
    public var lastAtMs: Int64

    public init(lastText: String? = nil, lastAtMs: Int64 = 0) {
        self.lastText = lastText
        self.lastAtMs = lastAtMs
    }
}

/// Decides whether `cue` should be spoken now.
///
/// Returns the updated state and the text to speak, or nil for silence.
/// `urgent` bypasses the minimum gap -- arrival and ambiguity must not wait
/// six seconds while the user walks past the door.
/// A routine sensor update must also wait for the entire current spoken phrase:
/// a direction plus distance recording can exceed the six-second minimum gap.
/// Deferral keeps state unchanged so a later tick can speak the current instruction.
public func nextVoiceCue(
    state: VoiceState,
    cue: String?,
    urgent: Bool,
    nowMs: Int64,
    playbackBusy: Bool = false
) -> (VoiceState, String?) {
    guard let cue = cue else { return (state, nil) }
    if cue == state.lastText { return (state, nil) }
    if !urgent && playbackBusy { return (state, nil) }
    if !urgent && nowMs - state.lastAtMs < VOICE_MIN_GAP_MS { return (state, nil) }
    return (VoiceState(lastText: cue, lastAtMs: nowMs), cue)
}
