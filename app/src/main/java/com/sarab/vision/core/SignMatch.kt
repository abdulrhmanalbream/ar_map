package com.sarab.vision.core

import kotlin.math.ln

/**
 * Telling near-identical buildings apart by reading their entrance plaques.
 *
 * ## The problem this exists for
 *
 * On this campus the colleges are visually interchangeable: the same sandstone
 * facade, the same pointed arches, the same proportions, built to one template.
 * A person standing in front of one cannot name it from the architecture, and
 * neither can image matching. GPS cannot separate them either -- the entrances
 * sit tens of metres apart, inside the error of a phone fix.
 *
 * What *is* distinctive is the plaque above every door, in Arabic and English,
 * high contrast, always in the same place.
 *
 * ## Why plain word overlap is not enough
 *
 * The English names are:
 *
 *     College of Noble Quran and Islamic Studies
 *     College of Hadith and Islamic Studies
 *     College of Islamic Propogation and Principles of Religion
 *
 * Five of seven words are shared between the first two. Counting matching
 * words would score them nearly equally and the app would confidently name the
 * wrong building -- the single worst outcome available, because a user who is
 * told the wrong thing walks away, while a user told "I am not sure" looks up.
 *
 * So words are weighted by how *rare* they are across the candidate set
 * (inverse document frequency). "college", "islamic", "studies", "كلية",
 * "الدراسات" appear everywhere and count for almost nothing; "hadith",
 * "quran", "الحديث", "الشريعة" appear once and carry the decision. The weights
 * are computed from whichever buildings are actually in contention, so they
 * stay correct as the campus grows.
 *
 * ## Why it refuses to guess
 *
 * A match must beat the runner-up by a clear margin. Half-read signs, glare
 * and motion blur are normal, and returning [SignMatch.Unsure] costs the user
 * a second look while returning a wrong name costs them the walk.
 */

/** How confident the reader is about which building it is looking at. */
sealed interface SignMatch {
    /** One building clearly won. */
    data class Found(
        val landmark: Landmark,
        val score: Double,
        /** How far clear of the runner-up, 0..1. Higher is safer. */
        val margin: Double
    ) : SignMatch

    /** Text was read, but it does not clearly name any candidate. */
    data class Unsure(val bestGuess: Landmark?, val reason: String) : SignMatch

    /** Nothing legible in the frame. */
    data object NoText : SignMatch
}

/**
 * Minimum weighted score for a match to be considered at all.
 *
 * Below this the reader has essentially only matched filler words like
 * "college of", which every building shares.
 */
const val SIGN_MIN_SCORE = 0.34

/**
 * How far ahead of the runner-up the winner must be.
 *
 * Set high on purpose. The failure this guards against is naming the wrong one
 * of two near-identical neighbours, which is exactly the situation the feature
 * exists for.
 */
const val SIGN_MIN_MARGIN = 0.25

/** Words too common to carry meaning, stripped before scoring. */
private val STOP_WORDS = setOf(
    "of", "and", "the", "for", "in",
    "في", "من", "و", "ال"
)

/**
 * Splits text into comparable tokens.
 *
 * Reuses [normaliseForSearch] so Arabic folds exactly the way it does
 * everywhere else in the app -- alef variants, taa marbuta and tashkeel all
 * normalise, which matters because OCR is inconsistent about them.
 */
fun signTokens(text: String): List<String> {
    return normaliseForSearch(text)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length > 1 && it !in STOP_WORDS }
}

/**
 * Everything a landmark might have written on its sign.
 *
 * The name is included alongside the explicit sign texts, so a landmark that
 * nobody has photographed a plaque for still has something to match.
 */
private fun corpusFor(landmark: Landmark): List<String> =
    (landmark.signTexts + landmark.name).filter { it.isNotBlank() }

/**
 * Matches text read off a sign against the buildings it could belong to.
 *
 * @param recognised the lines OCR returned, in any order
 * @param candidates the buildings in contention -- normally the handful within
 *   GPS range, not the whole campus, since narrowing by position first makes
 *   the rare-word weighting sharper
 */
fun matchSign(recognised: List<String>, candidates: List<Landmark>): SignMatch {
    val readTokens = recognised.flatMap { signTokens(it) }.toSet()
    if (readTokens.isEmpty()) return SignMatch.NoText
    if (candidates.isEmpty()) return SignMatch.Unsure(null, "لا توجد مبانٍ قريبة للمقارنة")

    // Document frequency across the candidates: how many buildings use each
    // word. A word on every sign tells us nothing.
    val documentFrequency = HashMap<String, Int>()
    val candidateTokens = candidates.associateWith { landmark ->
        val tokens = corpusFor(landmark).flatMap { signTokens(it) }.toSet()
        tokens.forEach { documentFrequency[it] = (documentFrequency[it] ?: 0) + 1 }
        tokens
    }

    val total = candidates.size
    fun weight(token: String): Double {
        val df = documentFrequency[token] ?: 0
        if (df == 0) return 0.0
        // Smoothed IDF. With one candidate every weight collapses to a
        // constant, which is correct -- there is nothing to discriminate
        // against, so any real overlap should win.
        return ln(1.0 + total.toDouble() / df)
    }

    // Score by how much of the DISTINCTIVE evidence in the read text points at
    // each candidate.
    //
    // The obvious alternative -- what fraction of the candidate's own name did
    // we manage to read -- is wrong, and the tests caught it: every plaque
    // carries both an Arabic and an English line, so reading one line cleanly
    // still covers only half the stored text and scored below the threshold.
    // Partial reads are the normal case, not the exception.
    val achieved = candidateTokens.mapValues { (_, tokens) ->
        tokens.filter { it in readTokens }.sumOf { weight(it) }
    }

    // Weight of what we read that belongs to ANY candidate. Text from a fire
    // exit sign or a passing bus is not evidence for or against anything.
    val attributable = readTokens.filter { documentFrequency.containsKey(it) }
        .sumOf { weight(it) }
    if (attributable <= 0.0) {
        return SignMatch.Unsure(null, "لم أتعرف على اسم واضح في اللوحة")
    }

    val ranked = achieved.entries.sortedByDescending { it.value }
    val best = ranked.first().key
    val bestWeight = ranked.first().value
    val runnerUpWeight = ranked.getOrNull(1)?.value ?: 0.0

    if (bestWeight <= 0.0) {
        return SignMatch.Unsure(null, "لم أتعرف على اسم واضح في اللوحة")
    }

    // Words unique to a single building are what actually decide this. If two
    // different buildings each have one of theirs in frame, the camera is
    // looking at two plaques and no answer about "the" building is honest.
    val namedExclusively = candidateTokens.count { (_, tokens) ->
        tokens.any { it in readTokens && documentFrequency[it] == 1 }
    }
    if (namedExclusively > 1) {
        return SignMatch.Unsure(best, "أرى أكثر من لافتة — قرّب الكاميرا على واحدة")
    }

    val score = bestWeight / attributable
    val margin = 1.0 - (runnerUpWeight / bestWeight)

    return when {
        score < SIGN_MIN_SCORE ->
            SignMatch.Unsure(null, "لم أتعرف على اسم واضح في اللوحة")

        margin < SIGN_MIN_MARGIN ->
            // Deliberately names the ambiguity rather than picking one. Two
            // buildings whose signs share their distinctive words are exactly
            // the case where a confident answer would be a lie.
            SignMatch.Unsure(best, "اللوحة تشبه أكثر من مبنى — قرّب الكاميرا من اللافتة")

        else -> SignMatch.Found(best, score, margin)
    }
}
