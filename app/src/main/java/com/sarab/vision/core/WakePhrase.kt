package com.sarab.vision.core

/**
 * After a bare wake phrase the wearer usually pauses before asking; Android's
 * recognizer ends the utterance at that pause, so the question arrives as the
 * next result. Six seconds covers the pause without letting an unrelated
 * conversation a minute later ride on an old wake-up.
 */
const val WAKE_FOLLOW_UP_MS = 6_000L

/**
 * The assistant's name as recognizers actually spell it. Matching is on
 * normaliseForSearch output, so hamza, shadda and taa-marbuta variants fold
 * together already; these cover the remaining real spellings: the vocative
 * attached to the noun, the definite form, and Latin transliterations that
 * English/Indonesian/Turkish recognizers produce for the Arabic name.
 */
private val WAKE_NAMES = listOf(
    "مطوف", "المطوف", "مطوفي",
    "mutawwif", "mutawif", "mutawef", "mutawwef", "motawef", "motawwif", "mutaif",
)

sealed class WakeResult {
    /** Nothing addressed to the assistant: this text must go nowhere. */
    object Ignored : WakeResult()
    /** Woken without a question yet; listen for the follow-up. */
    object Awake : WakeResult()
    data class Question(val text: String) : WakeResult()
}

/** Urdu keyboards/recognizers emit Persian yeh and keheh for the same letters. */
private fun foldScripts(input: String) = input
    .replace('ی', 'ي').replace('ک', 'ك').replace('ہ', 'ه').replace('ۃ', 'ه')

private fun tokens(text: String): List<String> =
    normaliseForSearch(foldScripts(text))
        .split(Regex("[\\s،,.!?؟:;\"'«»()\\-]+"))
        .filter { it.isNotBlank() }

private fun isName(token: String): Boolean {
    if (token in WAKE_NAMES) return true
    // The vocative is often glued on: "يامطوف".
    return token.startsWith("يا") && token.removePrefix("يا") in WAKE_NAMES
}

/**
 * Finds the wake phrase anywhere in an utterance; the words after it are the
 * question. Words BEFORE it are dropped on purpose: they were said to someone
 * else, and nothing heard before the wake phrase may leave the phone.
 */
fun matchWakePhrase(transcript: String): WakeResult {
    val words = tokens(transcript)
    val index = words.indexOfFirst(::isName)
    if (index < 0) return WakeResult.Ignored
    // Keep the original spelling of the question for the server, not the
    // search-normalised form: diacritics and hamza carry meaning there.
    val original = foldScripts(transcript).split(Regex("\\s+")).filter { it.isNotBlank() }
    val originalIndex = original.indexOfFirst { word -> tokens(word).any(::isName) }
    val rest = if (originalIndex >= 0) original.drop(originalIndex + 1) else words.drop(index + 1)
    val question = rest.joinToString(" ")
        .trim(' ', '،', ',', '.', '!', ':', '؛')
    return if (tokens(question).isEmpty()) WakeResult.Awake else WakeResult.Question(question)
}

/**
 * The hands-free turn: a bare wake phrase opens a short window in which the
 * next utterance is the question. Pure, so the six-second rule is tested
 * without a microphone.
 */
class WakeTurn(private val followUpMs: Long = WAKE_FOLLOW_UP_MS) {
    private var awakeUntil = -1L

    val awake: Boolean get() = awakeUntil >= 0

    fun isAwake(nowMs: Long): Boolean {
        if (awakeUntil in 0 until nowMs) awakeUntil = -1L
        return awakeUntil >= 0
    }

    /** Returns the question to submit, or null when nothing should be sent. */
    fun onTranscript(text: String, nowMs: Long): String? {
        val wasAwake = isAwake(nowMs)
        return when (val result = matchWakePhrase(text)) {
            is WakeResult.Question -> { awakeUntil = -1L; result.text }
            WakeResult.Awake -> { awakeUntil = nowMs + followUpMs; null }
            WakeResult.Ignored -> if (wasAwake && tokens(text).isNotEmpty()) {
                awakeUntil = -1L
                text.trim()
            } else null
        }
    }

    fun reset() { awakeUntil = -1L }
}
