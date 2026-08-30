package com.sarab.vision.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val Ground = Color(0xFF0A0E13)
private val GroundGlow = Color(0xFF131B24)
private val PathBlue = Color(0xFF4FD1FF)
private val Gold = Color(0xFFE8B54D)

/** Total on-screen time: 2.2s to play, 0.35s to fade. Longer felt like a wait, not a boot. */
private const val PLAY_MS = 2200
private const val HOLD_MS = 250
private const val FADE_MS = 350

/**
 * Aperture Lock cold-start animation: the route draws in, three dots pulse
 * while ARCore spins up, the heading needle locks on, and four reticle
 * corners fly in and click shut around the destination marker.
 *
 * One-shot, not looping -- this plays once per Activity creation and calls
 * [onFinished], it does not gate real session state.
 */
@Composable
fun IgnitionSplash(onFinished: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val exitAlpha = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(PLAY_MS, easing = LinearEasing))
        delay(HOLD_MS.toLong())
        exitAlpha.animateTo(0f, tween(FADE_MS, easing = LinearEasing))
        onFinished()
    }

    val t = progress.value

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = exitAlpha.value }
            .background(Ground),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .size(260.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(GroundGlow, Ground),
                        radius = 400f
                    )
                )
        ) {
            drawIgnitionScene(t)
        }
    }
}

/** Maps [t] onto a 0..1 progress local to the [start]..[end] window. */
private fun segment(t: Float, start: Float, end: Float): Float =
    ((t - start) / (end - start)).coerceIn(0f, 1f)

/** Triangular rise-then-fall bump, for the three "thinking" dots. */
private fun bump(t: Float, start: Float, end: Float): Float {
    val local = segment(t, start, end)
    return if (local <= 0.5f) local * 2f else (1f - local) * 2f
}

private fun DrawScope.drawIgnitionScene(t: Float) {
    // Same 120-unit grid as the launcher icon and the Mirage Marks identity,
    // scaled to fill this canvas with a little breathing room (132 virtual
    // units) around it.
    val scale = size.minDimension / 132f
    val originX = (size.width - 120f * scale) / 2f
    val originY = (size.height - 120f * scale) / 2f
    fun p(x: Float, y: Float) = Offset(originX + x * scale, originY + y * scale)

    // -- Start dot --
    val startDotIn = FastOutSlowInEasing.transform(segment(t, 0f, 0.06f))
    drawCircle(PathBlue, radius = 4.5f * scale * startDotIn, center = p(28f, 94f))

    // -- Route: drawn progressively via PathMeasure, mitred like the app's PathRenderer --
    val routeDraw = FastOutSlowInEasing.transform(segment(t, 0.02f, 0.32f))
    if (routeDraw > 0f) {
        val full = Path().apply {
            moveTo(p(28f, 94f).x, p(28f, 94f).y)
            lineTo(p(28f, 62f).x, p(28f, 62f).y)
            lineTo(p(74f, 62f).x, p(74f, 62f).y)
            lineTo(p(74f, 38f).x, p(74f, 38f).y)
        }
        val measure = PathMeasure().apply { setPath(full, false) }
        val segmentPath = Path()
        measure.getSegment(0f, measure.length * routeDraw, segmentPath, true)
        drawPath(
            segmentPath,
            color = PathBlue,
            style = Stroke(width = 6f * scale, cap = StrokeCap.Butt, join = StrokeJoin.Miter)
        )
    }

    // -- Three "thinking" dots along the path's straight run --
    val dotR = 3f * scale
    drawCircle(PathBlue, dotR, p(44f, 62f), alpha = bump(t, 0.32f, 0.42f))
    drawCircle(PathBlue, dotR, p(51f, 62f), alpha = bump(t, 0.36f, 0.46f))
    drawCircle(PathBlue, dotR, p(58f, 62f), alpha = bump(t, 0.40f, 0.50f))

    // -- Heading needle, locking onto the path's end --
    val needleT = FastOutSlowInEasing.transform(segment(t, 0.50f, 0.60f))
    if (needleT > 0f) {
        val needle = Path().apply {
            moveTo(p(74f, 32.2f).x, p(74f, 32.2f).y)
            lineTo(p(79.8f, 45.9f).x, p(79.8f, 45.9f).y)
            lineTo(p(74f, 41.7f).x, p(74f, 41.7f).y)
            lineTo(p(68.2f, 45.9f).x, p(68.2f, 45.9f).y)
            close()
        }
        drawPath(needle, color = Gold, alpha = needleT)
    }

    // -- Hover shadow, then the marker itself --
    val markerT = FastOutSlowInEasing.transform(segment(t, 0.62f, 0.76f))
    val shadowAlpha = segment(t, 0.66f, 0.78f) * 0.35f
    drawOval(
        color = Color.Black,
        alpha = shadowAlpha,
        topLeft = Offset(p(67f, 26.8f).x, p(67f, 26.8f).y),
        size = Size(14f * scale, 4f * scale)
    )
    if (markerT > 0f) {
        val half = 5.5f * scale * markerT
        val center = p(74f, 20f)
        rotate(45f, pivot = center) {
            drawRect(
                color = Gold,
                topLeft = Offset(center.x - half, center.y - half),
                size = Size(half * 2f, half * 2f),
                alpha = markerT
            )
        }
    }

    // -- Four reticle corners, flying in from outside the frame and clicking shut --
    val cornerT = FastOutSlowInEasing.transform(segment(t, 0.66f, 0.80f))
    val cornerAlpha = segment(t, 0.66f, 0.72f)
    val fly = (1f - cornerT) * 11f * scale
    val arm = 4f * scale
    val cornerStroke = Stroke(width = 2.2f * scale, cap = StrokeCap.Square)

    fun corner(nearX: Float, nearY: Float, dx: Float, dy: Float) {
        val origin = p(nearX, nearY) + Offset(dx * fly, dy * fly)
        val path = Path().apply {
            moveTo(origin.x, origin.y)
            lineTo(origin.x, origin.y - dy * arm)
            moveTo(origin.x, origin.y)
            lineTo(origin.x - dx * arm, origin.y)
        }
        drawPath(path, color = PathBlue, alpha = cornerAlpha, style = cornerStroke)
    }
    corner(67f, 13f, -1f, -1f)
    corner(81f, 13f, 1f, -1f)
    corner(67f, 27f, -1f, 1f)
    corner(81f, 27f, 1f, 1f)
}
