import XCTest
@testable import MutawwifCore

/// Reading building signs, tested against the real campus. Three of these
/// colleges sit in one complex, look identical, and have English names sharing
/// most of their words.
final class SignMatchTests: XCTestCase {

    private func college(_ id: String, _ name: String, _ signs: String...) -> Landmark {
        Landmark(id: id, name: name, category: .faculty, position: LatLng(24.48, 39.56), signTexts: signs)
    }

    private lazy var shariah = college("shariah", "كلية الشريعة", "كلية الشريعة", "Faculty of Shariah")
    private lazy var quran = college("quran", "كلية القرآن الكريم والدراسات الإسلامية",
                                     "كلية القرآن الكريم والدراسات الإسلامية", "College of Noble Quran and Islamic Studies")
    private lazy var hadith = college("hadith", "كلية الحديث الشريف والدراسات الإسلامية",
                                      "كلية الحديث الشريف والدراسات الإسلامية", "College of Hadith and Islamic Studies")
    private lazy var dawah = college("dawah", "كلية الدعوة وأصول الدين",
                                     "كلية الدعوة وأصول الدين", "College of Islamic Propogation and Principles of Religion")
    private var complex: [Landmark] { [shariah, quran, hadith, dawah] }

    private func foundLandmark(_ r: SignMatch) -> Landmark? {
        if case let .found(lm, _, _) = r { return lm }
        return nil
    }

    private func isUnsure(_ r: SignMatch) -> Bool { if case .unsure = r { return true }; return false }

    private func matched(_ lines: String...) -> Landmark? { foundLandmark(matchSign(lines, complex)) }

    func testEachEnglishSignIdentifiesItsOwnBuilding() {
        XCTAssertEqual(shariah, matched("Faculty of Shariah"))
        XCTAssertEqual(quran, matched("College of Noble Quran and Islamic Studies"))
        XCTAssertEqual(hadith, matched("College of Hadith and Islamic Studies"))
        XCTAssertEqual(dawah, matched("College of Islamic Propogation and Principles of Religion"))
    }

    func testEachArabicSignIdentifiesItsOwnBuilding() {
        XCTAssertEqual(shariah, matched("كلية الشريعة"))
        XCTAssertEqual(quran, matched("كلية القرآن الكريم والدراسات الإسلامية"))
        XCTAssertEqual(hadith, matched("كلية الحديث الشريف والدراسات الإسلامية"))
        XCTAssertEqual(dawah, matched("كلية الدعوة وأصول الدين"))
    }

    func testARealPlaqueReadWholeBothLinesAtOnce() {
        XCTAssertEqual(hadith, matched("كلية الحديث الشريف والدراسات الإسلامية", "College of Hadith and Islamic Studies"))
    }

    func testTheSharedWordsAloneNeverNameABuilding() {
        XCTAssertTrue(isUnsure(matchSign(["College of and Islamic Studies"], complex)))
    }

    func testOneDistinctiveWordIsEnoughToSeparateTheTwins() {
        XCTAssertEqual(quran, matched("Quran"))
        XCTAssertEqual(hadith, matched("Hadith"))
        XCTAssertEqual(quran, matched("القرآن"))
        XCTAssertEqual(hadith, matched("الحديث"))
    }

    func testAHalfReadSignStillResolvesWhenTheRareWordSurvives() {
        XCTAssertEqual(hadith, matched("ollege of Hadith and Isl"))
        XCTAssertEqual(dawah, matched("Propogation and Principles"))
    }

    func testTheMisspellingOnTheWallAndTheCorrectSpellingBothMatch() {
        let withTypo = college("dawah", "كلية الدعوة وأصول الدين",
                               "College of Islamic Propogation and Principles of Religion",
                               "College of Islamic Propagation and Principles of Religion")
        let candidates = [shariah, quran, hadith, withTypo]
        XCTAssertEqual(withTypo, foundLandmark(matchSign(["Propagation"], candidates)))
        XCTAssertEqual(withTypo, foundLandmark(matchSign(["Propogation"], candidates)))
    }

    func testAnEmptyFrameReportsNoTextRatherThanGuessing() {
        XCTAssertEqual(.noText, matchSign([], complex))
        XCTAssertEqual(.noText, matchSign(["   ", "!!"], complex))
    }

    func testUnrelatedTextDoesNotNameABuilding() {
        XCTAssertTrue(isUnsure(matchSign(["Emergency Exit", "مخرج طوارئ"], complex)))
    }

    func testAnAmbiguousReadStillOffersAReasonForTheUi() {
        guard case let .unsure(_, reason) = matchSign(["College of and Islamic Studies"], complex) else {
            return XCTFail("expected unsure")
        }
        XCTAssertFalse(reason.isEmpty)
    }

    func testNoCandidatesIsHandled() {
        XCTAssertTrue(isUnsure(matchSign(["Faculty of Shariah"], [])))
    }

    func testASingleCandidateStillNeedsRealOverlap() {
        XCTAssertNotNil(foundLandmark(matchSign(["Faculty of Shariah"], [shariah])))
        XCTAssertTrue(isUnsure(matchSign(["Parking Level 2"], [shariah])))
    }

    func testArabicMatchesRegardlessOfAlefAndTaaMarbutaForm() {
        XCTAssertEqual(dawah, matched("كلية الدعوه واصول الدين"))
        XCTAssertEqual(shariah, matched("كليه الشريعه"))
    }

    func testCaseAndPunctuationDoNotMatter() {
        XCTAssertEqual(hadith, matched("COLLEGE OF HADITH, AND ISLAMIC STUDIES."))
        XCTAssertEqual(shariah, matched("faculty  of   shariah"))
    }

    func testTokensShorterThanTwoCharactersAreIgnored() {
        XCTAssertTrue(signTokens("a b c").isEmpty)
        XCTAssertTrue(signTokens("of and the").isEmpty)
    }

    func testAConfidentMatchReportsARealMargin() {
        guard case let .found(_, score, margin) = matchSign(["Faculty of Shariah"], complex) else {
            return XCTFail("expected found")
        }
        XCTAssertGreaterThanOrEqual(margin, SIGN_MIN_MARGIN)
        XCTAssertGreaterThanOrEqual(score, SIGN_MIN_SCORE)
    }

    func testReadingTwoSignsAtOnceRefusesToPickOne() {
        XCTAssertTrue(isUnsure(matchSign(["College of Hadith and Islamic Studies", "College of Noble Quran and Islamic Studies"], complex)))
    }
}
