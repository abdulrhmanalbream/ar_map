package com.sarab.vision.core

/** Recognition has its own speech; navigation works on either the phone or connected glasses. */
internal fun navigationVoiceEligible(
    foreground: Boolean,
    recognitionMode: Boolean,
    hasTarget: Boolean,
    eyeSource: Boolean,
    glassesConnected: Boolean,
    muted: Boolean,
): Boolean = foreground && !recognitionMode && hasTarget && !muted && (!eyeSource || glassesConnected)

internal data class NavigationVoiceOption(
    val id: String,
    val language: String,
    val country: String,
    val quality: Int,
    val requiresNetwork: Boolean,
    val installed: Boolean,
)

/** Never route an Arabic time-sensitive cue through an unavailable, remote or foreign voice. */
internal fun selectNavigationVoice(voices: List<NavigationVoiceOption>): NavigationVoiceOption? =
    voices.filter { it.language == "ar" && it.installed && !it.requiresNetwork }
        .sortedWith(compareByDescending<NavigationVoiceOption> { it.quality }
            .thenByDescending { it.country == "SA" }.thenBy { it.id })
        .firstOrNull()
