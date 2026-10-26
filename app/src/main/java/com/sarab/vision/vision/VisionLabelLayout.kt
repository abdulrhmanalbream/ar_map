package com.sarab.vision.vision

import kotlin.math.abs
import kotlin.math.floor

/**
 * Keeps each label near its detection when possible. If a nearby choice blocks
 * later labels, all labels move to a nonoverlapping grid instead of stacking.
 * The result preserves detection order. Overlap is permitted only when the
 * available region cannot hold the requested label sizes and spacing.
 */
fun layoutVisionLabels(
    boxes: List<VisionScreenBox>, viewportWidth: Float, viewportHeight: Float,
    labelWidth: Float, labelHeight: Float, topInset: Float, bottomInset: Float, gap: Float,
): List<VisionScreenBox> {
    if (boxes.isEmpty()) return emptyList()
    if (!viewportWidth.isFinite() || !viewportHeight.isFinite() || viewportWidth <= 0 || viewportHeight <= 0) {
        return boxes.map { VisionScreenBox(0f, 0f, 0f, 0f) }
    }
    val top = topInset.takeIf { it.isFinite() }?.coerceIn(0f, viewportHeight) ?: 0f
    val bottom = (viewportHeight - (bottomInset.takeIf { it.isFinite() } ?: 0f).coerceAtLeast(0f))
        .coerceIn(top, viewportHeight)
    val width = (labelWidth.takeIf { it.isFinite() } ?: viewportWidth).coerceIn(0f, viewportWidth)
    val height = (labelHeight.takeIf { it.isFinite() } ?: bottom - top).coerceIn(0f, bottom - top)
    val spacing = (gap.takeIf { it.isFinite() } ?: 0f).coerceAtLeast(0f)
    val maxLeft = viewportWidth - width
    val maxTop = bottom - height

    fun rect(x: Float, y: Float): VisionScreenBox {
        val left = (x.takeIf { it.isFinite() } ?: 0f).coerceIn(0f, maxLeft)
        val labelTop = (y.takeIf { it.isFinite() } ?: top).coerceIn(top, maxTop)
        return VisionScreenBox(left, labelTop, left + width, labelTop + height)
    }
    fun conflicts(a: VisionScreenBox, b: VisionScreenBox): Boolean =
        a.left < b.right + spacing && a.right + spacing > b.left &&
            a.top < b.bottom + spacing && a.bottom + spacing > b.top

    val placed = mutableListOf<VisionScreenBox>()
    for (box in boxes) {
        val centerX = (box.left + box.right) / 2 - width / 2
        val candidates = listOf(
            rect(centerX, box.top - height - spacing), rect(centerX, box.bottom + spacing),
            rect(box.left, box.top), rect(box.right + spacing, box.top),
            rect(box.left - width - spacing, box.top),
            rect(centerX, box.top - 2 * (height + spacing)),
            rect(centerX, box.bottom + height + 2 * spacing),
        ).distinct()
        val available = candidates.firstOrNull { candidate -> placed.none { conflicts(candidate, it) } }
        if (available == null) break
        placed += available
    }
    if (placed.size == boxes.size) return placed

    // Reflow the whole set: an early centered label must not strand later ones.
    if (width > 0f && height > 0f) {
        val columns = floor((viewportWidth + spacing) / (width + spacing)).toInt().coerceAtLeast(1)
        val rows = floor((bottom - top + spacing) / (height + spacing)).toInt().coerceAtLeast(1)
        if (columns.toLong() * rows >= boxes.size) {
            // More grid slots than labels cannot improve their separation; cap
            // each axis to the count so malformed tiny sizes cannot allocate a huge grid.
            val usedColumns = minOf(columns, boxes.size)
            val usedRows = minOf(rows, boxes.size)
            val grid = (0 until usedRows).flatMap { row ->
                (0 until usedColumns).map { column ->
                    val x = if (usedColumns == 1) maxLeft / 2 else maxLeft * column / (usedColumns - 1)
                    val y = if (usedRows == 1) top else top + (maxTop - top) * row / (usedRows - 1)
                    rect(x, y)
                }
            }.toMutableList()
            return boxes.map { box ->
                val desiredX = (box.left + box.right) / 2
                val desiredY = (box.top + box.bottom) / 2
                val index = grid.indices.minByOrNull { index ->
                    val candidate = grid[index]
                    abs((candidate.left + candidate.right) / 2 - desiredX) +
                        abs((candidate.top + candidate.bottom) / 2 - desiredY)
                } ?: 0
                grid.removeAt(index)
            }
        }
    }
    // Physical space is insufficient; stay within the viewport and keep order.
    return boxes.map { rect(it.left, it.top) }
}
