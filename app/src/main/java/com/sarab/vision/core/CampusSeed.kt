package com.sarab.vision.core

/**
 * The landmarks to survey, as templates.
 *
 * These carry the names, categories and descriptions but NOT coordinates:
 * positions come from walking the campus in survey mode. Pre-filling the
 * names means the survey is a matter of walking to each entrance and tapping
 * save, rather than typing Arabic on a phone in the sun.
 *
 * Once surveyed, the exported landmarks.json can be dropped into
 * app/src/main/assets/ to ship the finished map with the app.
 */
object CampusSeed {

    /** A landmark to be captured, with its details already filled in. */
    data class Template(
        val name: String,
        val category: LandmarkCategory,
        val detail: String = "",
        val hours: String = "",
        val amenities: List<String> = emptyList()
    )

    val TEMPLATES: List<Template> = listOf(
        Template(
            name = "سكن طلاب - وحدة 13",
            category = LandmarkCategory.HOUSING,
            detail = "الوحدة السكنية رقم 13 لسكن الطلاب.",
            hours = "الاستقبال 24 ساعة",
            amenities = listOf("مدخل مهيأ", "واي فاي", "مغسلة")
        ),
        Template(
            name = "الملعب",
            category = LandmarkCategory.SPORTS,
            detail = "الملعب الرياضي الرئيسي بالحرم الجامعي.",
            hours = "يفتح من 6 صباحاً حتى 10 مساءً",
            amenities = listOf("مدخل مهيأ", "مواقف", "دورات مياه")
        ),
        Template(
            name = "كلية الحاسب الآلي",
            category = LandmarkCategory.FACULTY,
            detail = "قاعات محاضرات ومعامل الحاسب الآلي.",
            hours = "الأحد - الخميس 7:30 ص - 8:00 م",
            amenities = listOf("مدخل مهيأ", "واي فاي", "مصلى", "كافتيريا")
        ),
        Template(
            name = "كلية الشريعة",
            category = LandmarkCategory.FACULTY,
            detail = "قاعات محاضرات كلية الشريعة والدراسات الإسلامية.",
            hours = "الأحد - الخميس 7:30 ص - 8:00 م",
            amenities = listOf("مدخل مهيأ", "واي فاي", "مصلى")
        )
    )

    /** Templates that have not been captured yet, matched by name. */
    fun remaining(captured: List<Landmark>): List<Template> {
        val done = captured.map { normaliseForSearch(it.name) }.toSet()
        return TEMPLATES.filterNot { normaliseForSearch(it.name) in done }
    }
}
