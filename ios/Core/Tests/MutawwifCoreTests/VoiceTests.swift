import XCTest
@testable import MutawwifCore

// Ports of NavigationSpeechTest, NavigationVoicePolicyTest, VoiceCuePlaybackTest
// and VoiceCueTest. The value of spoken guidance is set by how rarely it speaks.

private func clipId(_ s: NavigationSpeechSegment) -> String? {
    if case let .clip(id, _) = s { return id }
    return nil
}

final class NavigationSpeechTests: XCTestCase {

    func testDirectionBoundariesAgreeWithExistingVisualAndVoicePolicy() {
        let cases: [(Double, NavigationDirection)] = [
            (-180.0, .back), (-125.01, .back), (-125.0, .left), (-55.01, .left),
            (-55.0, .slightLeft), (-15.01, .slightLeft), (-15.0, .forward), (0.0, .forward),
            (15.0, .forward), (15.01, .slightRight), (55.0, .slightRight), (55.01, .right),
            (125.0, .right), (125.01, .back), (180.0, .back),
        ]
        for (degrees, expected) in cases {
            let mode = GuidanceMode.compass(distanceMeters: 163.0, bearingDegrees: 0.0, relativeDegrees: degrees)
            XCTAssertEqual(expected, navigationDirection(degrees), "at \(degrees)")
            XCTAssertEqual(expected.clipId, clipId(navigationSpeechPlan(mode, "وجهة")[0]))
            let identity: String
            switch expected {
            case .forward: identity = "امشِ للأمام"
            case .right: identity = "اتجه يميناً"
            case .left: identity = "اتجه يساراً"
            case .back: identity = "استدر للخلف"
            case .slightRight: identity = "مِل يميناً قليلاً"
            case .slightLeft: identity = "مِل يساراً قليلاً"
            }
            XCTAssertTrue(voiceCueAr(mode, "وجهة")!.hasPrefix(identity))
            XCTAssertTrue(instructionAr(mode, "وجهة").hasPrefix(identity))
        }
    }

    func testDistanceClipsFollowExistingBucketsAcrossStepBoundaries() {
        let cases: [(Double, Int)] = [
            (30.0, 25), (45.0, 50), (80.0, 75), (99.0, 100), (100.0, 100), (163.0, 150),
            (240.0, 250), (299.0, 300), (300.0, 300), (480.0, 500), (1999.0, 2000),
        ]
        for (distance, bucket) in cases {
            XCTAssertEqual("nav_ar_distance_\(bucket)", clipId(navigationDistanceSpeech(distance)))
        }
        XCTAssertEqual(25, NAVIGATION_DISTANCE_CLIPS.count)
        XCTAssertEqual(36, NAVIGATION_CLIP_IDS.count)
    }

    func testRareDistanceKeepsFullSpokenMetresWithoutClampingToLastRecording() {
        guard case let .text(text) = navigationDistanceSpeech(2160.0) else { return XCTFail("expected text") }
        XCTAssertEqual("المسافة المتبقية، 2200 متراً.", text)
        XCTAssertFalse(text.contains("كم"))
    }

    func testArrivalApproachAndSelectionKeepTheActualDestinationName() {
        let target = "بوابة أضافها المستخدم"
        let plans = [
            navigationSpeechPlan(.arrived(distanceMeters: 4.0), target),
            navigationSpeechPlan(.arApproach(distanceMeters: 18.0), target),
            navigationSelectionSpeech(target),
        ]
        for plan in plans {
            XCTAssertNotNil(clipId(plan.first!))
            XCTAssertEqual(.text(target), plan.last)
        }
    }

    func testAmbiguityNeverSubstitutesAnArrivalOrSpeaksAnUnconfirmedName() {
        let plan = navigationSpeechPlan(.ambiguous(distanceMeters: 4.0, candidates: []), "غير مؤكد")
        XCTAssertEqual(1, plan.count)
        XCTAssertEqual("nav_ar_ambiguous", clipId(plan[0]))
    }

    func testExplicitRepeatUsesVoiceDistanceAndNoFixDoesNotNagAutomatically() {
        XCTAssertTrue(navigationSpeechPlan(.noFix, "وجهة").isEmpty)
        let noFix = navigationSpeechPlan(.noFix, "وجهة", manualRepeat: true)
        XCTAssertEqual(1, noFix.count)
        XCTAssertEqual("nav_ar_no_fix", clipId(noFix[0]))
        let plan = navigationSpeechPlan(.compass(distanceMeters: 163.0, bearingDegrees: 0.0, relativeDegrees: 90.0), "وجهة", manualRepeat: true)
        XCTAssertEqual("nav_ar_right", clipId(plan[0]))
        XCTAssertEqual("nav_ar_distance_150", clipId(plan[1]))
    }
}

final class NavigationVoicePolicyTests: XCTestCase {

    private func eligible(_ fg: Bool, _ rec: Bool, _ target: Bool, _ eye: Bool, _ glasses: Bool, _ muted: Bool) -> Bool {
        navigationVoiceEligible(foreground: fg, recognitionMode: rec, hasTarget: target, eyeSource: eye, glassesConnected: glasses, muted: muted)
    }

    func testPhoneNavigationSpeaksWithoutGlassesButRecognitionDoesNot() {
        XCTAssertTrue(eligible(true, false, true, false, false, false))
        XCTAssertFalse(eligible(true, true, true, false, false, false))
        XCTAssertFalse(eligible(true, true, true, true, true, false))
    }

    func testBackgroundMutedAndClearedDestinationsStaySilentForBothSources() {
        for eye in [false, true] {
            XCTAssertFalse(eligible(false, false, true, eye, true, false))
            XCTAssertFalse(eligible(true, false, true, eye, true, true))
            XCTAssertFalse(eligible(true, false, false, eye, true, false))
        }
    }

    func testEyeNavigationStillRequiresItsDisplayConnection() {
        XCTAssertTrue(eligible(true, false, true, true, true, false))
        XCTAssertFalse(eligible(true, false, true, true, false, false))
    }

    private func voice(_ id: String, quality: Int = 300, country: String = "SA", language: String = "ar",
                       network: Bool = false, installed: Bool = true) -> NavigationVoiceOption {
        NavigationVoiceOption(id: id, language: language, country: country, quality: quality, requiresNetwork: network, installed: installed)
    }

    func testUnavailableOrNetworkArabicNeverReplacesInstalledOfflineSpeech() {
        XCTAssertEqual("local", selectNavigationVoice([
            voice("cloud", quality: 500, network: true), voice("missing", quality: 500, installed: false),
            voice("local", quality: 200),
        ])?.id)
    }

    func testQualityWinsAndSaudiArabicWinsAnEqualQualityTie() {
        XCTAssertEqual("quality", selectNavigationVoice([voice("saudi", quality: 300), voice("quality", quality: 400, country: "EG")])?.id)
        XCTAssertEqual("saudi", selectNavigationVoice([voice("egyptian", country: "EG"), voice("saudi")])?.id)
    }

    func testNoInstalledOfflineArabicStaysSilent() {
        XCTAssertNil(selectNavigationVoice([voice("english", language: "en"), voice("network", network: true), voice("not-installed", installed: false)]))
        XCTAssertNil(selectNavigationVoice([]))
    }
}

final class VoiceCuePlaybackTests: XCTestCase {

    func testRoutineUpdateAfterSixSecondsWaitsForTheWholeDirectionAndDistancePhrase() {
        let state = nextVoiceCue(state: VoiceState(), cue: "اتجه قليلاً إلى اليمين، على بعد 1300 متر", urgent: false, nowMs: 10_000).0
        let changed = "اتجه يميناً، على بعد 1300 متر"
        let deferred = nextVoiceCue(state: state, cue: changed, urgent: false, nowMs: 16_100, playbackBusy: true)
        XCTAssertEqual(state, deferred.0)
        XCTAssertNil(deferred.1)
        let after = nextVoiceCue(state: deferred.0, cue: changed, urgent: false, nowMs: 17_900, playbackBusy: false)
        XCTAssertEqual(changed, after.1)
        XCTAssertEqual(17_900, after.0.lastAtMs)
    }

    func testManyBusyTicksDoNotConsumeLatestInstructionOrAdvanceItsTimestamp() {
        let state = VoiceState(lastText: "الأمام", lastAtMs: 10_000)
        var deferred = state
        for time in stride(from: Int64(16_000), through: 20_000, by: 250) {
            let result = nextVoiceCue(state: deferred, cue: "يمين \(time)", urgent: false, nowMs: time, playbackBusy: true)
            XCTAssertEqual(state, result.0)
            XCTAssertNil(result.1)
            deferred = result.0
        }
        XCTAssertEqual("آخر اتجاه", nextVoiceCue(state: deferred, cue: "آخر اتجاه", urgent: false, nowMs: 20_001).1)
    }

    func testUrgentArrivalInterruptsBusyPlaybackBeforeTheSixSecondGap() {
        let result = nextVoiceCue(state: VoiceState(lastText: "اتجه يميناً", lastAtMs: 10_000), cue: "وصلت إلى وجهتك", urgent: true, nowMs: 10_500, playbackBusy: true)
        XCTAssertEqual("وصلت إلى وجهتك", result.1)
        XCTAssertEqual(10_500, result.0.lastAtMs)
    }

    func testCompletionStillPreservesDedupAndMinimumSpacingForShortPhrases() {
        let state = VoiceState(lastText: "الأمام", lastAtMs: 10_000)
        XCTAssertNil(nextVoiceCue(state: state, cue: "الأمام", urgent: false, nowMs: 30_000).1)
        XCTAssertNil(nextVoiceCue(state: state, cue: "يمين", urgent: false, nowMs: 15_999).1)
        XCTAssertEqual("يمين", nextVoiceCue(state: state, cue: "يمين", urgent: false, nowMs: 16_000).1)
    }
}

final class VoiceCueTests: XCTestCase {

    func testBucketsAreCoarseFarAwayAndFinerCloseIn() {
        XCTAssertEqual(500, voiceDistanceBucket(480.0))
        XCTAssertEqual(400, voiceDistanceBucket(420.0))
        XCTAssertEqual(250, voiceDistanceBucket(240.0))
        XCTAssertEqual(150, voiceDistanceBucket(160.0))
        XCTAssertEqual(75, voiceDistanceBucket(80.0))
        XCTAssertEqual(50, voiceDistanceBucket(45.0))
    }

    func testBucketNeverReachesZero() {
        XCTAssertGreaterThan(voiceDistanceBucket(3.0), 0)
    }

    func testNoFixStaysSilent() {
        XCTAssertNil(voiceCueAr(.noFix, "X"))
    }

    func testCompassCueCarriesDirectionAndBucketedDistance() {
        let cue = voiceCueAr(.compass(distanceMeters: 163.0, bearingDegrees: 90.0, relativeDegrees: 90.0), "X")!
        XCTAssertTrue(cue.hasPrefix("اتجه يميناً"))
        XCTAssertTrue(cue.contains("150"))
    }

    func testSmallDistanceChangesDoNotChangeThePhrase() {
        XCTAssertEqual(
            voiceCueAr(.compass(distanceMeters: 163.0, bearingDegrees: 0.0, relativeDegrees: 5.0), "X"),
            voiceCueAr(.compass(distanceMeters: 157.0, bearingDegrees: 0.0, relativeDegrees: 5.0), "X")
        )
    }

    func testArrivalNamesTheDestination() {
        XCTAssertTrue(voiceCueAr(.arrived(distanceMeters: 4.0), "كلية الشريعة")!.contains("كلية الشريعة"))
    }

    func testANewPhraseIsSpokenAndRecorded() {
        let (state, spoken) = nextVoiceCue(state: VoiceState(), cue: "امشِ للأمام", urgent: false, nowMs: 10_000)
        XCTAssertEqual("امشِ للأمام", spoken)
        XCTAssertEqual("امشِ للأمام", state.lastText)
    }

    func testTheSamePhraseIsNeverRepeated() {
        XCTAssertNil(nextVoiceCue(state: VoiceState(lastText: "امشِ للأمام", lastAtMs: 10_000), cue: "امشِ للأمام", urgent: false, nowMs: 60_000).1)
    }

    func testRoutinePhrasesKeepTheMinimumGap() {
        let state = VoiceState(lastText: "قديم", lastAtMs: 10_000)
        let (unchanged, spoken) = nextVoiceCue(state: state, cue: "جديد", urgent: false, nowMs: 10_000 + VOICE_MIN_GAP_MS - 1)
        XCTAssertNil(spoken)
        XCTAssertEqual(state, unchanged)
    }

    func testUrgentPhrasesCutThroughTheGap() {
        XCTAssertNotNil(nextVoiceCue(state: VoiceState(lastText: "قديم", lastAtMs: 10_000), cue: "وصلت", urgent: true, nowMs: 10_500).1)
    }
}
