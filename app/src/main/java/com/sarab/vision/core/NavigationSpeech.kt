package com.sarab.vision.core

internal sealed interface NavigationSpeechSegment {
    data class Clip(val id: String, val fallbackText: String) : NavigationSpeechSegment
    data class Text(val text: String) : NavigationSpeechSegment
}

internal enum class NavigationDirection(val clipId: String, val spokenText: String) {
    FORWARD("nav_ar_forward", "تابع السير إلى الأمام."),
    RIGHT("nav_ar_right", "اتجه إلى اليمين."),
    LEFT("nav_ar_left", "اتجه إلى اليسار."),
    BACK("nav_ar_back", "استدر إلى الخلف."),
    SLIGHT_RIGHT("nav_ar_slight_right", "اتجه قليلاً إلى اليمين."),
    SLIGHT_LEFT("nav_ar_slight_left", "اتجه قليلاً إلى اليسار."),
}

/** Same thresholds as voiceCueAr/instructionAr; playback never decides the route. */
internal fun navigationDirection(relativeDegrees: Double): NavigationDirection = when {
    kotlin.math.abs(relativeDegrees) <= 15 -> NavigationDirection.FORWARD
    relativeDegrees > 125 || relativeDegrees < -125 -> NavigationDirection.BACK
    relativeDegrees > 55 -> NavigationDirection.RIGHT
    relativeDegrees < -55 -> NavigationDirection.LEFT
    relativeDegrees > 0 -> NavigationDirection.SLIGHT_RIGHT
    else -> NavigationDirection.SLIGHT_LEFT
}

private val NAVIGATION_DISTANCE_WORDS = mapOf(
    25 to "خمسة وعشرون مترًا", 50 to "خمسون مترًا", 75 to "خمسة وسبعون مترًا",
    100 to "مئة متر", 150 to "مئة وخمسون مترًا", 200 to "مئتا متر", 250 to "مئتان وخمسون مترًا",
    300 to "ثلاثمئة متر", 400 to "أربعمئة متر", 500 to "خمسمئة متر", 600 to "ستمئة متر",
    700 to "سبعمئة متر", 800 to "ثمانمئة متر", 900 to "تسعمئة متر", 1000 to "ألف متر",
    1100 to "ألف ومئة متر", 1200 to "ألف ومئتا متر", 1300 to "ألف وثلاثمئة متر",
    1400 to "ألف وأربعمئة متر", 1500 to "ألف وخمسمئة متر", 1600 to "ألف وستمئة متر",
    1700 to "ألف وسبعمئة متر", 1800 to "ألف وثمانمئة متر", 1900 to "ألف وتسعمئة متر", 2000 to "ألفا متر",
)
internal val NAVIGATION_DISTANCE_CLIPS = NAVIGATION_DISTANCE_WORDS.keys
internal val NAVIGATION_CLIP_IDS = NavigationDirection.entries.map { it.clipId }.toSet() +
    setOf("nav_ar_approach", "nav_ar_arrived", "nav_ar_ambiguous", "nav_ar_selected", "nav_ar_no_fix") +
    NAVIGATION_DISTANCE_CLIPS.map { "nav_ar_distance_$it" }

/** Complete spoken units, never the compact م/كم strings used by the display. */
internal fun navigationDistanceSpeech(meters: Double): NavigationSpeechSegment {
    val bucket = voiceDistanceBucket(meters)
    val words = NAVIGATION_DISTANCE_WORDS[bucket] ?: "$bucket متراً"
    val sentence = "المسافة المتبقية، $words."
    return if (bucket in NAVIGATION_DISTANCE_CLIPS)
        NavigationSpeechSegment.Clip("nav_ar_distance_$bucket", sentence)
    else NavigationSpeechSegment.Text(sentence)
}

private fun withDestination(clip: NavigationSpeechSegment.Clip, targetName: String): List<NavigationSpeechSegment> =
    listOf(clip) + targetName.trim().takeIf { it.isNotEmpty() }?.let { listOf(NavigationSpeechSegment.Text(it)) }.orEmpty()

/** Called only after the existing voice timing policy accepts a cue, or for an explicit repeat. */
internal fun navigationSpeechPlan(mode: GuidanceMode, targetName: String, manualRepeat: Boolean = false): List<NavigationSpeechSegment> =
    when (mode) {
        GuidanceMode.NoFix -> if (manualRepeat)
            listOf(NavigationSpeechSegment.Clip("nav_ar_no_fix", "ننتظر تحديد موقعك.")) else emptyList()
        is GuidanceMode.Compass -> navigationDirection(mode.relativeDegrees).let { direction ->
            listOf(NavigationSpeechSegment.Clip(direction.clipId, direction.spokenText), navigationDistanceSpeech(mode.distanceMeters))
        }
        is GuidanceMode.ArApproach -> withDestination(
            NavigationSpeechSegment.Clip("nav_ar_approach", "وجهتك أمامك. اتبع المسار الظاهر على الأرض."), targetName)
        is GuidanceMode.Arrived -> withDestination(
            NavigationSpeechSegment.Clip("nav_ar_arrived", "لقد وصلت إلى وجهتك."), targetName)
        is GuidanceMode.Ambiguous -> listOf(
            NavigationSpeechSegment.Clip("nav_ar_ambiguous", "أنت قريب من عدة مبانٍ. تأكد من وجهتك بالنظر."))
    }

internal fun navigationSelectionSpeech(targetName: String): List<NavigationSpeechSegment> = withDestination(
    NavigationSpeechSegment.Clip("nav_ar_selected", "تم اختيار وجهتك."), targetName)
