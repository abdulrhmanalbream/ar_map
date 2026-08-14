package com.sarab.vision.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sarab.vision.core.TravelMode
import com.sarab.vision.core.compassTarget
import com.sarab.vision.core.compassTicks
import com.sarab.vision.core.formatDistanceAr

private val Ink = Color(0xFF0B1520)
private val Accent = Color(0xFF4FC3F7)
private val Amber = Color(0xFFFFB300)
private val Muted = Color(0xFF9FB3C8)

/**
 * The compass ribbon across the top of the camera.
 *
 * Answers the two questions a camera view otherwise cannot: which way am I
 * facing, and where is my destination relative to that. The destination is
 * pinned at its true bearing, so turning the phone slides it along the strip
 * exactly as the world moves.
 *
 * Kept deliberately short and translucent -- it is an instrument, not a
 * panel, and the camera underneath is the point of the screen.
 */
@Composable
fun CompassBar(
    headingDegrees: Double?,
    targetBearingDegrees: Double?,
    targetDistanceMeters: Double,
    targetName: String?,
    travelMode: TravelMode,
    onModeChange: (TravelMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                // Fades into the camera rather than sitting on a hard slab.
                Brush.verticalGradient(
                    listOf(Ink.copy(alpha = 0.92f), Ink.copy(alpha = 0.0f))
                )
            )
            .padding(top = 8.dp, bottom = 20.dp)
    ) {
        // ---- Travel mode -------------------------------------------------
        Row(
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .background(Color(0xCC16202C), RoundedCornerShape(22.dp))
                    .padding(3.dp)
            ) {
                TravelMode.entries.forEach { mode ->
                    val selected = mode == travelMode
                    Text(
                        text = mode.labelAr,
                        color = if (selected) Ink else Muted,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .background(
                                if (selected) Accent else Color.Transparent,
                                RoundedCornerShape(19.dp)
                            )
                            .clickable { onModeChange(mode) }
                            .padding(horizontal = 18.dp, vertical = 8.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---- The ribbon --------------------------------------------------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
        ) {
            if (headingDegrees == null) {
                Text(
                    "جاري تحديد الاتجاه…",
                    color = Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                CompassRibbon(
                    headingDegrees = headingDegrees,
                    targetBearingDegrees = targetBearingDegrees,
                    targetDistanceMeters = targetDistanceMeters,
                    targetName = targetName
                )
            }
        }
    }
}

@Composable
private fun CompassRibbon(
    headingDegrees: Double,
    targetBearingDegrees: Double?,
    targetDistanceMeters: Double,
    targetName: String?
) {
    val ticks = compassTicks(headingDegrees)
    val target = targetBearingDegrees?.let {
        compassTarget(headingDegrees, it, targetDistanceMeters)
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(46.dp)) {
            val w = size.width
            val midY = size.height * 0.42f

            // Tick marks. Cardinals are taller and brighter so the eye can
            // find north without reading labels.
            ticks.forEach { tick ->
                val x = w / 2f + (tick.x * w / 2f)
                val tall = tick.isCardinal
                drawLine(
                    color = if (tall) Color.White.copy(alpha = 0.9f)
                    else Color.White.copy(alpha = 0.35f),
                    start = Offset(x, midY - if (tall) 11f else 6f),
                    end = Offset(x, midY + if (tall) 11f else 6f),
                    strokeWidth = if (tall) 3f else 2f
                )
            }

            // The destination pin, drawn last so it is never hidden by ticks.
            target?.let { t ->
                val x = w / 2f + (t.x * w / 2f)
                val colour = if (t.visible) Amber else Amber.copy(alpha = 0.55f)
                drawCircle(colour, radius = 9f, center = Offset(x, midY))
                drawCircle(Ink, radius = 4f, center = Offset(x, midY))

                if (!t.visible) {
                    // Chevron at the edge: the target is behind you, so show
                    // which way to turn rather than dropping it silently.
                    val dir = t.offScreenDirection
                    val tip = x + dir * 16f
                    drawPath(
                        Path().apply {
                            moveTo(tip, midY)
                            lineTo(tip - dir * 11f, midY - 8f)
                            lineTo(tip - dir * 11f, midY + 8f)
                            close()
                        },
                        colour
                    )
                }
            }

            // Centre reticle: exactly where the camera is pointing.
            drawLine(
                color = Accent,
                start = Offset(w / 2f, midY - 16f),
                end = Offset(w / 2f, midY + 16f),
                strokeWidth = 4f
            )
        }

        // Cardinal labels, as text so Arabic shapes correctly.
        ticks.filter { it.isCardinal }.forEach { tick ->
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = tick.label,
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offsetFraction(tick.x)
                        .padding(top = 26.dp)
                )
            }
        }

        // Distance badge under the destination pin.
        if (target != null && targetName != null) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = formatDistanceAr(target.distanceMeters),
                    color = Amber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offsetFraction(target.x)
                        .padding(top = 26.dp)
                )
            }
        }
    }
}

/**
 * Positions a child at a normalised x across the parent.
 *
 * Compose has no "place at fraction" modifier, and the ribbon needs precise
 * angular placement, so the offset is computed from the measured width.
 */
@Composable
private fun Modifier.offsetFraction(x: Float): Modifier {
    return this.then(
        Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val parentWidth = constraints.maxWidth
            val centre = parentWidth / 2f + (x * parentWidth / 2f)
            val left = (centre - placeable.width / 2f).toInt()
            layout(placeable.width, placeable.height) {
                placeable.placeRelative(left, 0)
            }
        }
    )
}
