import XCTest
@testable import MutawwifCore

/// Picking and path maths: a bad ray silently means "tapping the cube does
/// nothing" with no error anywhere.
final class GeometryTests: XCTestCase {

    func testNormalizingAZeroVectorDoesNotProduceNaN() {
        let n = Vec3.ZERO.normalized()
        XCTAssertFalse(n.x.isNaN)
        XCTAssertEqual(0, n.length(), accuracy: 1e-5)
    }

    func testRayPointingAtBoxHitsIt() {
        let ray = Ray(origin: Vec3(0, 0, 0), direction: Vec3(0, 0, -1))
        let hit = intersectAabb(ray: ray, center: Vec3(0, 0, -5), halfExtent: 0.5)
        XCTAssertNotNil(hit)
        XCTAssertEqual(4.5, hit!, accuracy: 1e-4)
    }

    func testRayPointingAwayFromBoxMissesIt() {
        let ray = Ray(origin: Vec3(0, 0, 0), direction: Vec3(0, 0, 1))
        XCTAssertNil(intersectAabb(ray: ray, center: Vec3(0, 0, -5), halfExtent: 0.5))
    }

    func testRayOffsetToTheSideMissesASmallBox() {
        let ray = Ray(origin: Vec3(3, 0, 0), direction: Vec3(0, 0, -1))
        XCTAssertNil(intersectAabb(ray: ray, center: Vec3(0, 0, -5), halfExtent: 0.5))
    }

    func testRayParallelToASlabButOutsideItMisses() {
        let ray = Ray(origin: Vec3(-10, 5, -5), direction: Vec3(1, 0, 0))
        XCTAssertNil(intersectAabb(ray: ray, center: Vec3(0, 0, -5), halfExtent: 0.5))
    }

    func testCameraInsideTheBoxStillRegistersAHit() {
        let ray = Ray(origin: Vec3(0, 0, 0), direction: Vec3(0, 0, -1))
        let hit = intersectAabb(ray: ray, center: Vec3(0, 0, 0), halfExtent: 1)
        XCTAssertNotNil(hit)
        XCTAssertEqual(0, hit!, accuracy: 1e-5)
    }

    func testPathRunsTheRequestedLengthInTheRequestedDirection() {
        let start = Vec3(1, 0, 2)
        let points = buildPathPoints(start: start, forward: Vec3(0, 0, -1), length: 4, segments: 8)
        XCTAssertEqual(9, points.count)
        XCTAssertEqual(start, points.first)
        let end = points.last!
        XCTAssertEqual(1, end.x, accuracy: 1e-4)
        XCTAssertEqual(-2, end.z, accuracy: 1e-4)
        XCTAssertEqual(0, end.y - start.y, accuracy: 1e-4)
        XCTAssertEqual(4, (end - start).length(), accuracy: 1e-4)
    }

    func testPathDirectionIsNormalizedSoLengthIsRespected() {
        let points = buildPathPoints(start: Vec3.ZERO, forward: Vec3(0, 0, -7), length: 3, segments: 4)
        XCTAssertEqual(3, (points.last! - points.first!).length(), accuracy: 1e-4)
    }

    func testDegeneratePathInputsReturnEmpty() {
        XCTAssertTrue(buildPathPoints(start: Vec3.ZERO, forward: Vec3.ZERO, length: 4).isEmpty)
        XCTAssertTrue(buildPathPoints(start: Vec3.ZERO, forward: Vec3(0, 0, -1), length: 4, segments: 0).isEmpty)
    }
}
