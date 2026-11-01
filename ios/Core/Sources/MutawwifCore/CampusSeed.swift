import Foundation

/// The landmarks to survey, as templates.
///
/// These carry the names, categories, descriptions and -- where known -- the
/// exact text on the building's entrance plaque. Positions come from walking
/// the campus in survey mode or from picking the point on the map, so they are
/// deliberately not here.
///
/// Pre-filling the names means the survey is a matter of walking to each
/// entrance and tapping save, rather than typing Arabic on a phone in the sun.
public enum CampusSeed {

    /// A landmark to be captured, with its details already filled in.
    public struct Template: Equatable {
        public var name: String
        public var category: LandmarkCategory
        public var detail: String
        public var hours: String
        public var amenities: [String]
        /// What is written on the entrance plaque, verbatim.
        ///
        /// Transcribed from photographs of the actual signs, including the
        /// misspelling on the Dawah building ("Propogation"), because OCR reads
        /// the wall rather than the dictionary. The correct spelling is listed
        /// alongside it so either reading matches.
        public var signTexts: [String]

        public init(
            name: String,
            category: LandmarkCategory,
            detail: String = "",
            hours: String = "",
            amenities: [String] = [],
            signTexts: [String] = []
        ) {
            self.name = name
            self.category = category
            self.detail = detail
            self.hours = hours
            self.amenities = amenities
            self.signTexts = signTexts
        }
    }

    public static let TEMPLATES: [Template] = [
        Template(
            name: "سكن طلاب - وحدة 13",
            category: .housing,
            detail: "الوحدة السكنية رقم 13 لسكن الطلاب.",
            hours: "الاستقبال 24 ساعة",
            amenities: ["مدخل مهيأ", "واي فاي", "مغسلة"]
        ),
        Template(
            name: "الملعب",
            category: .sports,
            detail: "الملعب الرياضي الرئيسي بالحرم الجامعي.",
            hours: "يفتح من 6 صباحاً حتى 10 مساءً",
            amenities: ["مدخل مهيأ", "مواقف", "دورات مياه"]
        ),
        Template(
            name: "كلية الحاسب الآلي",
            category: .faculty,
            detail: "قاعات محاضرات ومعامل الحاسب الآلي.",
            hours: "الأحد - الخميس 7:30 ص - 8:00 م",
            amenities: ["مدخل مهيأ", "واي فاي", "مصلى", "كافتيريا"]
        ),
        Template(
            name: "كلية الشريعة",
            category: .faculty,
            detail: "مبنى مرتفع من ستة أدوار، يقع منفرداً عبر الشارع عن " +
                "مجمع كليات القرآن والحديث والدعوة.",
            hours: "الأحد - الخميس 7:30 ص - 8:00 م",
            amenities: ["مدخل مهيأ", "واي فاي", "مصلى"],
            signTexts: ["كلية الشريعة", "Faculty of Shariah"]
        ),
        Template(
            name: "كلية القرآن الكريم والدراسات الإسلامية",
            category: .faculty,
            detail: "الكلية الشرقية في المجمع، تطل على الساحة المبلطة.",
            hours: "الأحد - الخميس 7:30 ص - 8:00 م",
            amenities: ["مدخل مهيأ", "واي فاي", "مصلى"],
            signTexts: [
                "كلية القرآن الكريم والدراسات الإسلامية",
                "College of Noble Quran and Islamic Studies",
            ]
        ),
        Template(
            name: "كلية الحديث الشريف والدراسات الإسلامية",
            category: .faculty,
            detail: "الكلية الوسطى في المجمع، بين كلية الدعوة وكلية القرآن.",
            hours: "الأحد - الخميس 7:30 ص - 8:00 م",
            amenities: ["مدخل مهيأ", "واي فاي", "مصلى"],
            signTexts: [
                "كلية الحديث الشريف والدراسات الإسلامية",
                "College of Hadith and Islamic Studies",
            ]
        ),
        Template(
            name: "كلية الدعوة وأصول الدين",
            category: .faculty,
            detail: "الكلية الغربية في المجمع، الأقرب إلى كلية الشريعة.",
            hours: "الأحد - الخميس 7:30 ص - 8:00 م",
            amenities: ["مدخل مهيأ", "واي فاي", "مصلى"],
            signTexts: [
                "كلية الدعوة وأصول الدين",
                // The plaque itself reads "Propogation". Both spellings are
                // listed so a correct OCR read and a faithful one both match.
                "College of Islamic Propogation and Principles of Religion",
                "College of Islamic Propagation and Principles of Religion",
            ]
        ),
    ]

    /// Templates that have not been captured yet, matched by name.
    public static func remaining(_ captured: [Landmark]) -> [Template] {
        let done = Set(captured.map { normaliseForSearch($0.name) })
        return TEMPLATES.filter { !done.contains(normaliseForSearch($0.name)) }
    }
}
