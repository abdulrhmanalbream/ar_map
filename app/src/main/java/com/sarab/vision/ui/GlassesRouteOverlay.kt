package com.sarab.vision.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sarab.vision.core.GlassesScreenPoint
import com.sarab.vision.core.LatLng
import com.sarab.vision.core.glassesRouteChevrons
import com.sarab.vision.core.glassesRoutePreview
import com.sarab.vision.core.glassesTargetDirection
import com.sarab.vision.core.pointAlongGlassesRoute
import kotlin.math.abs
import kotlin.math.atan2

private val GlassesRouteAccent = Color(0xFF69E8D7)

/** Camera and overlay share the full viewport and the same centred source-image crop. */
@Composable
fun GlassesRouteOverlay(
    userPosition: LatLng?,
    routePoints: List<LatLng>,
    targetPosition: LatLng?,
    headingDegrees: Double?,
    pitchDegrees: Double,
    rollDegrees: Double,
    modifier: Modifier = Modifier,
    horizontalFovDegrees: Double = 70.0,
    imageAspectRatio: Double = 4.0 / 3.0,
) {
    val here = userPosition?.takeIf { it.isValid } ?: return
    val heading = headingDegrees?.takeIf { it.isFinite() } ?: return
    if (!pitchDegrees.isFinite() || !rollDegrees.isFinite()) return
    val target = targetPosition?.let { glassesTargetDirection(here, it, heading) }
    val ground = glassesRoutePreview(here, routePoints, heading)
    if (ground.size < 2 && target == null) return
    val lookahead = pointAlongGlassesRoute(ground, 7.0) ?: ground.lastOrNull()
    val routeBearing = lookahead?.let { Math.toDegrees(atan2(it.x.toDouble(), -it.z.toDouble())) }
    val direction = routeBearing ?: target?.relativeDegrees ?: return

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val arrows = glassesRouteChevrons(
            ground, pitchDegrees, rollDegrees, horizontalFovDegrees, imageAspectRatio,
            with(density) { maxWidth.toPx().toDouble() }, with(density) { maxHeight.toPx().toDouble() },
            minimumGapPixels = with(density) { 24.dp.toPx().toDouble() },
        )
        Canvas(Modifier.fillMaxSize()) {
            if (size.width <= 0 || size.height <= 0) return@Canvas
            fun pixel(point: GlassesScreenPoint) = Offset(
                ((point.x + 1.0) * size.width / 2.0).toFloat(),
                ((point.y + 1.0) * size.height / 2.0).toFloat(),
            )
            clipRect {
                // Open, rounded road chevrons keep the scene readable. There is no
                // line extending under the wearer or solid triangle covering the road.
                for (arrow in arrows.asReversed()) {
                    val path = Path().apply {
                        val left = pixel(arrow.left)
                        val tip = pixel(arrow.tip)
                        val right = pixel(arrow.right)
                        moveTo(left.x, left.y)
                        lineTo(tip.x, tip.y)
                        lineTo(right.x, right.y)
                    }
                    val width = (48.0 / arrow.tip.depthMeters).coerceIn(2.5, 5.0).toFloat().dp.toPx()
                    val stroke = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    drawPath(path, Color(0xD9102028), style = Stroke(width + 1.5.dp.toPx(),
                        cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(path, GlassesRouteAccent, style = stroke)
                }
            }
        }

        // One maneuver cue survives when the path is outside the view or no route exists.
        if (arrows.isEmpty() || abs(direction) > 35.0) Row(
            Modifier.align(Alignment.TopCenter).padding(top = 76.dp, start = 16.dp, end = 16.dp)
                .background(Color(0xDB102028), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Canvas(Modifier.size(24.dp)) {
                rotate(direction.toFloat()) {
                    drawPath(Path().apply {
                        moveTo(size.width * 0.5f, size.height * 0.08f)
                        lineTo(size.width * 0.86f, size.height * 0.85f)
                        lineTo(size.width * 0.5f, size.height * 0.64f)
                        lineTo(size.width * 0.14f, size.height * 0.85f)
                        close()
                    }, GlassesRouteAccent)
                }
            }
            Text(
                when {
                    ground.size < 2 -> "اتجاه الوجهة فقط · لا يوجد مسار قريب"
                    abs(direction) > 125.0 -> "المسار خلفك"
                    direction > 30.0 -> "اتجه إلى المسار يمينك"
                    direction < -30.0 -> "اتجه إلى المسار يسارك"
                    else -> "اتبع المسار أمامك"
                },
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            )
        }
    }
}
