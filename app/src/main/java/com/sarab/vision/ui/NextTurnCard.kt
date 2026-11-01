package com.sarab.vision.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sarab.vision.core.ManeuverType
import com.sarab.vision.core.UpcomingManeuver
import com.sarab.vision.core.maneuverInstructionAr
import com.sarab.vision.core.maneuverVerbAr

/**
 * The next instruction, the way every navigation app shows it: a turn arrow,
 * «بعد ٤٠ م انعطف يميناً» large, and what follows it small underneath, so the
 * walker can glance once and keep their eyes on the path.
 */
@Composable
fun NextTurnCard(next: UpcomingManeuver, modifier: Modifier = Modifier) {
    val m = next.maneuver
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .background(Hedayah.HudPanel, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Canvas(Modifier.size(40.dp)) { drawManeuverArrow(m.type, m.turnDegrees, Hedayah.HudGold) }
        Column(Modifier.weight(1f)) {
            Text(
                maneuverInstructionAr(m, next.distanceMeters),
                color = Hedayah.ArText,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val then = next.then
            if (then != null && then.type != ManeuverType.DEPART) {
                Text(
                    "ثم ${maneuverVerbAr(then.type)}",
                    color = Hedayah.HudGold.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * A turn arrow: a stem coming up from the bottom that bends by the turn's
 * angle and ends in a head. Arrival is a ring; a U-turn bends right round.
 * Shared by the phone card and the glasses so both read the same.
 */
fun DrawScope.drawManeuverArrow(type: ManeuverType, turnDegrees: Double, color: Color, strokeDp: Float = 3.5f) {
    val stroke = Stroke(strokeDp.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val w = size.width
    val h = size.height
    if (type == ManeuverType.ARRIVE) {
        drawCircle(color, radius = w * 0.28f, style = stroke)
        drawCircle(color, radius = w * 0.08f)
        return
    }
    val angle = when (type) {
        ManeuverType.DEPART -> 0.0
        ManeuverType.U_TURN -> if (turnDegrees >= 0) 180.0 else -180.0
        else -> turnDegrees.coerceIn(-160.0, 160.0)
    }
    val radians = Math.toRadians(angle)
    val bendX = w * 0.5f
    val bendY = h * 0.55f
    val armLength = w * 0.36f
    // In RTL the screen is not mirrored for geometry: right is still +x.
    val tipX = bendX + (armLength * kotlin.math.sin(radians)).toFloat()
    val tipY = bendY - (armLength * kotlin.math.cos(radians)).toFloat()
    val path = Path().apply {
        moveTo(bendX, h * 0.95f)
        lineTo(bendX, bendY)
        lineTo(tipX, tipY)
    }
    drawPath(path, color, style = stroke)
    val head = w * 0.16f
    val back = radians + Math.PI
    val left = back - Math.toRadians(35.0)
    val right = back + Math.toRadians(35.0)
    val headPath = Path().apply {
        moveTo(tipX + (head * kotlin.math.sin(left)).toFloat(), tipY - (head * kotlin.math.cos(left)).toFloat())
        lineTo(tipX, tipY)
        lineTo(tipX + (head * kotlin.math.sin(right)).toFloat(), tipY - (head * kotlin.math.cos(right)).toFloat())
    }
    drawPath(headPath, color, style = stroke)
}
