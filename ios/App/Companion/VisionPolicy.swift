import Foundation

enum VisionPolicy {
    static let automaticIntervalMs: Int64 = 6_000
    static let staticRefreshMs: Int64 = 30_000
    static let frameFreshMs: Int64 = 1_500
    static let sceneFreshMs: Int64 = 30_000
    static let steadyMs: Int64 = 750
    static let signatureIntervalMs: Int64 = 500
    /// While a scene's markers stay matched, re-analysis waits this long (18 s cadence).
    static let matchedRefreshMs: Int64 = 18_000
    static let languages: Set<String> = ["ar", "en", "ur", "id", "tr"]

    static func freshTimestamp(_ capturedAt: Int64, now: Int64, maximumAge: Int64) -> Bool {
        capturedAt >= 0 && now >= capturedAt && now - capturedAt <= maximumAge
    }

    static func validPose(_ pose: VisionPose?) -> Bool {
        guard let pose = pose else { return false }
        return pose.yawDegrees.isFinite && pose.pitchDegrees.isFinite &&
            pose.pitchDegrees >= -90 && pose.pitchDegrees <= 90 && pose.rollDegrees.isFinite
    }

    private static func angleDelta(_ a: Double, _ b: Double) -> Double {
        let wrapped = ((b - a).truncatingRemainder(dividingBy: 360) + 540).truncatingRemainder(dividingBy: 360) - 180
        return abs(wrapped)
    }

    static func nearPose(_ captured: VisionPose?, _ current: VisionPose?) -> Bool {
        guard validPose(captured), validPose(current), let c = captured, let n = current,
              c.sessionId == n.sessionId else { return false }
        return angleDelta(c.yawDegrees, n.yawDegrees) <= 3 &&
            abs(n.pitchDegrees - c.pitchDegrees) <= 3 &&
            angleDelta(c.rollDegrees, n.rollDegrees) <= 3
    }

    static func markersFresh(_ capturedAt: Int64, now: Int64, captured: VisionPose?, current: VisionPose?) -> Bool {
        freshTimestamp(capturedAt, now: now, maximumAge: sceneFreshMs) && nearPose(captured, current)
    }

    static func automaticDue(_ lastAttemptAt: Int64?, now: Int64) -> Bool {
        guard let last = lastAttemptAt else { return true }
        return now >= last && now - last >= automaticIntervalMs
    }

    static func staticRefreshDue(_ lastCapturedAt: Int64?, now: Int64) -> Bool {
        guard let last = lastCapturedAt else { return true }
        return now >= last && now - last >= staticRefreshMs
    }

    static func retryDelayMs(_ consecutiveFailures: Int) -> Int64 {
        let shift = Int64(min(max(consecutiveFailures, 0), 4))
        return min(60_000, automaticIntervalMs * (Int64(1) << shift))
    }
}

struct VisionFrameSignature: Equatable {
    var luma: [Int]
}

/// Mean normalized luminance difference; intentionally conservative about changed scenes.
func visionSignatureDifference(_ first: VisionFrameSignature?, _ second: VisionFrameSignature?) -> Double? {
    guard let a = first, let b = second, a.luma.count == 192, b.luma.count == 192,
          a.luma.allSatisfy({ $0 >= 0 && $0 <= 255 }), b.luma.allSatisfy({ $0 >= 0 && $0 <= 255 }) else { return nil }
    var sum = 0
    for i in 0..<192 { sum += abs(a.luma[i] - b.luma[i]) }
    return Double(sum) / Double(192 * 255)
}

func visionSignaturesMatch(_ first: VisionFrameSignature?, _ second: VisionFrameSignature?) -> Bool {
    guard let difference = visionSignatureDifference(first, second), let a = first, let b = second else { return false }
    var changed = 0
    for i in 0..<192 where abs(a.luma[i] - b.luma[i]) > 35 { changed += 1 }
    return difference <= 12.0 / 255.0 && changed <= 19
}

/// An invalidated HTTP request still owns the single slot until its worker exits.
final class VisionRequestGate {
    struct Ticket: Equatable {
        let serial: Int64
        let epoch: Int64
    }
    private var epoch: Int64 = 0
    private var serial: Int64 = 0
    private var active: Ticket?
    private var lastAttemptAt: Int64?

    var busy: Bool { active != nil }

    func start(_ now: Int64, automatic: Bool) -> Ticket? {
        if busy || (automatic && !VisionPolicy.automaticDue(lastAttemptAt, now: now)) { return nil }
        lastAttemptAt = now
        serial += 1
        let ticket = Ticket(serial: serial, epoch: epoch)
        active = ticket
        return ticket
    }

    func invalidate() { epoch += 1 }
    func accepts(_ ticket: Ticket) -> Bool { active == ticket && ticket.epoch == epoch }
    func finish(_ ticket: Ticket) { if active == ticket { active = nil } }
}

private func validBox(_ box: VisionBox) -> Bool {
    let values = [box.yMin, box.xMin, box.yMax, box.xMax]
    return values.allSatisfy { $0.isFinite && $0 >= 0 && $0 <= 1000 } && box.yMin < box.yMax && box.xMin < box.xMax
}

/// Maps normalized Gemini [y,x,y,x] boxes through a centered aspect-fill crop
/// (AVLayerVideoGravity.resizeAspectFill), matching the live preview.
func cropVisionBox(_ box: VisionBox, imageWidth: Int, imageHeight: Int,
                   viewportWidth: Double, viewportHeight: Double) -> VisionScreenBox? {
    mapVisionBox(box, imageWidth: imageWidth, imageHeight: imageHeight,
                 viewportWidth: viewportWidth, viewportHeight: viewportHeight, fill: true)
}

/// Imported photos are displayed with aspect-fit, so their boxes use the fit transform.
func fitVisionBox(_ box: VisionBox, imageWidth: Int, imageHeight: Int,
                  viewportWidth: Double, viewportHeight: Double) -> VisionScreenBox? {
    mapVisionBox(box, imageWidth: imageWidth, imageHeight: imageHeight,
                 viewportWidth: viewportWidth, viewportHeight: viewportHeight, fill: false)
}

private func mapVisionBox(_ box: VisionBox, imageWidth: Int, imageHeight: Int,
                          viewportWidth: Double, viewportHeight: Double, fill: Bool) -> VisionScreenBox? {
    guard imageWidth > 0, imageHeight > 0, viewportWidth.isFinite, viewportHeight.isFinite,
          viewportWidth > 0, viewportHeight > 0, validBox(box) else { return nil }
    let w = Double(imageWidth), h = Double(imageHeight)
    let scale = fill ? max(viewportWidth / w, viewportHeight / h) : min(viewportWidth / w, viewportHeight / h)
    let offsetX = (viewportWidth - w * scale) / 2
    let offsetY = (viewportHeight - h * scale) / 2
    let left = max(0, offsetX + box.xMin / 1000 * w * scale)
    let top = max(0, offsetY + box.yMin / 1000 * h * scale)
    let right = min(viewportWidth, offsetX + box.xMax / 1000 * w * scale)
    let bottom = min(viewportHeight, offsetY + box.yMax / 1000 * h * scale)
    return right > left && bottom > top ? VisionScreenBox(left: left, top: top, right: right, bottom: bottom) : nil
}
