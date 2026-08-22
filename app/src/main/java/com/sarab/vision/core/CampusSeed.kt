package com.sarab.vision.core

/**
 * The landmarks to survey, as templates.
 *
 * These carry the names, categories, descriptions and -- where known -- the
 * exact text on the building's entrance plaque. Positions come from walking
 * the campus in survey mode or from picking the point on the map, so they are
 * deliberately not here.
 *
 * Pre-filling the names means the survey is a matter of walking to each
 * entrance and tapping save, rather than typing Arabic on a phone in the sun.
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
        val amenities: List<String> = emptyList(),
        /**
         * What is written on the entrance plaque, verbatim.
         *
         * Transcribed from photographs of the actual signs, including the
         * misspelling on the Dawah building ("Propogation"), because OCR reads
         * the wall rather than the dictionary. The correct spelling is listed
         * alongside it so either reading matches.
         */
        val signTexts: List<String> = emptyList()
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
            detail = "مبنى مرتفع من ستة أدوار، يقع منفرداً عبر الشارع عن " +
                "مجمع كليات القرآن والحديث والدعوة.",
            hours = "الأحد - الخميس 7:30 ص - 8:00 م",
            amenities = listOf("مدخل مهيأ", "واي فاي", "مصلى"),
            signTexts = listOf("كلية الشريعة", "Faculty of Shariah")
        ),
        Template(
            name = "كلية القرآن الكريم والدراسات الإسلامية",
            category = LandmarkCategory.FACULTY,
            detail = "الكلية الشرقية في المجمع، تطل على الساحة المبلطة.",
            hours = "الأحد - الخميس 7:30 ص - 8:00 م",
            amenities = listOf("مدخل مهيأ", "واي فاي", "مصلى"),
            signTexts = listOf(
                "كلية القرآن الكريم والدراسات الإسلامية",
                "College of Noble Quran and Islamic Studies"
            )
        ),
        Template(
            name = "كلية الحديث الشريف والدراسات الإسلامية",
            category = LandmarkCategory.FACULTY,
            detail = "الكلية الوسطى في المجمع، بين كلية الدعوة وكلية القرآن.",
            hours = "الأحد - الخميس 7:30 ص - 8:00 م",
            amenities = listOf("مدخل مهيأ", "واي فاي", "مصلى"),
            signTexts = listOf(
                "كلية الحديث الشريف والدراسات الإسلامية",
                "College of Hadith and Islamic Studies"
            )
        ),
        Template(
            name = "كلية الدعوة وأصول الدين",
            category = LandmarkCategory.FACULTY,
            detail = "الكلية الغربية في المجمع، الأقرب إلى كلية الشريعة.",
            hours = "الأحد - الخميس 7:30 ص - 8:00 م",
            amenities = listOf("مدخل مهيأ", "واي فاي", "مصلى"),
            signTexts = listOf(
                "كلية الدعوة وأصول الدين",
                // The plaque itself reads "Propogation". Both spellings are
                // listed so a correct OCR read and a faithful one both match.
                "College of Islamic Propogation and Principles of Religion",
                "College of Islamic Propagation and Principles of Religion"
            )
        )
    )

    /** Templates that have not been captured yet, matched by name. */
    fun remaining(captured: List<Landmark>): List<Template> {
        val done = captured.map { normaliseForSearch(it.name) }.toSet()
        return TEMPLATES.filterNot { normaliseForSearch(it.name) in done }
    }
}
