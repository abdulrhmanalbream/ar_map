import XCTest
@testable import MutawwifCore

/// Search behaviour for the real campus names. The numbered housing blocks
/// ("وحدة 13") are the risky case: a naive substring match would make a search
/// for "1" or "3" return unit 13.
final class LandmarkSearchTests: XCTestCase {

    private func lm(_ name: String, _ category: LandmarkCategory = .other) -> Landmark {
        Landmark(id: name, name: name, category: category, position: LatLng(24.5, 39.5))
    }

    private lazy var unit13 = lm("سكن طلاب - وحدة 13", .housing)
    private lazy var unit1 = lm("سكن طلاب - وحدة 1", .housing)
    private lazy var unit3 = lm("سكن طلاب - وحدة 3", .housing)
    private lazy var stadium = lm("الملعب", .sports)
    private lazy var computing = lm("كلية الحاسب الآلي", .faculty)
    private lazy var sharia = lm("كلية الشريعة", .faculty)

    func testArabicIndicDigitsFoldToAscii() {
        XCTAssertEqual("13", normaliseForSearch("١٣"))
        XCTAssertEqual("وحده 13", normaliseForSearch("وحدة ١٣"))
    }

    func testAlefAndTaaMarbutaVariantsFold() {
        XCTAssertEqual(normaliseForSearch("احمد"), normaliseForSearch("أحمد"))
        XCTAssertEqual(normaliseForSearch("كليه"), normaliseForSearch("كلية"))
        XCTAssertEqual(normaliseForSearch("الالي"), normaliseForSearch("الآلي"))
    }

    func testSearching13FindsUnit13Only() {
        XCTAssertTrue(matchesQuery(unit13, "13"))
        XCTAssertFalse(matchesQuery(unit1, "13"))
        XCTAssertFalse(matchesQuery(unit3, "13"))
    }

    func testSearching1DoesNotMatchUnit13() {
        XCTAssertTrue(matchesQuery(unit1, "1"))
        XCTAssertFalse(matchesQuery(unit13, "1"))
    }

    func testArabicIndicQueryMatchesAsciiDataAndViceVersa() {
        XCTAssertTrue(matchesQuery(unit13, "١٣"))
        XCTAssertTrue(matchesQuery(lm("سكن طلاب - وحدة ١٣", .housing), "13"))
    }

    func testPartialArabicNameMatches() {
        XCTAssertTrue(matchesQuery(computing, "حاسب"))
        XCTAssertTrue(matchesQuery(sharia, "شريعة"))
        XCTAssertTrue(matchesQuery(stadium, "ملعب"))
    }

    func testTheTwoFacultiesDoNotMatchEachOther() {
        XCTAssertTrue(matchesQuery(computing, "الحاسب"))
        XCTAssertFalse(matchesQuery(sharia, "الحاسب"))
        XCTAssertTrue(matchesQuery(sharia, "الشريعة"))
        XCTAssertFalse(matchesQuery(computing, "الشريعة"))
    }

    func testCategoryNameIsSearchableInBothLanguages() {
        XCTAssertTrue(matchesQuery(unit13, "سكن"))
        XCTAssertTrue(matchesQuery(unit13, "housing"))
        XCTAssertTrue(matchesQuery(computing, "كلية"))
        XCTAssertTrue(matchesQuery(stadium, "sports"))
    }

    func testEmptyQueryMatchesEverything() {
        XCTAssertTrue(matchesQuery(unit13, ""))
        XCTAssertTrue(matchesQuery(stadium, "   "))
    }

    func testSearchIgnoresDiacritics() {
        let withTashkeel = lm("كُلِّيَة الحاسِب")
        XCTAssertTrue(matchesQuery(withTashkeel, "كلية"))
        XCTAssertTrue(matchesQuery(withTashkeel, "الحاسب"))
    }

    func testSeedJsonRoundTrips() throws {
        let json = """
        [{"id":"lm-x","name":"كلية","category":"FACULTY","lat":24.48,"lon":39.55,
          "manual":true,"accuracy":15.0,"signs":["A",""],"photos":[],
          "entrances":[{"id":"e1","name":"المدخل","lat":24.481,"lon":39.551}]},
         {"id":"bad","name":"no coords"}]
        """
        let decoded = try Landmark.decodeStore(Data(json.utf8))
        XCTAssertEqual(1, decoded.count)
        XCTAssertEqual(.faculty, decoded[0].category)
        XCTAssertEqual(["A"], decoded[0].signTexts)
        XCTAssertEqual(1, decoded[0].entrances.count)
        XCTAssertTrue(decoded[0].placedManually)
        let again = try Landmark.decodeStore(try Landmark.encodeStore(decoded))
        XCTAssertEqual(decoded, again)
    }
}
