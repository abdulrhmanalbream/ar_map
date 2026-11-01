package com.sarab.vision.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sarab.vision.core.GlassesDisplayRect
import com.sarab.vision.core.GlassesScreenPoint
import com.sarab.vision.core.LatLng
import com.sarab.vision.core.glassesRouteChevrons
import com.sarab.vision.core.glassesRoutePreview
import com.sarab.vision.core.glassesTargetDirection
import com.sarab.vision.core.pointAlongGlassesRoute
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/** Mint reads as "seen" and stays distinct from the gold route on the same optics. */
private val OutlineMint = Hedayah.ArAccent
private val RouteGold = Hedayah.HudGold
private val HudWhite = Color(0xFFF3FFF9)
private val HelpAmber = Color(0xFFFFC46B)

/**
 * Answers fade so text never parks in the wearer's view: about a reading pace
 * of 60 ms per character, between 5 and 14 seconds.
 */
private const val ANSWER_MIN_MS = 5_000L
private const val ANSWER_MAX_MS = 14_000L
private const val ANSWER_MS_PER_CHAR = 60L

/** Long enough to read as "found it", short enough to never feel decorative. */
private const val OUTLINE_INTRO_MS = 520

enum class GlassesAssistantIndicator { NONE, WAITING_FOR_WAKE, LISTENING, THINKING, SPEAKING }
enum class GlassesHelpIndicator { NONE, SENDING, SENT }

/** One recognised thing, already mapped into display space (-1..+1, Y down). */
data class GlassesOutline(val id: String, val label: String, val rect: GlassesDisplayRect)

/** Inputs for the ground chevrons; heading/pitch/roll come from the glasses IMU. */
data class GlassesRouteInput(
    val position: LatLng,
    val routePoints: List<LatLng>,
    val targetPosition: LatLng?,
    val headingDegrees: Double,
    val pitchDegrees: Double?,
    val rollDegrees: Double?,
)

data class GlassesLinesStatus(
    val assistant: GlassesAssistantIndicator = GlassesAssistantIndicator.NONE,
    val answer: String? = null,
    val answerRevision: Long = 0L,
    val help: GlassesHelpIndicator = GlassesHelpIndicator.NONE,
    val hint: String? = null,
    val arrived: Boolean = false,
    val ambiguous: Boolean = false,
)

/**
 * The "lines only" glasses picture: route chevrons on the floor, corner
 * brackets on recognised things, and one quiet status line.
 *
 * Optical see-through glasses add light to the real world; black adds
 * nothing. So there is no camera video here (the wearer already sees the real
 * scene, sharper and without latency) and no panels or buttons (any filled
 * area becomes a grey veil). Every element is a thin bright stroke or short
 * text on pure black.
 */
@Composable
fun GlassesLinesHud(
    route: GlassesRouteInput?,
    outlines: List<GlassesOutline>,
    status: GlassesLinesStatus,
    displayHorizontalFovDegrees: Double,
    calibrationTarget: GlassesDisplayRect? = null,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }

            if (route != null && !status.arrived && !status.ambiguous) {
                RouteLines(route, displayHorizontalFovDegrees, widthPx.toDouble(), heightPx.toDouble())
            }
            outlines.forEach { outline ->
                key(outline.id) { OutlineBracket(outline, widthPx, heightPx) }
            }
            calibrationTarget?.let { CalibrationTarget(it, widthPx, heightPx) }
            StatusLine(status, Modifier.align(Alignment.BottomCenter))
            HelpBadge(status.help, Modifier.align(Alignment.TopEnd).padding(end = 28.dp))
        }
    }
}

@Composable
private fun RouteLines(route: GlassesRouteInput, fov: Double, width: Double, height: Double) {
    val pitch = route.pitchDegrees?.takeIf { it.isFinite() }
    val roll = route.rollDegrees?.takeIf { it.isFinite() }
    val ground = glassesRoutePreview(route.position, route.routePoints, route.headingDegrees)
    val target = route.targetPosition?.let { glassesTargetDirection(route.position, it, route.headingDegrees) }
    val lookahead = pointAlongGlassesRoute(ground, 7.0) ?: ground.lastOrNull()
    val direction = lookahead?.let { Math.toDegrees(atan2(it.x.toDouble(), -it.z.toDouble())) }
        ?: target?.relativeDegrees ?: return
    // Projected with the DISPLAY field of view and the display's own aspect:
    // chevrons are placed where the floor is through the optics, not where it
    // was in a camera frame.
    val arrows = if (pitch != null && roll != null && width > 0 && height > 0) glassesRouteChevrons(
        ground, pitch, roll, fov, width / height, width, height,
        minimumGapPixels = 40.0,
    ) else emptyList()
    Canvas(Modifier.fillMaxSize()) {
        fun pixel(point: GlassesScreenPoint) = Offset(
            ((point.x + 1.0) * size.width / 2.0).toFloat(),
            ((point.y + 1.0) * size.height / 2.0).toFloat(),
        )
        for (arrow in arrows.asReversed()) {
            val path = Path().apply {
                val left = pixel(arrow.left); val tip = pixel(arrow.tip); val right = pixel(arrow.right)
                moveTo(left.x, left.y); lineTo(tip.x, tip.y); lineTo(right.x, right.y)
            }
            val core = (64.0 / arrow.tip.depthMeters).coerceIn(3.0, 7.0).toFloat().dp.toPx()
            // A wide, faint stroke under a crisp one reads as a glow on
            // additive optics; there is no dark outline because black is invisible.
            drawPath(path, RouteGold.copy(alpha = 0.22f),
                style = Stroke(core * 3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, RouteGold, style = Stroke(core, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
    // When the floor path is out of view, one compact arrow tells the wearer where to turn.
    if (arrows.isEmpty() || abs(direction) > 35.0) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Row(Modifier.padding(top = 28.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Canvas(Modifier.size(34.dp)) { drawTurnArrow(direction.toFloat()) }
                Text(when {
                    abs(direction) > 125.0 -> "المسار خلفك"
                    direction > 30.0 -> "يمينك"
                    direction < -30.0 -> "يسارك"
                    else -> "أمامك"
                }, color = RouteGold, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun DrawScope.drawTurnArrow(degrees: Float) {
    rotate(degrees) {
        val path = Path().apply {
            moveTo(size.width * 0.5f, size.height * 0.08f)
            lineTo(size.width * 0.86f, size.height * 0.8f)
            lineTo(size.width * 0.5f, size.height * 0.6f)
            lineTo(size.width * 0.14f, size.height * 0.8f)
            close()
        }
        drawPath(path, RouteGold.copy(alpha = 0.25f), style = Stroke(7.dp.toPx(), join = StrokeJoin.Round))
        drawPath(path, RouteGold, style = Stroke(2.5.dp.toPx(), join = StrokeJoin.Round))
    }
}

@Composable
private fun OutlineBracket(outline: GlassesOutline, widthPx: Float, heightPx: Float) {
    val intro = remember { Animatable(0f) }
    LaunchedEffect(Unit) { intro.animateTo(1f, tween(OUTLINE_INTRO_MS, easing = FastOutSlowInEasing)) }
    val density = LocalDensity.current
    val r = outline.rect
    val left = ((r.left + 1.0) * widthPx / 2.0).toFloat()
    val right = ((r.right + 1.0) * widthPx / 2.0).toFloat()
    val top = ((r.top + 1.0) * heightPx / 2.0).toFloat()
    val bottom = ((r.bottom + 1.0) * heightPx / 2.0).toFloat()
    val t = intro.value
    Canvas(Modifier.fillMaxSize()) {
        // The bracket closes in from 14 dp outside the object and its glow
        // flares once: that single motion is the "found it" moment.
        val spread = (1f - t) * 14.dp.toPx()
        val l = left - spread; val rr = right + spread; val tp = top - spread; val b = bottom + spread
        val arm = (minOf(rr - l, b - tp) * 0.24f).coerceIn(12.dp.toPx(), 40.dp.toPx())
        val glowAlpha = 0.18f + 0.5f * (1f - t)
        val corners = listOf(
            listOf(Offset(l, tp + arm), Offset(l, tp), Offset(l + arm, tp)),
            listOf(Offset(rr - arm, tp), Offset(rr, tp), Offset(rr, tp + arm)),
            listOf(Offset(l, b - arm), Offset(l, b), Offset(l + arm, b)),
            listOf(Offset(rr - arm, b), Offset(rr, b), Offset(rr, b - arm)),
        )
        corners.forEach { points ->
            val path = Path().apply {
                moveTo(points[0].x, points[0].y); lineTo(points[1].x, points[1].y); lineTo(points[2].x, points[2].y)
            }
            drawPath(path, OutlineMint.copy(alpha = glowAlpha * t.coerceAtLeast(0.35f)),
                style = Stroke(9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, OutlineMint.copy(alpha = 0.35f + 0.65f * t),
                style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
    if (outline.label.isNotBlank()) {
        var labelSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        val gap = with(density) { 10.dp.toPx() }
        val centerX = (left + right) / 2f
        // Above the object when there is room, otherwise below it.
        val above = top - gap - labelSize.height >= 0f
        val labelTop = if (above) top - gap - labelSize.height else bottom + gap
        val labelLeft = (centerX - labelSize.width / 2f).coerceIn(0f, (widthPx - labelSize.width).coerceAtLeast(0f))
        Canvas(Modifier.fillMaxSize()) {
            val anchorY = if (above) top else bottom
            val labelEdge = if (above) labelTop + labelSize.height else labelTop
            drawLine(OutlineMint.copy(alpha = 0.7f * t), Offset(centerX, anchorY), Offset(centerX, labelEdge),
                strokeWidth = 1.2.dp.toPx())
        }
        Text(outline.label, color = HudWhite.copy(alpha = t), fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.absoluteOffset { IntOffset(labelLeft.roundToInt(), labelTop.roundToInt()) }
                .widthIn(max = with(density) { (widthPx * 0.42f).toDp() })
                .onSizeChanged { labelSize = it })
    }
}

@Composable
private fun CalibrationTarget(rect: GlassesDisplayRect, widthPx: Float, heightPx: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val cx = ((rect.centerX + 1.0) * widthPx / 2.0).toFloat()
        val cy = ((rect.centerY + 1.0) * heightPx / 2.0).toFloat()
        val half = 34.dp.toPx()
        val arm = 14.dp.toPx()
        val stroke = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round)
        listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f).forEach { (sx, sy) ->
            val x = cx + sx * half; val y = cy + sy * half
            drawLine(HelpAmber, Offset(x, y), Offset(x - sx * arm, y), stroke.width, StrokeCap.Round)
            drawLine(HelpAmber, Offset(x, y), Offset(x, y - sy * arm), stroke.width, StrokeCap.Round)
        }
        drawCircle(HelpAmber, 3.dp.toPx(), Offset(cx, cy))
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Text("طابق الإطار مع ما في منتصف شاشة الجوال، ثم اضبطه من الجوال",
            color = HelpAmber, fontSize = 18.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 24.dp, start = 32.dp, end = 32.dp))
    }
}

@Composable
private fun StatusLine(status: GlassesLinesStatus, modifier: Modifier) {
    var answerVisible by remember { mutableStateOf(false) }
    LaunchedEffect(status.answer, status.answerRevision) {
        val answer = status.answer
        if (answer.isNullOrBlank()) { answerVisible = false; return@LaunchedEffect }
        answerVisible = true
        delay((ANSWER_MIN_MS + answer.length * ANSWER_MS_PER_CHAR).coerceAtMost(ANSWER_MAX_MS))
        answerVisible = false
    }
    Column(modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(answerVisible, enter = fadeIn(tween(250)), exit = fadeOut(tween(600))) {
            Text(status.answer.orEmpty(), color = HudWhite, fontSize = 21.sp, lineHeight = 30.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.dp))
        }
        val (text, color) = when {
            status.arrived -> "وصلت إلى وجهتك" to OutlineMint
            status.ambiguous -> "أكّد المبنى من الجوال" to HelpAmber
            status.assistant == GlassesAssistantIndicator.LISTENING -> "أسمعك…" to OutlineMint
            status.assistant == GlassesAssistantIndicator.THINKING -> "أفكّر…" to OutlineMint
            status.assistant == GlassesAssistantIndicator.SPEAKING -> "" to OutlineMint
            status.hint != null -> status.hint.orEmpty() to HelpAmber
            else -> "" to OutlineMint
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status.assistant != GlassesAssistantIndicator.NONE) {
                // A small steady dot says "the assistant can hear «يا مطوف»"; it
                // brightens when actually listening for a question.
                val bright = status.assistant != GlassesAssistantIndicator.WAITING_FOR_WAKE
                Box(Modifier.size(if (bright) 10.dp else 7.dp)
                    .background(OutlineMint.copy(alpha = if (bright) 1f else 0.45f), CircleShape))
            }
            if (text.isNotEmpty()) Text(text, color = color, fontSize = 18.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun HelpBadge(help: GlassesHelpIndicator, modifier: Modifier) {
    if (help == GlassesHelpIndicator.NONE) return
    Text(if (help == GlassesHelpIndicator.SENT) "تم إرسال طلب المساعدة" else "جارٍ إرسال طلب المساعدة…",
        color = HelpAmber, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 10.dp))
}
