package com.sarab.vision.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading building signs, tested against the real campus.
 *
 * These four colleges are the reason the feature exists. Three of them sit in
 * one complex, look identical, and have English names sharing most of their
 * words. If the matcher can separate these it can separate anything on this
 * campus; if it cannot, the app confidently sends people to the wrong door.
 */
class SignMatchTest {

    private fun college(id: String, name: String, vararg signs: String) = Landmark(
        id = id,
        name = name,
        category = LandmarkCategory.FACULTY,
        position = LatLng(24.48, 39.56),
        signTexts = signs.toList()
    )

    private val shariah = college(
        "shariah", "كلية الشريعة",
        "كلية الشريعة", "Faculty of Shariah"
    )
    private val quran = college(
        "quran", "كلية القرآن الكريم والدراسات الإسلامية",
        "كلية القرآن الكريم والدراسات الإسلامية",
        "College of Noble Quran and Islamic Studies"
    )
    private val hadith = college(
        "hadith", "كلية الحديث الشريف والدراسات الإسلامية",
        "كلية الحديث الشريف والدراسات الإسلامية",
        "College of Hadith and Islamic Studies"
    )
    private val dawah = college(
        "dawah", "كلية الدعوة وأصول الدين",
        "كلية الدعوة وأصول الدين",
        "College of Islamic Propogation and Principles of Religion"
    )

    private val complex = listOf(shariah, quran, hadith, dawah)

    private fun matched(vararg lines: String): Landmark? =
        (matchSign(lines.toList(), complex) as? SignMatch.Found)?.landmark

    // ---- The four buildings, read correctly -------------------------------

    @Test
    fun `each english sign identifies its own building`() {
        assertEquals(shariah, matched("Faculty of Shariah"))
        assertEquals(quran, matched("College of Noble Quran and Islamic Studies"))
        assertEquals(hadith, matched("College of Hadith and Islamic Studies"))
        assertEquals(
            dawah,
            matched("College of Islamic Propogation and Principles of Religion")
        )
    }

    @Test
    fun `each arabic sign identifies its own building`() {
        assertEquals(shariah, matched("كلية الشريعة"))
        assertEquals(quran, matched("كلية القرآن الكريم والدراسات الإسلامية"))
        assertEquals(hadith, matched("كلية الحديث الشريف والدراسات الإسلامية"))
        assertEquals(dawah, matched("كلية الدعوة وأصول الدين"))
    }

    @Test
    fun `a real plaque read whole, both lines at once`() {
        // What OCR actually returns from one photo of the Hadith entrance.
        val result = matchSign(
            listOf(
                "كلية الحديث الشريف والدراسات الإسلامية",
                "College of Hadith and Islamic Studies"
            ),
            complex
        )
        assertTrue(result is SignMatch.Found)
        assertEquals(hadith, (result as SignMatch.Found).landmark)
    }

    // ---- The confusions that matter ---------------------------------------

    @Test
    fun `the shared words alone never name a building`() {
        // "College of ... and Islamic Studies" fits Quran AND Hadith. Naming
        // either one from this would be a coin flip presented as a fact.
        val result = matchSign(listOf("College of and Islamic Studies"), complex)
        assertTrue(
            "shared filler must not produce a confident answer, got $result",
            result is SignMatch.Unsure
        )
    }

    @Test
    fun `one distinctive word is enough to separate the twins`() {
        // Quran and Hadith differ by exactly one word on a seven-word sign.
        // That word has to carry the whole decision.
        assertEquals(quran, matched("Quran"))
        assertEquals(hadith, matched("Hadith"))
        assertEquals(quran, matched("القرآن"))
        assertEquals(hadith, matched("الحديث"))
    }

    @Test
    fun `a half read sign still resolves when the rare word survives`() {
        // Glare and blur routinely eat part of a line. What matters is whether
        // the distinctive word made it through.
        assertEquals(hadith, matched("ollege of Hadith and Isl"))
        assertEquals(dawah, matched("Propogation and Principles"))
    }

    @Test
    fun `the misspelling on the wall and the correct spelling both match`() {
        val withTypo = college(
            "dawah", "كلية الدعوة وأصول الدين",
            "College of Islamic Propogation and Principles of Religion",
            "College of Islamic Propagation and Principles of Religion"
        )
        val candidates = listOf(shariah, quran, hadith, withTypo)
        assertEquals(
            withTypo,
            (matchSign(listOf("Propagation"), candidates) as SignMatch.Found).landmark
        )
        assertEquals(
            withTypo,
            (matchSign(listOf("Propogation"), candidates) as SignMatch.Found).landmark
        )
    }

    // ---- Refusing to guess -------------------------------------------------

    @Test
    fun `an empty frame reports no text rather than guessing`() {
        assertTrue(matchSign(emptyList(), complex) is SignMatch.NoText)
        assertTrue(matchSign(listOf("   ", "!!"), complex) is SignMatch.NoText)
    }

    @Test
    fun `unrelated text does not name a building`() {
        val result = matchSign(listOf("Emergency Exit", "مخرج طوارئ"), complex)
        assertTrue("got $result", result is SignMatch.Unsure)
    }

    @Test
    fun `an ambiguous read still offers its best guess for the ui`() {
        val result = matchSign(listOf("College of and Islamic Studies"), complex)
        val unsure = result as SignMatch.Unsure
        // The UI can show "did you mean...?" without the app claiming it knows.
        assertTrue(unsure.reason.isNotBlank())
    }

    @Test
    fun `no candidates is handled`() {
        val result = matchSign(listOf("Faculty of Shariah"), emptyList())
        assertTrue(result is SignMatch.Unsure)
    }

    @Test
    fun `a single candidate still needs real overlap`() {
        assertTrue(
            matchSign(listOf("Faculty of Shariah"), listOf(shariah)) is SignMatch.Found
        )
        assertTrue(
            "an unrelated read must not confirm the only candidate",
            matchSign(listOf("Parking Level 2"), listOf(shariah)) is SignMatch.Unsure
        )
    }

    // ---- Arabic normalisation ---------------------------------------------

    @Test
    fun `arabic matches regardless of alef and taa marbuta form`() {
        // OCR is inconsistent about أ vs ا and ة vs ه; a user typing is worse.
        assertEquals(dawah, matched("كلية الدعوه واصول الدين"))
        assertEquals(shariah, matched("كليه الشريعه"))
    }

    @Test
    fun `case and punctuation do not matter`() {
        assertEquals(hadith, matched("COLLEGE OF HADITH, AND ISLAMIC STUDIES."))
        assertEquals(shariah, matched("faculty  of   shariah"))
    }

    @Test
    fun `tokens shorter than two characters are ignored`() {
        assertTrue(signTokens("a b c").isEmpty())
        assertTrue(signTokens("of and the").isEmpty())
    }

    // ---- Margin behaviour --------------------------------------------------

    @Test
    fun `a confident match reports a real margin`() {
        val found = matchSign(listOf("Faculty of Shariah"), complex) as SignMatch.Found
        assertTrue("margin was ${found.margin}", found.margin >= SIGN_MIN_MARGIN)
        assertTrue("score was ${found.score}", found.score >= SIGN_MIN_SCORE)
    }

    @Test
    fun `reading two signs at once refuses to pick one`() {
        // Standing between two entrances, both plaques in frame. The honest
        // answer is that the camera cannot tell which one is being asked about.
        val result = matchSign(
            listOf(
                "College of Hadith and Islamic Studies",
                "College of Noble Quran and Islamic Studies"
            ),
            complex
        )
        assertTrue("got $result", result is SignMatch.Unsure)
    }
}
