package com.sarab.vision.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sarab.vision.core.GeoBounds
import com.sarab.vision.core.GpsFix
import com.sarab.vision.core.Landmark
import com.sarab.vision.core.LandmarkCategory
import com.sarab.vision.core.LatLng
import com.sarab.vision.core.MapTransform
import com.sarab.vision.core.distanceMeters
import com.sarab.vision.core.formatDistanceAr
import kotlin.math.hypot

private val Bg = Color(0xFF0B1520)
private val Panel = Color(0xE6121A24)
private val Accent = Color(0xFF4FC3F7)
private val Amber = Color(0xFFFFB300)
private val Muted = Color(0xFF9FB3C8)
private val Grid = Color(0xFF1A2634)

/** Colour per landmark category, so the map reads at a glance. */
private fun categoryColour(c: LandmarkCategory): Color = when (c) {
    LandmarkCategory.HOUSING -> Color(0xFF66D9AF)
    LandmarkCategory.SPORTS -> Color(0xFFFFB300)
    LandmarkCategory.FACULTY -> Color(0xFF4FC3F7)
    LandmarkCategory.SERVICES -> Color(0xFFBA68C8)
    LandmarkCategory.GATE -> Color(0xFFEF5350)
    LandmarkCategory.OTHER -> Color(0xFF9FB3C8)
}

/**
 * 2D campus map, drawn from the surveyed landmarks.
 *
 * There is no pre-drawn campus image to work from, so the map is generated
 * from the coordinates themselves and auto-fits whatever has been captured.
 * That means it is useful from the second landmark onward rather than
 * blocked on artwork that does not exist.
 */
@Composable
fun CampusMapScreen(
    landmarks: List<Landmark>,
    userFix: GpsFix?,
    headingDegrees: Double?,
    selectedId: String?,
    onSelect: (Landmark) -> Unit,
    onClose: () -> Unit
) {
    val userPos = userFix?.position

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        if (landmarks.isEmpty()) {
            EmptyMapMessage(onClose)
            return@Box
        }

        // Include the user so the map always frames both them and the campus.
        val points = remember(landmarks, userPos) {
            landmarks.map { it.position } + listOfNotNull(userPos?.takeIf { it.isValid })
        }
        val bounds = remember(points) { GeoBounds.of(points) }

        if (bounds == null) {
            EmptyMapMessage(onClose)
            return@Box
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(landmarks, bounds) {
                    detectTapGestures { tap ->
                        val transform = MapTransform(bounds, size.width.toFloat(), size.height.toFloat())
                        // Pick the nearest landmark within a comfortable
                        // finger radius, so tapping is forgiving.
                        val hit = landmarks
                            .map { it to transform.toCanvas(it.position) }
                            .map { (lm, pt) ->
                                lm to hypot(tap.x - pt.x, tap.y - pt.y)
                            }
                            .filter { it.second < 90f }
                            .minByOrNull { it.second }
                            ?.first
                        if (hit != null) onSelect(hit)
                    }
                }
        ) {
            val transform = MapTransform(bounds, size.width, size.height)

            drawGrid()

            // Route line from the user to the selected landmark, so the map
            // answers "which way" and not just "where".
            val selected = landmarks.firstOrNull { it.id == selectedId }
            if (userPos != null && userPos.isValid && selected != null) {
                val from = transform.toCanvas(userPos)
                val to = transform.toCanvas(selected.position)
                drawLine(
                    color = Amber.copy(alpha = 0.75f),
                    start = Offset(from.x, from.y),
                    end = Offset(to.x, to.y),
                    strokeWidth = 5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))
                )
            }

            landmarks.forEach { lm ->
                val pt = transform.toCanvas(lm.position)
                val colour = categoryColour(lm.category)
                val isSelected = lm.id == selectedId

                if (isSelected) {
                    drawCircle(colour.copy(alpha = 0.22f), radius = 34f, center = Offset(pt.x, pt.y))
                }
                drawCircle(colour, radius = if (isSelected) 17f else 13f, center = Offset(pt.x, pt.y))
                drawCircle(Bg, radius = if (isSelected) 8f else 6f, center = Offset(pt.x, pt.y))
            }

            // User position last, so it is never hidden behind a landmark.
            if (userPos != null && userPos.isValid) {
                val pt = transform.toCanvas(userPos)

                // Accuracy halo: shows honestly how sure we are, which matters
                // when two buildings are only 20m apart.
                userFix?.accuracyMeters?.let { acc ->
                    val radiusPx = (acc / transform.metersPerPixel()).toFloat()
                    if (radiusPx.isFinite() && radiusPx > 1f) {
                        drawCircle(
                            color = Accent.copy(alpha = 0.12f),
                            radius = radiusPx.coerceAtMost(size.minDimension / 2f),
                            center = Offset(pt.x, pt.y)
                        )
                    }
                }

                translate(pt.x, pt.y) {
                    if (headingDegrees != null) {
                        rotate(headingDegrees.toFloat(), pivot = Offset.Zero) {
                            drawUserCone()
                        }
                    }
                }
                drawCircle(Color.White, radius = 11f, center = Offset(pt.x, pt.y))
                drawCircle(Accent, radius = 8f, center = Offset(pt.x, pt.y))
            }

            drawScaleBar(transform.scaleBarMeters(), transform.metersPerPixel())
        }

        // ---- Overlays ----------------------------------------------------
        // Sits on a solid backdrop: over a busy map the white title was
        // unreadable, and it visually collided with the close button.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Bg.copy(alpha = 0.92f))
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "خريطة الحرم",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        // Build the parts separately: interpolating a number
                        // straight into an Arabic string renders the digits
                        // and words in the wrong visual order.
                        buildString {
                            append("عدد المعالم: ")
                            append(landmarks.size)
                            userFix?.let {
                                append(" · دقة الموقع ")
                                append(it.accuracyMeters.toInt())
                                append(" م")
                            } ?: append(" · لا يوجد موقع")
                        },
                        color = Muted,
                        fontSize = 12.sp
                    )
                }
                Text(
                    "إغلاق",
                    color = Accent,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .background(Panel, RoundedCornerShape(10.dp))
                        .clickable(onClick = onClose)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }

        // Selected landmark summary at the bottom.
        landmarks.firstOrNull { it.id == selectedId }?.let { lm ->
            val distance = userPos?.takeIf { it.isValid }
                ?.let { distanceMeters(it, lm.position) }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .fillMaxWidth()
                    .background(Panel, RoundedCornerShape(18.dp))
                    .padding(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(categoryColour(lm.category).copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(lm.category.letter, color = categoryColour(lm.category), fontSize = 16.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(lm.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(
                        buildString {
                            append(lm.category.labelAr)
                            if (distance != null) {
                                append(" · يبعد ")
                                append(formatDistanceAr(distance))
                            }
                        },
                        color = Muted,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawGrid() {
    val step = 110f
    var x = 0f
    while (x < size.width) {
        drawLine(Grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += step
    }
    var y = 0f
    while (y < size.height) {
        drawLine(Grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += step
    }
}

/** Cone showing which way the user is facing. */
private fun DrawScope.drawUserCone() {
    val path = Path().apply {
        moveTo(0f, 0f)
        lineTo(-26f, -54f)
        lineTo(26f, -54f)
        close()
    }
    drawPath(path, Accent.copy(alpha = 0.35f))
}

private fun DrawScope.drawScaleBar(meters: Int, metersPerPixel: Double) {
    if (metersPerPixel <= 0) return
    val lengthPx = (meters / metersPerPixel).toFloat()
    if (!lengthPx.isFinite() || lengthPx <= 0f) return

    val y = size.height - 46f
    val x0 = 28f
    val x1 = x0 + lengthPx.coerceAtMost(size.width * 0.5f)

    drawLine(Color.White, Offset(x0, y), Offset(x1, y), strokeWidth = 3f)
    drawLine(Color.White, Offset(x0, y - 7f), Offset(x0, y + 7f), strokeWidth = 3f)
    drawLine(Color.White, Offset(x1, y - 7f), Offset(x1, y + 7f), strokeWidth = 3f)
}

@Composable
private fun EmptyMapMessage(onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "لا توجد معالم مسجلة بعد",
            color = Color.White,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "افتح وضع المسح وامشِ إلى كل معلم لتسجيله. تظهر الخريطة تلقائياً " +
                "بعد تسجيل أول معلمين.",
            color = Muted,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(22.dp))
        Text(
            "رجوع",
            color = Accent,
            fontSize = 15.sp,
            modifier = Modifier
                .background(Panel, RoundedCornerShape(12.dp))
                .clickable(onClick = onClose)
                .padding(horizontal = 22.dp, vertical = 12.dp)
        )
    }
}
