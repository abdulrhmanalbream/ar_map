import XCTest
@testable import SmartMutawwif

final class VisionLabelLayoutTests: XCTestCase {
    private func overlaps(_ a: VisionScreenBox, _ b: VisionScreenBox) -> Bool {
        a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
    }

    private func layout(_ boxes: [VisionScreenBox], _ width: Double, _ height: Double) -> [VisionScreenBox] {
        layoutVisionLabels(boxes, viewportWidth: width, viewportHeight: height, labelWidth: 220, labelHeight: 80,
                           topInset: 76, bottomInset: 130, gap: 8)
    }

    func testThreeDetectionsAtSamePositionReceiveSeparatePortraitLabels() {
        let labels = layout(Array(repeating: VisionScreenBox(left: 110, top: 280, right: 190, bottom: 330), count: 3), 360, 800)
        XCTAssertEqual(labels.count, 3)
        for i in labels.indices { for j in labels.indices where j > i { XCTAssertFalse(overlaps(labels[i], labels[j])) } }
        XCTAssertTrue(labels.allSatisfy { $0.left >= 0 && $0.right <= 360 && $0.top >= 76 && $0.bottom <= 670 })
    }

    func testNearbyChoiceReflowsWhenItWouldBlockThreeLabelsThatFit() {
        let labels = layout(Array(repeating: VisionScreenBox(left: 225, top: 90, right: 245, bottom: 110), count: 3), 680, 286)
        XCTAssertEqual(labels.count, 3)
        for i in labels.indices { for j in labels.indices where j > i { XCTAssertFalse(overlaps(labels[i], labels[j])) } }
        XCTAssertTrue(labels.allSatisfy { $0.top == 76 && $0.bottom == 156 })
    }

    func testSeparatedDetectionsKeepInputOrderAndNearbyPlacement() {
        let labels = layout([VisionScreenBox(left: 600, top: 200, right: 700, bottom: 260),
                             VisionScreenBox(left: 20, top: 200, right: 120, bottom: 260)], 900, 700)
        XCTAssertTrue(labels[0].left > labels[1].right)
        XCTAssertTrue(labels[0].bottom <= 200 && labels[1].bottom <= 200)
    }

    func testInsufficientSpaceStillReturnsClampedRectangles() {
        let labels = layout(Array(repeating: VisionScreenBox(left: -100, top: 600, right: 900, bottom: 800), count: 3), 160, 180)
        XCTAssertEqual(labels.count, 3)
        XCTAssertTrue(labels.allSatisfy { $0.left >= 0 && $0.right <= 160 && $0.top >= 0 && $0.bottom <= 180 })
    }
}
