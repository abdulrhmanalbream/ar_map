import Foundation

private func clamp(_ value: Double, _ low: Double, _ high: Double) -> Double {
    min(max(value, low), high)
}

/// Keeps each label near its detection when possible. If a nearby choice blocks
/// later labels, all labels move to a nonoverlapping grid instead of stacking.
/// The result preserves detection order. Overlap is permitted only when the
/// available region cannot hold the requested label sizes and spacing.
func layoutVisionLabels(_ boxes: [VisionScreenBox], viewportWidth: Double, viewportHeight: Double,
                        labelWidth: Double, labelHeight: Double, topInset: Double, bottomInset: Double,
                        gap: Double) -> [VisionScreenBox] {
    if boxes.isEmpty { return [] }
    guard viewportWidth.isFinite, viewportHeight.isFinite, viewportWidth > 0, viewportHeight > 0 else {
        return boxes.map { _ in VisionScreenBox(left: 0, top: 0, right: 0, bottom: 0) }
    }
    let top = topInset.isFinite ? clamp(topInset, 0, viewportHeight) : 0
    let bottom = clamp(viewportHeight - max(bottomInset.isFinite ? bottomInset : 0, 0), top, viewportHeight)
    let width = clamp(labelWidth.isFinite ? labelWidth : viewportWidth, 0, viewportWidth)
    let height = clamp(labelHeight.isFinite ? labelHeight : bottom - top, 0, bottom - top)
    let spacing = max(gap.isFinite ? gap : 0, 0)
    let maxLeft = viewportWidth - width
    let maxTop = bottom - height

    func rect(_ x: Double, _ y: Double) -> VisionScreenBox {
        let left = clamp(x.isFinite ? x : 0, 0, maxLeft)
        let labelTop = clamp(y.isFinite ? y : top, top, maxTop)
        return VisionScreenBox(left: left, top: labelTop, right: left + width, bottom: labelTop + height)
    }
    func conflicts(_ a: VisionScreenBox, _ b: VisionScreenBox) -> Bool {
        a.left < b.right + spacing && a.right + spacing > b.left &&
            a.top < b.bottom + spacing && a.bottom + spacing > b.top
    }

    var placed: [VisionScreenBox] = []
    for box in boxes {
        let centerX = (box.left + box.right) / 2 - width / 2
        var candidates: [VisionScreenBox] = []
        for candidate in [
            rect(centerX, box.top - height - spacing), rect(centerX, box.bottom + spacing),
            rect(box.left, box.top), rect(box.right + spacing, box.top),
            rect(box.left - width - spacing, box.top),
            rect(centerX, box.top - 2 * (height + spacing)),
            rect(centerX, box.bottom + height + 2 * spacing),
        ] where !candidates.contains(candidate) {
            candidates.append(candidate)
        }
        guard let available = candidates.first(where: { candidate in !placed.contains { conflicts(candidate, $0) } }) else {
            break
        }
        placed.append(available)
    }
    if placed.count == boxes.count { return placed }

    // Reflow the whole set: an early centered label must not strand later ones.
    if width > 0 && height > 0 {
        let columns = max(Int(((viewportWidth + spacing) / (width + spacing)).rounded(.down)), 1)
        let rows = max(Int(((bottom - top + spacing) / (height + spacing)).rounded(.down)), 1)
        if Int64(columns) * Int64(rows) >= Int64(boxes.count) {
            let usedColumns = min(columns, boxes.count)
            let usedRows = min(rows, boxes.count)
            var grid: [VisionScreenBox] = []
            for row in 0..<usedRows {
                for column in 0..<usedColumns {
                    let x = usedColumns == 1 ? maxLeft / 2 : maxLeft * Double(column) / Double(usedColumns - 1)
                    let y = usedRows == 1 ? top : top + (maxTop - top) * Double(row) / Double(usedRows - 1)
                    grid.append(rect(x, y))
                }
            }
            return boxes.map { box in
                let desiredX = (box.left + box.right) / 2
                let desiredY = (box.top + box.bottom) / 2
                var bestIndex = 0
                var bestDistance = Double.infinity
                for (index, candidate) in grid.enumerated() {
                    let distance = abs((candidate.left + candidate.right) / 2 - desiredX) +
                        abs((candidate.top + candidate.bottom) / 2 - desiredY)
                    if distance < bestDistance { bestDistance = distance; bestIndex = index }
                }
                return grid.remove(at: bestIndex)
            }
        }
    }
    // Physical space is insufficient; stay within the viewport and keep order.
    return boxes.map { rect($0.left, $0.top) }
}
