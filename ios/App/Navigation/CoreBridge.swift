import Foundation
import MutawwifCore

/// The one place the navigation layer calls MutawwifCore symbols beyond
/// Geo/Guidance/Landmark.
///
/// Core was transliterated in parallel with this layer and neither could be
/// compiled on the Windows host that wrote both, so any signature drift
/// between the two shows up as errors in this file only, not scattered
/// across the views.
enum CoreBridge {

    // MARK: Voice policy (core/VoiceCue.kt)

    static func voiceCue(_ mode: GuidanceMode, _ targetName: String) -> String? {
        voiceCueAr(mode, targetName)
    }

    static func nextCue(
        _ state: VoiceState,
        _ cue: String?,
        urgent: Bool,
        nowMs: Int64,
        playbackBusy: Bool
    ) -> (VoiceState, String?) {
        nextVoiceCue(state: state, cue: cue, urgent: urgent, nowMs: nowMs, playbackBusy: playbackBusy)
    }

    static func freshVoiceState() -> VoiceState { VoiceState() }

    static func voiceState(lastText: String?, lastAtMs: Int64) -> VoiceState {
        VoiceState(lastText: lastText, lastAtMs: lastAtMs)
    }

    // MARK: Spoken plan (core/NavigationSpeech.kt)

    /// Clip id + fallback text, or text only. Flattened here so VoiceGuide
    /// does not depend on how core shaped the sealed segment type.
    enum Segment {
        case clip(id: String, fallbackText: String)
        case text(String)
    }

    static func guidancePlan(_ mode: GuidanceMode, _ targetName: String, manualRepeat: Bool) -> [Segment] {
        navigationSpeechPlan(mode, targetName, manualRepeat: manualRepeat).map(flatten)
    }

    static func selectionPlan(_ targetName: String) -> [Segment] {
        navigationSelectionSpeech(targetName).map(flatten)
    }

    static func isKnownClip(_ id: String) -> Bool {
        NAVIGATION_CLIP_IDS.contains(id)
    }

    private static func flatten(_ segment: NavigationSpeechSegment) -> Segment {
        switch segment {
        case let .clip(id, fallbackText): return .clip(id: id, fallbackText: fallbackText)
        case let .text(text): return .text(text)
        }
    }

    // MARK: AR ground path (core/ArRoutePath.kt, ArProjection.kt, PathHazard.kt)

    static func pathLength(_ distanceToTargetMeters: Double) -> Double {
        groundPathLength(distanceToTargetMeters)
    }

    /// Route vertices as (x, z) metre offsets from the camera in a frame whose
    /// -Z axis points at compass bearing `frameBearingDeg`.
    static func groundPath(
        userPosition: LatLng,
        routePoints: [LatLng],
        frameBearingDeg: Double,
        maxVisibleMeters: Double
    ) -> [Vec3] {
        let raw = routeGroundPath(
            userPosition: userPosition,
            routePoints: routePoints,
            headingDeg: frameBearingDeg,
            maxVisibleMeters: maxVisibleMeters
        )
        return resamplePath(raw, spacingMeters: 0.8)
    }

    enum Hazard { case clear, turn, stairs }

    static func hazard(_ cameraRelative: [Vec3], stairsAhead: Bool) -> Hazard {
        switch pathHazardFor(cameraRelative, stairsAhead) {
        case .clear: return .clear
        case .turn: return .turn
        case .stairs: return .stairs
        }
    }

    // MARK: Routing (core/PathNetwork.kt)

    /// The PATHS editor that draws the campus graph is phase 2 on iPhone, so
    /// the network is empty and routeTo returns its straight-line route. The
    /// call still goes through core so a loaded network later needs no
    /// changes here.
    static func route(from: LatLng, to: LatLng) -> [LatLng] {
        routeTo(PathNetwork(), from, to, .walk).points
    }

    // MARK: Demo campus (core/DemoCampus.kt)

    static func demoLandmarks(centre: LatLng, headingDeg: Double) -> [Landmark] {
        DemoCampus.generate(centre, headingDeg: headingDeg)
    }

    static func step(towards from: LatLng, _ to: LatLng, metres: Double) -> LatLng {
        stepTowards(from, to, metres)
    }

    static func step(from: LatLng, bearingDeg: Double, metres: Double) -> LatLng {
        stepAlongBearing(from, bearingDeg, metres)
    }
}
