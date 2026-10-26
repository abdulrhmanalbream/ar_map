package com.sarab.vision.ui

/** Existing and background replies must not speak when the screen is reopened. */
internal class VisionReplyReadGate(initialRevision: Long) {
    private var consumedRevision = initialRevision
    fun consume(revision: Long, foreground: Boolean): Boolean {
        if (revision <= consumedRevision) return false
        consumedRevision = revision
        return foreground
    }
}

internal fun visionUtteranceMatches(generation: Long, utteranceId: String?): Boolean =
    utteranceId?.startsWith("vision-$generation-") == true

/** Keep a complete answer while respecting each TTS engine's utterance limit. */
internal fun visionSpeechChunks(text: String, maxLength: Int): List<String> {
    require(maxLength >= 2)
    val value = text.trim()
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < value.length) {
        var end = minOf(start + maxLength, value.length)
        if (end < value.length && value[end - 1].isHighSurrogate() && value[end].isLowSurrogate()) end--
        if (end < value.length) {
            val boundary = value.lastIndexOf(' ', end - 1)
            if (boundary > start + maxLength / 2) end = boundary
        }
        chunks.add(value.substring(start, end))
        start = end
        while (start < value.length && value[start].isWhitespace()) start++
    }
    return chunks
}
