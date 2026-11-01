import Foundation

// A scripted tour that demonstrates the system without depending on anything
// that can fail in a meeting room.
//
// A live demo normally needs GPS (weak or absent indoors), AR tracking (needs
// texture, light and motion) and a network for map tiles (unfamiliar wifi).
// On stage, any one of those failing takes the whole demonstration with it.
// The tour therefore drives simulated position and heading through a fixed
// script. Nothing it shows requires a sensor or a connection.
//
// The order builds an argument: destination chosen and compass locks on;
// distance counts down; close in, AR takes over; arrival lands on two
// buildings 20m apart, where the app admits GPS cannot tell them apart and
// asks; the route is re-run by bike and by car, which take different paths.

/// What the app should do during a beat.
public enum TourAction: Equatable {
    /// Sit still and let the narration land.
    case hold
    /// Select a destination by name fragment.
    case selectTarget(nameFragment: String)
    /// Walk towards the target at a given speed.
    case walk(metresPerSecond: Double)
    /// Jump to just short of the target, to reach arrival quickly.
    case jumpNear(metresShort: Double)
    /// Turn on the spot, to show the compass responding.
    case turn(degreesPerSecond: Double)
    /// Switch travel mode, to show routing differ by mode.
    case setMode(mode: TravelMode)
    /// Open the map.
    case showMap
    /// Return to the camera.
    case showCamera
}

/// One beat of the scripted tour.
public struct TourStep: Equatable {
    public var titleAr: String
    public var captionAr: String
    /// How long this beat runs, in milliseconds.
    public var durationMs: Int64
    public var action: TourAction

    public init(titleAr: String, captionAr: String, durationMs: Int64, action: TourAction) {
        self.titleAr = titleAr
        self.captionAr = captionAr
        self.durationMs = durationMs
        self.action = action
    }
}

/// The script.
///
/// Timings are deliberately generous: a presenter talks over each beat, and a
/// demo that races ahead of the narration is worse than one that waits.
public enum PresentationTour {

    public static let STEPS: [TourStep] = [
        TourStep(
            titleAr: "المطوف الذكي — تنقّل داخل الحرم",
            captionAr: "الكاميرا والبوصلة تعملان بدون إنترنت",
            durationMs: 4_000,
            action: .hold
        ),
        TourStep(
            titleAr: "اختيار الوجهة",
            captionAr: "كلية الحاسب الآلي · على بُعد 250 متراً",
            durationMs: 3_500,
            action: .selectTarget(nameFragment: "الملعب")
        ),
        TourStep(
            titleAr: "البوصلة تحدّد الاتجاه",
            captionAr: "الوجهة مثبّتة على اتجاهها الحقيقي في الشريط العلوي",
            durationMs: 4_000,
            action: .turn(degreesPerSecond: 22.0)
        ),
        TourStep(
            titleAr: "التوجيه أثناء المشي",
            captionAr: "المسافة تتناقص والتعليمات تتغيّر تلقائياً",
            durationMs: 7_000,
            action: .walk(metresPerSecond: 22.0)
        ),
        TourStep(
            titleAr: "الاقتراب — يتحوّل إلى AR",
            captionAr: "تحت 30 متراً يُرسم المسار على الأرض الحقيقية",
            durationMs: 5_000,
            action: .jumpNear(metresShort: 24.0)
        ),
        TourStep(
            titleAr: "الوصول",
            captionAr: "مبنيان متقاربان — دقة GPS لا تكفي للتفريق بينهما",
            durationMs: 6_500,
            action: .jumpNear(metresShort: 4.0)
        ),
        TourStep(
            titleAr: "نسأل بدل أن نخمّن",
            captionAr: "التطبيق يعترف بالحد ويطلب التأكيد من الصورة",
            durationMs: 5_000,
            action: .hold
        ),
        TourStep(
            titleAr: "الخريطة والمسار",
            captionAr: "شبكة طرق مرسومة تعمل بدون إنترنت",
            durationMs: 5_000,
            action: .showMap
        ),
        TourStep(
            titleAr: "مسار الدراجة",
            captionAr: "الدراجات تستخدم طرق السيارات والمشاة معاً",
            durationMs: 4_500,
            action: .setMode(mode: .bike)
        ),
        TourStep(
            titleAr: "مسار السيارة يختلف",
            captionAr: "السيارات ممنوعة من الممرات — مسار مختلف تماماً",
            durationMs: 5_000,
            action: .setMode(mode: .car)
        ),
        TourStep(
            titleAr: "يعمل بالكامل بدون إنترنت",
            captionAr: "GPS والبوصلة والتوجيه — لا خادم ولا اتصال",
            durationMs: 5_000,
            action: .showCamera
        ),
    ]

    public static var totalDurationMs: Int64 { STEPS.reduce(Int64(0)) { $0 + $1.durationMs } }
}

/// Where the tour currently is.
public struct TourState: Equatable {
    public var stepIndex: Int
    public var elapsedInStepMs: Int64
    public var running: Bool

    public init(stepIndex: Int, elapsedInStepMs: Int64, running: Bool) {
        self.stepIndex = stepIndex
        self.elapsedInStepMs = elapsedInStepMs
        self.running = running
    }

    public var step: TourStep? {
        let steps = PresentationTour.STEPS
        return stepIndex >= 0 && stepIndex < steps.count ? steps[stepIndex] : nil
    }

    public var finished: Bool { stepIndex >= PresentationTour.STEPS.count }

    /// Progress through the whole tour, 0..1, for a progress bar.
    public var overallProgress: Float {
        let total = PresentationTour.totalDurationMs
        if total <= 0 { return 0 }
        let before = PresentationTour.STEPS.prefix(max(stepIndex, 0)).reduce(Int64(0)) { $0 + $1.durationMs }
        return Float((Double(before + elapsedInStepMs) / Double(total)).coerced(0.0, 1.0))
    }
}

/// Advances the tour clock.
///
/// Pure so the whole script can be verified without waiting a minute for it to
/// play out on a phone.
public func advanceTour(_ state: TourState, _ deltaMs: Int64) -> TourState {
    if !state.running || state.finished { return state }

    guard let step = state.step else {
        var s = state
        s.running = false
        return s
    }
    let elapsed = state.elapsedInStepMs + deltaMs

    if elapsed >= step.durationMs {
        let nextIndex = state.stepIndex + 1
        return TourState(
            stepIndex: nextIndex,
            elapsedInStepMs: 0,
            // Stop cleanly at the end rather than running past it.
            running: nextIndex < PresentationTour.STEPS.count
        )
    }
    var s = state
    s.elapsedInStepMs = elapsed
    return s
}

/// How far the simulated walker should move this tick, in metres.
public func tourWalkDistance(_ state: TourState, _ deltaMs: Int64) -> Double {
    if case let .some(.walk(mps)) = state.step?.action {
        return mps * (Double(deltaMs) / 1000.0)
    }
    return 0.0
}

/// How far the simulated heading should turn this tick, in degrees.
public func tourTurnDegrees(_ state: TourState, _ deltaMs: Int64) -> Double {
    if case let .some(.turn(dps)) = state.step?.action {
        return dps * (Double(deltaMs) / 1000.0)
    }
    return 0.0
}
