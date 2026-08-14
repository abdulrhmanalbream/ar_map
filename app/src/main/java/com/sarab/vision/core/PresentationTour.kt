package com.sarab.vision.core

/**
 * A scripted tour that demonstrates the system without depending on anything
 * that can fail in a meeting room.
 *
 * ## Why this exists
 *
 * A live demo of this app normally needs GPS (weak or absent indoors), ARCore
 * tracking (needs texture, light and motion) and a network for map tiles
 * (unfamiliar wifi). On stage, any one of those failing takes the whole
 * demonstration with it, and the audience sees a broken product rather than
 * an unlucky venue.
 *
 * The tour therefore drives simulated position and heading through a fixed
 * script. Nothing it shows requires a sensor or a connection.
 *
 * ## What it is scripted to show
 *
 * The order is chosen to build an argument, not just to move a dot:
 *  1. a destination is chosen and the compass locks on
 *  2. the distance counts down as the user "walks"
 *  3. close in, AR takes over from the compass
 *  4. arrival lands on two buildings 20m apart, which is where the app
 *     admits GPS cannot tell them apart and asks -- the honesty that
 *     separates it from a naive implementation
 *  5. the same route is re-run by bike and by car, which take different
 *     paths, showing routing that Google Maps cannot do on a campus
 */

/** One beat of the scripted tour. */
data class TourStep(
    val titleAr: String,
    val captionAr: String,
    /** How long this beat runs, in milliseconds. */
    val durationMs: Long,
    val action: TourAction
)

/** What the app should do during a beat. */
sealed interface TourAction {
    /** Sit still and let the narration land. */
    data object Hold : TourAction

    /** Select a destination by name fragment. */
    data class SelectTarget(val nameFragment: String) : TourAction

    /** Walk towards the target at a given speed. */
    data class Walk(val metresPerSecond: Double) : TourAction

    /** Jump to just short of the target, to reach arrival quickly. */
    data class JumpNear(val metresShort: Double) : TourAction

    /** Turn on the spot, to show the compass responding. */
    data class Turn(val degreesPerSecond: Double) : TourAction

    /** Switch travel mode, to show routing differ by mode. */
    data class SetMode(val mode: TravelMode) : TourAction

    /** Open the map. */
    data object ShowMap : TourAction

    /** Return to the camera. */
    data object ShowCamera : TourAction
}

/**
 * The script.
 *
 * Timings are deliberately generous: a presenter talks over each beat, and a
 * demo that races ahead of the narration is worse than one that waits.
 */
object PresentationTour {

    val STEPS: List<TourStep> = listOf(
        TourStep(
            titleAr = "سراب — تنقّل داخل الحرم",
            captionAr = "الكاميرا والبوصلة تعملان بدون إنترنت",
            durationMs = 4_000,
            action = TourAction.Hold
        ),
        TourStep(
            titleAr = "اختيار الوجهة",
            captionAr = "كلية الحاسب الآلي · على بُعد 250 متراً",
            durationMs = 3_500,
            action = TourAction.SelectTarget("الملعب")
        ),
        TourStep(
            titleAr = "البوصلة تحدّد الاتجاه",
            captionAr = "الوجهة مثبّتة على اتجاهها الحقيقي في الشريط العلوي",
            durationMs = 4_000,
            action = TourAction.Turn(degreesPerSecond = 22.0)
        ),
        TourStep(
            titleAr = "التوجيه أثناء المشي",
            captionAr = "المسافة تتناقص والتعليمات تتغيّر تلقائياً",
            durationMs = 7_000,
            action = TourAction.Walk(metresPerSecond = 22.0)
        ),
        TourStep(
            titleAr = "الاقتراب — يتحوّل إلى AR",
            captionAr = "تحت 30 متراً يُرسم المسار على الأرض الحقيقية",
            durationMs = 5_000,
            action = TourAction.JumpNear(metresShort = 24.0)
        ),
        TourStep(
            titleAr = "الوصول",
            captionAr = "مبنيان متقاربان — دقة GPS لا تكفي للتفريق بينهما",
            durationMs = 6_500,
            action = TourAction.JumpNear(metresShort = 4.0)
        ),
        TourStep(
            titleAr = "نسأل بدل أن نخمّن",
            captionAr = "التطبيق يعترف بالحد ويطلب التأكيد من الصورة",
            durationMs = 5_000,
            action = TourAction.Hold
        ),
        TourStep(
            titleAr = "الخريطة والمسار",
            captionAr = "شبكة طرق مرسومة تعمل بدون إنترنت",
            durationMs = 5_000,
            action = TourAction.ShowMap
        ),
        TourStep(
            titleAr = "مسار الدراجة",
            captionAr = "الدراجات تستخدم طرق السيارات والمشاة معاً",
            durationMs = 4_500,
            action = TourAction.SetMode(TravelMode.BIKE)
        ),
        TourStep(
            titleAr = "مسار السيارة يختلف",
            captionAr = "السيارات ممنوعة من الممرات — مسار مختلف تماماً",
            durationMs = 5_000,
            action = TourAction.SetMode(TravelMode.CAR)
        ),
        TourStep(
            titleAr = "يعمل بالكامل بدون إنترنت",
            captionAr = "GPS والبوصلة والتوجيه — لا خادم ولا اتصال",
            durationMs = 5_000,
            action = TourAction.ShowCamera
        )
    )

    val totalDurationMs: Long get() = STEPS.sumOf { it.durationMs }
}

/** Where the tour currently is. */
data class TourState(
    val stepIndex: Int,
    val elapsedInStepMs: Long,
    val running: Boolean
) {
    val step: TourStep? get() = PresentationTour.STEPS.getOrNull(stepIndex)

    val finished: Boolean get() = stepIndex >= PresentationTour.STEPS.size

    /** Progress through the whole tour, 0..1, for a progress bar. */
    val overallProgress: Float
        get() {
            val total = PresentationTour.totalDurationMs
            if (total <= 0) return 0f
            val before = PresentationTour.STEPS.take(stepIndex).sumOf { it.durationMs }
            return ((before + elapsedInStepMs).toDouble() / total)
                .coerceIn(0.0, 1.0)
                .toFloat()
        }
}

/**
 * Advances the tour clock.
 *
 * Pure so the whole script can be verified without waiting a minute for it to
 * play out on a phone.
 */
fun advanceTour(state: TourState, deltaMs: Long): TourState {
    if (!state.running || state.finished) return state

    val step = state.step ?: return state.copy(running = false)
    val elapsed = state.elapsedInStepMs + deltaMs

    return if (elapsed >= step.durationMs) {
        val nextIndex = state.stepIndex + 1
        TourState(
            stepIndex = nextIndex,
            elapsedInStepMs = 0,
            // Stop cleanly at the end rather than running past it.
            running = nextIndex < PresentationTour.STEPS.size
        )
    } else {
        state.copy(elapsedInStepMs = elapsed)
    }
}

/** How far the simulated walker should move this tick, in metres. */
fun tourWalkDistance(state: TourState, deltaMs: Long): Double {
    val action = state.step?.action
    return if (action is TourAction.Walk) {
        action.metresPerSecond * (deltaMs / 1000.0)
    } else {
        0.0
    }
}

/** How far the simulated heading should turn this tick, in degrees. */
fun tourTurnDegrees(state: TourState, deltaMs: Long): Double {
    val action = state.step?.action
    return if (action is TourAction.Turn) {
        action.degreesPerSecond * (deltaMs / 1000.0)
    } else {
        0.0
    }
}
