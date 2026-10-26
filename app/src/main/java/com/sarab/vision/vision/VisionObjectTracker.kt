package com.sarab.vision.vision

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Unsigned luminance bytes, sampled off Main with aspect ratio preserved; no bitmap is retained. */
data class VisionTrackingFrame(
    val width: Int,
    val height: Int,
    val luma: ByteArray,
    val atElapsedMs: Long,
    val sessionId: Long,
)

data class VisionTrackingTarget(val itemId: String, val box: VisionBox)

/** confidence is image correlation, not confidence in the landmark's semantic identity. */
data class VisionTrackedBox(
    val itemId: String,
    val box: VisionBox,
    val confidence: Double,
    val atElapsedMs: Long,
    val sessionId: Long,
)

/**
 * Worker-owned local template tracker. Follows translation and modest uniform scale; it does not
 * estimate depth, camera intrinsics, world position, or a floor. Low texture, competing matches,
 * occlusion and large jumps lose a target permanently until another recognized capture initializes it.
 *
 * Initialize from the captured image, then replay chronological sampled history before displaying
 * the newest result. A first delayed direct match needs two consecutive strong matches. The caller
 * must additionally hide results whose atElapsedMs is stale against the actual monotonic clock.
 */
class VisionObjectTracker(captured: VisionTrackingFrame, targets: List<VisionTrackingTarget>) {
    private val width = captured.width
    private val height = captured.height
    private val session = captured.sessionId
    private val capturedAt = captured.atElapsedMs
    private var lastAt = capturedAt
    private var advanced = false
    private var active = validFrame(captured)
    private val tracked = mutableListOf<Target>()
    /** Initial eligible targets, unchanged after loss; lost targets must not get a stationary fallback. */
    val eligibleItemIds: Set<String>

    init {
        if (active) {
            val reference = blur(captured)
            val seen = mutableSetOf<String>()
            targets.take(MAX_TARGETS).forEach { target ->
                if (target.itemId.isNotBlank() && seen.add(target.itemId)) {
                    pixelBox(target.box)?.let { box ->
                        template(reference, box)?.let { patch -> tracked += Target(target.itemId, box, patch) }
                    }
                }
            }
            active = tracked.isNotEmpty()
        }
        eligibleItemIds = tracked.mapTo(linkedSetOf()) { it.id }.toSet()
    }

    /** Every returned box is a measured match in this frame, never a predicted stationary box. */
    fun update(frame: VisionTrackingFrame): List<VisionTrackedBox> {
        if (!active) return emptyList()
        if (!validFrame(frame) || frame.width != width || frame.height != height || frame.sessionId != session ||
            frame.atElapsedMs < lastAt || frame.atElapsedMs - capturedAt > MAX_CAPTURE_AGE_MS) {
            clear()
            return emptyList()
        }
        if (advanced && frame.atElapsedMs == lastAt) return emptyList()
        val gap = frame.atElapsedMs - lastAt
        val delayedFirst = !advanced && gap > MAX_CONTINUOUS_GAP_MS
        if ((advanced && gap > MAX_CONTINUOUS_GAP_MS) || (!advanced && gap > MAX_INITIAL_GAP_MS)) {
            clear()
            return emptyList()
        }
        val current = blur(frame)
        val output = mutableListOf<VisionTrackedBox>()
        val iterator = tracked.iterator()
        while (iterator.hasNext()) {
            val target = iterator.next()
            val match = match(current, target, global = !advanced)
            if (match == null) {
                iterator.remove()
                continue
            }
            val displacement = hypot(match.box.cx - target.last.cx, match.box.cy - target.last.cy)
            val maximumJump = hypot(width.toDouble(), height.toDouble()) * if (advanced) .18 else .35
            val strong = delayedFirst || target.confirming || (!advanced && displacement > 4.0)
            if (displacement > maximumJump || match.score < if (strong) STRONG_CORRELATION else MIN_CORRELATION) {
                iterator.remove()
                continue
            }
            target.last = match.box
            if (!advanced && (delayedFirst || displacement > 4.0)) {
                target.confirming = true
                continue
            }
            target.confirming = false
            output += VisionTrackedBox(target.id, normalized(match.box), match.score,
                frame.atElapsedMs, frame.sessionId)
        }
        lastAt = frame.atElapsedMs
        advanced = true
        active = tracked.isNotEmpty()
        return output
    }

    fun clear() {
        active = false
        tracked.clear()
    }

    private fun pixelBox(box: VisionBox): PixelBox? {
        if (listOf(box.xMin, box.xMax, box.yMin, box.yMax).any { !it.isFinite() || it !in 0.0..1000.0 } ||
            box.xMin >= box.xMax || box.yMin >= box.yMax) return null
        val left = box.xMin * width / 1000.0
        val right = box.xMax * width / 1000.0
        val top = box.yMin * height / 1000.0
        val bottom = box.yMax * height / 1000.0
        if (right - left < MIN_PATCH_EDGE || bottom - top < MIN_PATCH_EDGE) return null
        return PixelBox((left + right) / 2, (top + bottom) / 2, right - left, bottom - top)
    }

    private fun normalized(box: PixelBox) = VisionBox(
        ((box.cy - box.h / 2) / height * 1000).coerceIn(0.0, 1000.0),
        ((box.cx - box.w / 2) / width * 1000).coerceIn(0.0, 1000.0),
        ((box.cy + box.h / 2) / height * 1000).coerceIn(0.0, 1000.0),
        ((box.cx + box.w / 2) / width * 1000).coerceIn(0.0, 1000.0),
    )

    private fun template(image: FloatArray, box: PixelBox): Patch? {
        val values = FloatArray(GRID * GRID) { index ->
            sample(image, box.cx + ((index % GRID + .5) / GRID - .5) * box.w,
                box.cy + ((index / GRID + .5) / GRID - .5) * box.h)
        }
        val full = stats(values, 1)
        var variation = 0.0
        for (y in 0 until GRID) for (x in 0 until GRID) {
            if (x > 0) variation += abs(values[y * GRID + x] - values[y * GRID + x - 1])
            if (y > 0) variation += abs(values[y * GRID + x] - values[(y - 1) * GRID + x])
        }
        if (full.variance / full.count < 100.0 || variation / (2 * GRID * (GRID - 1)) < 3.0) return null
        return Patch(values, full, stats(values, 2))
    }

    private fun match(image: FloatArray, target: Target, global: Boolean): Match? {
        val scale = target.last.w / target.original.w
        val scales = if (global) doubleArrayOf(.8, .9, 1.0, 1.1, 1.2)
            else doubleArrayOf(scale * .94, scale, scale * 1.06)
        val candidates = ArrayList<Candidate>()
        val radius = max(12.0, max(width, height) * .15)
        for (factor in scales) {
            if (factor !in .70..1.45) continue
            val w = target.original.w * factor
            val h = target.original.h * factor
            if (w < MIN_PATCH_EDGE || h < MIN_PATCH_EDGE || w > width || h > height) continue
            val minX = if (global) w / 2 else max(w / 2, target.last.cx - radius)
            val maxX = if (global) width - w / 2 else min(width - w / 2, target.last.cx + radius)
            val minY = if (global) h / 2 else max(h / 2, target.last.cy - radius)
            val maxY = if (global) height - h / 2 else min(height - h / 2, target.last.cy + radius)
            if (maxX < minX || maxY < minY) continue
            for (yi in 0..ceil((maxY - minY) / 2).toInt()) {
                val cy = min(maxY, minY + yi * 2)
                for (xi in 0..ceil((maxX - minX) / 2).toInt()) {
                    val cx = min(maxX, minX + xi * 2)
                    val score = correlation(image, target.patch, PixelBox(cx, cy, w, h), coarse = true)
                    if (score >= .30) candidates += Candidate(cx, cy, factor, score)
                }
            }
        }
        if (candidates.isEmpty()) return null
        candidates.sortByDescending { it.score }
        val peakRadius = max(3.0, min(target.last.w, target.last.h) * .20)
        val seeds = mutableListOf<Candidate>()
        for (candidate in candidates) {
            if (seeds.none { hypot(it.cx - candidate.cx, it.cy - candidate.cy) < peakRadius }) {
                seeds += candidate
                if (seeds.size == MAX_REFINEMENT_PEAKS) break
            }
        }
        var best: Match? = null
        val refined = mutableListOf<Match>()
        for (seed in seeds) for (scaleAdjustment in doubleArrayOf(.97, 1.0, 1.03)) {
            val factor = seed.scale * scaleAdjustment
            if (factor !in .70..1.45) continue
            val w = target.original.w * factor
            val h = target.original.h * factor
            if (w < MIN_PATCH_EDGE || h < MIN_PATCH_EDGE) continue
            for (dy in -2..2) for (dx in -2..2) {
                val box = PixelBox(seed.cx + dx, seed.cy + dy, w, h)
                if (box.cx - w / 2 < 0 || box.cy + h / 2 > height || box.cy - h / 2 < 0 || box.cx + w / 2 > width) continue
                val score = correlation(image, target.patch, box, coarse = false)
                if (best == null || score > best.score) best = Match(box, score)
                refined += Match(box, score)
            }
        }
        val winner = best ?: return null
        if (winner.score < MIN_CORRELATION) return null
        // Competing peaks count even when their coarse subset scores better than the full patch:
        // uncertain textured/repeated scenes should hide a label, never choose an arbitrary object.
        var competitor = -1.0
        candidates.forEach {
            if (hypot(it.cx - winner.box.cx, it.cy - winner.box.cy) > peakRadius) competitor = max(competitor, it.score)
        }
        refined.forEach {
            if (hypot(it.box.cx - winner.box.cx, it.box.cy - winner.box.cy) > peakRadius) competitor = max(competitor, it.score)
        }
        if (winner.score - competitor < MIN_PEAK_MARGIN) return null
        return winner
    }

    private fun correlation(image: FloatArray, patch: Patch, box: PixelBox, coarse: Boolean): Double {
        val stride = if (coarse) 2 else 1
        val reference = if (coarse) patch.coarse else patch.full
        var sum = 0.0
        var squared = 0.0
        var cross = 0.0
        for (y in 0 until GRID step stride) for (x in 0 until GRID step stride) {
            val value = sample(image, box.cx + ((x + .5) / GRID - .5) * box.w,
                box.cy + ((y + .5) / GRID - .5) * box.h).toDouble()
            sum += value
            squared += value * value
            cross += value * patch.values[y * GRID + x]
        }
        val variance = squared - sum * sum / reference.count
        if (variance / reference.count < 64.0 || reference.variance <= 0) return -1.0
        return ((cross - sum * reference.sum / reference.count) / sqrt(variance * reference.variance)).coerceIn(-1.0, 1.0)
    }

    private fun sample(image: FloatArray, x: Double, y: Double): Float {
        val safeX = x.coerceIn(0.0, width - 1.0)
        val safeY = y.coerceIn(0.0, height - 1.0)
        val x0 = safeX.toInt()
        val y0 = safeY.toInt()
        val x1 = min(width - 1, x0 + 1)
        val y1 = min(height - 1, y0 + 1)
        val dx = (safeX - x0).toFloat()
        val dy = (safeY - y0).toFloat()
        val top = image[y0 * width + x0] * (1 - dx) + image[y0 * width + x1] * dx
        val bottom = image[y1 * width + x0] * (1 - dx) + image[y1 * width + x1] * dx
        return top * (1 - dy) + bottom * dy
    }

    private data class PixelBox(val cx: Double, val cy: Double, val w: Double, val h: Double)
    private data class Statistics(val sum: Double, val variance: Double, val count: Int)
    private data class Patch(val values: FloatArray, val full: Statistics, val coarse: Statistics)
    private data class Candidate(val cx: Double, val cy: Double, val scale: Double, val score: Double)
    private data class Match(val box: PixelBox, val score: Double)
    private class Target(val id: String, val original: PixelBox, val patch: Patch) {
        var last = original
        var confirming = false
    }

    private companion object {
        const val GRID = 12
        const val MAX_TARGETS = 6
        const val MAX_REFINEMENT_PEAKS = 4
        const val MIN_PATCH_EDGE = 10.0
        const val MIN_CORRELATION = .90
        const val STRONG_CORRELATION = .95
        const val MIN_PEAK_MARGIN = .07
        const val MAX_CONTINUOUS_GAP_MS = 1_500L
        const val MAX_INITIAL_GAP_MS = 8_000L
        const val MAX_CAPTURE_AGE_MS = 30_000L

        fun validFrame(frame: VisionTrackingFrame) = frame.width in 16..160 && frame.height in 16..160 &&
            frame.luma.size == frame.width * frame.height && frame.atElapsedMs >= 0 && frame.sessionId != 0L

        fun stats(values: FloatArray, stride: Int): Statistics {
            var sum = 0.0
            var squared = 0.0
            var count = 0
            for (y in 0 until GRID step stride) for (x in 0 until GRID step stride) {
                val value = values[y * GRID + x].toDouble()
                sum += value
                squared += value * value
                count++
            }
            return Statistics(sum, squared - sum * sum / count, count)
        }

        /** A fixed 3x3 Gaussian removes single-pixel aliasing before matching; work is bounded. */
        fun blur(frame: VisionTrackingFrame): FloatArray {
            val w = frame.width
            val h = frame.height
            val horizontal = FloatArray(w * h)
            val output = FloatArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                fun at(column: Int) = frame.luma[y * w + column.coerceIn(0, w - 1)].toInt() and 255
                horizontal[y * w + x] = (at(x - 1) + 2 * at(x) + at(x + 1)) / 4f
            }
            for (y in 0 until h) for (x in 0 until w) {
                output[y * w + x] = (horizontal[max(0, y - 1) * w + x] + 2 * horizontal[y * w + x] +
                    horizontal[min(h - 1, y + 1) * w + x]) / 4f
            }
            return output
        }
    }
}
