import XCTest
@testable import MutawwifCore

/// Google Maps link parsing. The lat/lng ordering is the trap: swapping them
/// puts a Saudi campus in the Indian Ocean.
final class GoogleMapsLinkTests: XCTestCase {

    private func found(_ raw: String, file: StaticString = #filePath, line: UInt = #line) -> LatLng {
        let result = parseGoogleMapsLink(raw)
        guard case let .found(position) = result else {
            XCTFail("expected coordinates, got \(result)", file: file, line: line)
            return LatLng(0, 0)
        }
        return position
    }

    private func isNotFound(_ r: MapsLinkResult) -> Bool {
        if case .notFound = r { return true }
        return false
    }

    func testParsesTheExactDroppedPin() {
        let url = "https://www.google.com/maps/place/Taibah+University/" +
            "@24.4672,39.6111,17z/data=!3m1!4b1!4m5!3m4!1s0x0:0x0!8m2!3d24.4839!4d39.5892"
        let p = found(url)
        XCTAssertEqual(24.4839, p.latitude, accuracy: 1e-6)
        XCTAssertEqual(39.5892, p.longitude, accuracy: 1e-6)
    }

    func testPrefersThePinOverTheCameraPosition() {
        XCTAssertEqual(24.4839, found("https://maps.google.com/@10.0,20.0,17z/data=!3d24.4839!4d39.5892").latitude, accuracy: 1e-6)
    }

    func testParsesTheCameraPositionWhenThereIsNoPin() {
        let p = found("https://www.google.com/maps/@24.4672,39.6111,17z")
        XCTAssertEqual(24.4672, p.latitude, accuracy: 1e-6)
        XCTAssertEqual(39.6111, p.longitude, accuracy: 1e-6)
    }

    func testParsesQueryParameters() {
        XCTAssertEqual(24.4672, found("https://maps.google.com/?q=24.4672,39.6111").latitude, accuracy: 1e-6)
        XCTAssertEqual(39.6111, found("https://maps.google.com/?ll=24.4672,39.6111").longitude, accuracy: 1e-6)
        XCTAssertEqual(24.4672, found("https://www.google.com/maps/dir/?api=1&destination=24.4672,39.6111").latitude, accuracy: 1e-6)
    }

    func testParsesTheLocPrefix() {
        XCTAssertEqual(24.4672, found("https://maps.google.com/?q=loc:24.4672,39.6111").latitude, accuracy: 1e-6)
    }

    func testParsesABarePastedPair() {
        let p = found("24.4672, 39.6111")
        XCTAssertEqual(24.4672, p.latitude, accuracy: 1e-6)
        XCTAssertEqual(39.6111, p.longitude, accuracy: 1e-6)
    }

    func testKeepsLatitudeAndLongitudeTheRightWayRound() {
        let p = found("https://www.google.com/maps/@24.4672,39.6111,17z")
        XCTAssertLessThan(p.latitude, 30)
        XCTAssertGreaterThan(p.longitude, 30)
    }

    func testShortLinksReportThatTheyNeedTheNetwork() {
        XCTAssertTrue(isShortLink("https://maps.app.goo.gl/abc123"))
        XCTAssertEqual(.needsNetwork, parseGoogleMapsLink("https://maps.app.goo.gl/abc123"))
    }

    func testAShortLinkCarryingCoordinatesIsStillParsed() {
        XCTAssertEqual(24.4672, found("https://maps.app.goo.gl/x?q=24.4672,39.6111").latitude, accuracy: 1e-6)
    }

    func testRejectsOutOfRangeCoordinates() {
        XCTAssertTrue(isNotFound(parseGoogleMapsLink("https://maps.google.com/?q=999.0,39.6")))
    }

    func testRejectsRubbishInput() {
        XCTAssertTrue(isNotFound(parseGoogleMapsLink("")))
        XCTAssertTrue(isNotFound(parseGoogleMapsLink("hello world")))
        XCTAssertTrue(isNotFound(parseGoogleMapsLink("https://example.com")))
    }

    func testHandlesNegativeCoordinates() {
        let p = found("https://www.google.com/maps/@-33.8688,151.2093,17z")
        XCTAssertEqual(-33.8688, p.latitude, accuracy: 1e-6)
        XCTAssertEqual(151.2093, p.longitude, accuracy: 1e-6)
    }

    func testBuildsAShareableLinkThatRoundTrips() {
        let original = LatLng(24.4672, 39.6111)
        let parsed = found(googleMapsLinkFor(original))
        XCTAssertEqual(original.latitude, parsed.latitude, accuracy: 1e-6)
        XCTAssertEqual(original.longitude, parsed.longitude, accuracy: 1e-6)
    }
}
