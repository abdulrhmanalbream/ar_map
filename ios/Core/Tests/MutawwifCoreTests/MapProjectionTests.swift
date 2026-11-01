import XCTest
@testable import MutawwifCore

/// Map projection. The y-axis inversion is the classic silent bug: canvas y
/// grows downwards while north grows upwards.
final class MapProjectionTests: XCTestCase {

    private let origin = LatLng(24.500000, 39.500000)

    private func at(_ n: Double, _ e: Double) -> LatLng { landmarkAt("x", n, e).position }

    func testBoundsCoverEveryPoint() {
        let points = [at(0, 0), at(100, 50), at(-30, -20)]
        let b = GeoBounds.of(points)!
        XCTAssertLessThanOrEqual(b.minLat, points.map { $0.latitude }.min()!)
        XCTAssertGreaterThanOrEqual(b.maxLat, points.map { $0.latitude }.max()!)
        XCTAssertLessThanOrEqual(b.minLon, points.map { $0.longitude }.min()!)
        XCTAssertGreaterThanOrEqual(b.maxLon, points.map { $0.longitude }.max()!)
    }

    func testBoundsIgnoreInvalidPoints() {
        let b = GeoBounds.of([LatLng(0.0, 0.0), origin])
        XCTAssertNotNil(b)
        XCTAssertEqual(origin.latitude, b!.centre.latitude, accuracy: 1e-9)
    }

    func testBoundsOfNothingIsNil() {
        XCTAssertNil(GeoBounds.of([]))
        XCTAssertNil(GeoBounds.of([LatLng(0.0, 0.0)]))
    }

    func testNorthAppearsAboveSouthOnTheCanvas() {
        let south = at(0, 0)
        let north = at(200, 0)
        let t = MapTransform(bounds: GeoBounds.of([south, north])!, canvasWidth: 800, canvasHeight: 800)
        XCTAssertLessThan(t.toCanvas(north).y, t.toCanvas(south).y)
    }

    func testEastAppearsRightOfWestOnTheCanvas() {
        let west = at(0, 0)
        let east = at(0, 200)
        let t = MapTransform(bounds: GeoBounds.of([west, east])!, canvasWidth: 800, canvasHeight: 800)
        XCTAssertGreaterThan(t.toCanvas(east).x, t.toCanvas(west).x)
    }

    func testAllPointsLandInsideTheCanvas() {
        let points = [at(0, 0), at(250, 120), at(-80, -200), at(140, -60)]
        let t = MapTransform(bounds: GeoBounds.of(points)!, canvasWidth: 900, canvasHeight: 1200)
        for p in points {
            let c = t.toCanvas(p)
            XCTAssertTrue(c.x >= 0 && c.x <= 900, "x=\(c.x) off canvas")
            XCTAssertTrue(c.y >= 0 && c.y <= 1200, "y=\(c.y) off canvas")
        }
    }

    func testAspectRatioIsPreserved() {
        let sw = at(0, 0)
        let se = at(0, 200)
        let nw = at(200, 0)
        let t = MapTransform(bounds: GeoBounds.of([sw, se, nw])!, canvasWidth: 600, canvasHeight: 1000)
        let horizontal = t.toCanvas(se).x - t.toCanvas(sw).x
        let vertical = t.toCanvas(sw).y - t.toCanvas(nw).y
        XCTAssertEqual(Double(horizontal), Double(vertical), accuracy: 2.0)
    }

    func testASingleLandmarkStillProjectsWithoutDividingByZero() {
        let t = MapTransform(bounds: GeoBounds.of([origin])!, canvasWidth: 800, canvasHeight: 800)
        let c = t.toCanvas(origin)
        XCTAssertTrue(c.x.isFinite && c.y.isFinite)
        XCTAssertTrue((0...800).contains(c.x) && (0...800).contains(c.y))
    }

    func testScaleBarPicksARoundNumber() {
        let t = MapTransform(bounds: GeoBounds.of([at(0, 0), at(300, 300)])!, canvasWidth: 800, canvasHeight: 800)
        XCTAssertTrue([10, 20, 50, 100, 200, 500, 1000].contains(t.scaleBarMeters()))
        XCTAssertGreaterThan(t.metersPerPixel(), 0)
    }

    func testDistancesMeasuredOnTheMapMatchReality() {
        let a = at(0, 0)
        let b = at(0, 250)
        let t = MapTransform(bounds: GeoBounds.of([a, b])!, canvasWidth: 1000, canvasHeight: 1000)
        let pa = t.toCanvas(a)
        let pb = t.toCanvas(b)
        let px = hypot(Double(pb.x - pa.x), Double(pb.y - pa.y))
        XCTAssertEqual(250.0, px * t.metersPerPixel(), accuracy: 5.0)
    }
}
