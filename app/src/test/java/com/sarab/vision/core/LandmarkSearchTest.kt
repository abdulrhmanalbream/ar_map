package com.sarab.vision.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Search behaviour for the real campus names.
 *
 * The numbered housing blocks ("وحدة 13") are the risky case: a naive
 * substring match would make a search for "1" or "3" return unit 13, and a
 * search for "13" return nothing when the data uses Arabic-Indic digits.
 */
class LandmarkSearchTest {

    private fun lm(name: String, category: LandmarkCategory = LandmarkCategory.OTHER) =
        Landmark(
            id = name,
            name = name,
            category = category,
            position = LatLng(24.5, 39.5)
        )

    private val unit13 = lm("سكن طلاب - وحدة 13", LandmarkCategory.HOUSING)
    private val unit1 = lm("سكن طلاب - وحدة 1", LandmarkCategory.HOUSING)
    private val unit3 = lm("سكن طلاب - وحدة 3", LandmarkCategory.HOUSING)
    private val stadium = lm("الملعب", LandmarkCategory.SPORTS)
    private val computing = lm("كلية الحاسب الآلي", LandmarkCategory.FACULTY)
    private val sharia = lm("كلية الشريعة", LandmarkCategory.FACULTY)

    @Test
    fun `arabic-indic digits fold to ascii`() {
        assertEquals("13", normaliseForSearch("١٣"))
        assertEquals("وحده 13", normaliseForSearch("وحدة ١٣"))
    }

    @Test
    fun `alef and taa marbuta variants fold`() {
        assertEquals(normaliseForSearch("احمد"), normaliseForSearch("أحمد"))
        assertEquals(normaliseForSearch("كليه"), normaliseForSearch("كلية"))
        assertEquals(normaliseForSearch("الالي"), normaliseForSearch("الآلي"))
    }

    @Test
    fun `searching 13 finds unit 13 only`() {
        assertTrue(matchesQuery(unit13, "13"))
        assertFalse("unit 1 must not match a search for 13", matchesQuery(unit1, "13"))
        assertFalse("unit 3 must not match a search for 13", matchesQuery(unit3, "13"))
    }

    @Test
    fun `searching 1 does not match unit 13`() {
        // This is the trap: a substring match would wrongly return unit 13.
        assertTrue(matchesQuery(unit1, "1"))
        assertFalse("unit 13 must not match a search for 1", matchesQuery(unit13, "1"))
    }

    @Test
    fun `arabic-indic query matches ascii data and vice versa`() {
        assertTrue(matchesQuery(unit13, "١٣"))

        val arabicDigits = lm("سكن طلاب - وحدة ١٣", LandmarkCategory.HOUSING)
        assertTrue(matchesQuery(arabicDigits, "13"))
    }

    @Test
    fun `partial arabic name matches`() {
        assertTrue(matchesQuery(computing, "حاسب"))
        assertTrue(matchesQuery(sharia, "شريعة"))
        assertTrue(matchesQuery(stadium, "ملعب"))
    }

    @Test
    fun `the two faculties do not match each other`() {
        assertTrue(matchesQuery(computing, "الحاسب"))
        assertFalse(matchesQuery(sharia, "الحاسب"))
        assertTrue(matchesQuery(sharia, "الشريعة"))
        assertFalse(matchesQuery(computing, "الشريعة"))
    }

    @Test
    fun `category name is searchable in both languages`() {
        assertTrue(matchesQuery(unit13, "سكن"))
        assertTrue(matchesQuery(unit13, "housing"))
        assertTrue(matchesQuery(computing, "كلية"))
        assertTrue(matchesQuery(stadium, "sports"))
    }

    @Test
    fun `empty query matches everything`() {
        assertTrue(matchesQuery(unit13, ""))
        assertTrue(matchesQuery(stadium, "   "))
    }

    @Test
    fun `search ignores diacritics`() {
        val withTashkeel = lm("كُلِّيَة الحاسِب")
        assertTrue(matchesQuery(withTashkeel, "كلية"))
        assertTrue(matchesQuery(withTashkeel, "الحاسب"))
    }
}
