package com.sarab.vision.ui

import com.sarab.vision.vision.VisionItem

/** New results may narrate once; neither reopening nor unmuting replays a stale result. */
internal class VisionNarrationGate {
    private var consumedScene: String? = null
    private val lastSpoken = linkedMapOf<String, Long>()

    fun shouldSpeak(sceneId: String, key: String, capturedAt: Long, now: Long, resumedAt: Long,
        muted: Boolean, busy: Boolean): Boolean {
        if (capturedAt < resumedAt || now < capturedAt || now - capturedAt > 60_000 || muted) {
            consumedScene = sceneId
            return false
        }
        if (consumedScene == sceneId || busy) return false
        consumedScene = sceneId
        val last = lastSpoken[key]
        if (last != null && now - last < 120_000) return false
        lastSpoken.remove(key)
        lastSpoken[key] = now
        if (lastSpoken.size > 32) lastSpoken.remove(lastSpoken.keys.first())
        return true
    }
}

internal fun visionLandmarkNarration(item: VisionItem, language: String): String {
    val devotional = item.devotionals.firstOrNull()
    return listOfNotNull(item.description.takeIf { it.isNotBlank() },
        devotional?.title?.takeIf { it.isNotBlank() }, devotional?.note?.takeIf { it.isNotBlank() },
        (if (language == "ar") devotional?.arabicText else devotional?.translation)?.takeIf { it.isNotBlank() })
        .joinToString("\n\n")
}
