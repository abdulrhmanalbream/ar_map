package com.sarab.vision.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sarab.vision.core.GuidanceMode
import com.sarab.vision.core.Landmark
import com.sarab.vision.core.ScreenPlacement
import com.sarab.vision.core.formatDistanceAr
import com.sarab.vision.core.markerScaleFor

private val Accent = Color(0xFF4FC3F7)
private val Amber = Color(0xFFFFB300)
private val Panel = Color(0xE6121A24)
private val Muted = Color(0xFF9FB3C8)

/**
 * Floating markers for landmarks that are too far away for ARCore.
 *
 * Placement comes from GPS bearing rather than ARCore tracking, which is what
 * makes a 250m landmark possible at all: we only need its DIRECTION, and GPS
 * gives us that exactly. ARCore could never anchor something that far out.
 */
@Composable
fun DistantLandmarkMarkers(
    placements: List<Pair<Landmark, ScreenPlacement>>,
    selectedId: String?,
    onSelect: (Landmark) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        placements.forEach { (landmark, placement) ->
            if (!placement.visible) return@forEach

            val scale = markerScaleFor(placement.distanceMeters)
            val isSelected = landmark.id == selectedId

            // Normalised (-1..1) -> fraction of the screen.
            BoxWithFractionOffset(
                fractionX = (placement.x + 1f) / 2f,
                fractionY = (1f - placement.y) / 2f
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .background(
                                if (isSelected) Amber else Panel,
                                RoundedCornerShape(14.dp)
                            )
                            .padding(
                                horizontal = (14 * scale).dp,
                                vertical = (9 * scale).dp
                            )
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = landmark.name,
                                color = if (isSelected) Color(0xFF1B2430) else Color.White,
                                fontSize = (14 * scale).sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = formatDistanceAr(placement.distanceMeters),
                                color = if (isSelected) Color(0xFF3A2E00) else Accent,
                                fontSize = (12 * scale).sp
                            )
                        }
                    }

                    // A stem so the label reads as pinned to a place on the
                    // ground rather than floating arbitrarily in the sky.
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height((22 * scale).dp)
                            .background(if (isSelected) Amber else Color(0x99FFFFFF))
                    )
                    Box(
                        modifier = Modifier
                            .size((8 * scale).dp)
                            .background(if (isSelected) Amber else Accent, CircleShape)
                    )
                }
            }
        }
    }
}

/**
 * Large direction arrow for the currently selected target.
 *
 * Rotates to the target's relative bearing, so it points the way even when
 * the landmark is behind the user and no marker can be shown.
 */
@Composable
fun DirectionArrow(
    relativeDegrees: Double,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(120.dp)) {
        rotate(degrees = relativeDegrees.toFloat()) {
            drawNavigationArrow(Accent)
        }
    }
}

private fun DrawScope.drawNavigationArrow(color: Color) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.5f, h * 0.12f)
        lineTo(w * 0.82f, h * 0.80f)
        lineTo(w * 0.5f, h * 0.63f)
        lineTo(w * 0.18f, h * 0.80f)
        close()
    }
    drawPath(path, color)
    // Soft halo so the arrow stays readable against a bright, sunlit scene.
    drawCircle(
        color = color.copy(alpha = 0.15f),
        radius = w * 0.46f,
        center = Offset(w / 2f, h / 2f)
    )
}

/**
 * Banner shown when GPS cannot tell two nearby buildings apart.
 *
 * This is the honest answer to a real limit: with 20m between buildings and
 * 5-10m GPS error, the app genuinely does not know which one the user is at.
 * Asking them to check the photo is better than confidently guessing wrong.
 */
@Composable
fun AmbiguityPrompt(
    candidates: List<Landmark>,
    onPick: (Landmark) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(Panel, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Text(
            "أنت قريب من عدة مبانٍ",
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "دقة GPS لا تكفي للتفريق بينها. اختر المبنى الذي أمامك:",
            color = Muted,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(14.dp))

        candidates.forEach { lm ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .background(Color(0xFF1B2836), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(Accent.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(lm.category.letter, color = Accent, fontSize = 15.sp)
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    lm.name,
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "هذا هو",
                    color = Amber,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .background(Color(0x33FFB300), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }
        }
    }
}

/** Bottom instruction bar: the single most important thing to do next. */
@Composable
fun GuidanceBar(
    targetName: String,
    instruction: String,
    mode: GuidanceMode,
    onChangeTarget: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(Panel, RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                targetName,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                instruction,
                color = when (mode) {
                    is GuidanceMode.Arrived -> Color(0xFF66D9AF)
                    is GuidanceMode.Ambiguous -> Amber
                    else -> Accent
                },
                fontSize = 14.sp
            )
        }
        Text(
            "تغيير",
            color = Muted,
            fontSize = 13.sp,
            modifier = Modifier
                .background(Color(0xFF1B2836), RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 9.dp)
        )
    }
}

/** Edge chevron pointing to an off-screen landmark. */
@Composable
fun OffScreenIndicator(direction: Int, label: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .background(Panel, RoundedCornerShape(24.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = if (direction < 0) "◀" else "▶",
            color = Amber,
            fontSize = 16.sp
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}

/**
 * Positions content at a fraction of the parent, since Compose has no direct
 * "place at 30% across" modifier.
 */
@Composable
private fun BoxWithFractionOffset(
    fractionX: Float,
    fractionY: Float,
    content: @Composable () -> Unit
) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val density = LocalDensity.current
        val xDp: Dp = with(density) { (constraints.maxWidth * fractionX).toDp() }
        val yDp: Dp = with(density) { (constraints.maxHeight * fractionY).toDp() }

        Box(
            modifier = Modifier.offset(
                x = xDp - 70.dp, // roughly centre the label on the point
                y = yDp - 30.dp
            )
        ) {
            content()
        }
    }
}

@Composable
private fun Text(
    text: String,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight? = null,
    lineHeight: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    modifier: Modifier = Modifier
) {
    androidx.compose.material3.Text(
        text = text,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        textAlign = TextAlign.Start,
        modifier = modifier
    )
}
