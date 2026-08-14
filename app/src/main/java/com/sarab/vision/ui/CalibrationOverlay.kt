package com.sarab.vision.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sarab.vision.core.CalibrationState
import com.sarab.vision.core.CalibrationStep

private val Ink = Color(0xFF0B1520)
private val Accent = Color(0xFF4FC3F7)
private val Muted = Color(0xFF9FB3C8)

/**
 * The guided start-up overlay.
 *
 * Covers the camera on purpose. Until ARCore has tracking there is nothing
 * meaningful to show through it, and a live but useless camera feed invites
 * the user to stand still -- which is precisely what prevents tracking from
 * ever starting.
 */
@Composable
fun CalibrationOverlay(
    state: CalibrationState,
    onSkip: () -> Unit
) {
    if (state.step == CalibrationStep.DONE) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.copy(alpha = 0.88f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            when (state.step) {
                CalibrationStep.WAVE -> WaveAnimation(state.progress)
                CalibrationStep.RAISE -> RaiseAnimation()
                CalibrationStep.DONE -> Unit
            }

            Spacer(Modifier.height(30.dp))

            Text(
                state.titleAr,
                color = Color.White,
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            Text(
                state.bodyAr,
                color = Muted,
                fontSize = 15.sp,
                lineHeight = 23.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(14.dp))

            // Explains WHY, so the step reads as necessary rather than as the
            // app being slow.
            Text(
                when (state.step) {
                    CalibrationStep.WAVE ->
                        "هذه الحركة تساعد الكاميرا على فهم أبعاد المكان وتضبط البوصلة."
                    else ->
                        "الآن يمكن رسم المسار على الأرض أمامك."
                },
                color = Muted.copy(alpha = 0.7f),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(28.dp))

            Text(
                "تخطّي",
                color = Muted,
                fontSize = 13.sp,
                modifier = Modifier
                    .background(Color(0xFF1B2836), RoundedCornerShape(12.dp))
                    .clickable(onClick = onSkip)
                    .padding(horizontal = 22.dp, vertical = 11.dp)
            )
        }
    }
}

/**
 * A phone sweeping left and right, with a progress ring.
 *
 * Showing the motion beats describing it: the instruction is understood
 * before the sentence is read.
 */
@Composable
private fun WaveAnimation(progress: Float) {
    val transition = rememberInfiniteTransition(label = "wave")
    val sweep by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sweep"
    )

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(180.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = size.minDimension / 2f - 10f

            drawCircle(
                color = Color.White.copy(alpha = 0.10f),
                radius = radius,
                style = Stroke(width = 6f)
            )
            drawArc(
                color = Accent,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(cx - radius, cy - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = 6f)
            )

            // The phone, sliding along the sweep.
            val phoneW = 46f
            val phoneH = 84f
            val x: Float = cx + sweep * radius * 0.42f
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(x - phoneW / 2f, cy - phoneH / 2f),
                size = Size(phoneW, phoneH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f)
            )
            drawRoundRect(
                color = Ink,
                topLeft = Offset(x - phoneW / 2f + 5f, cy - phoneH / 2f + 7f),
                size = Size(phoneW - 10f, phoneH - 14f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
            )

            // Motion arrows either side.
            listOf(-1f, 1f).forEach { dir ->
                val ax = cx + dir * radius * 0.78f
                drawLine(
                    color = Accent.copy(alpha = 0.75f),
                    start = Offset(ax - dir * 14f, cy - 12f),
                    end = Offset(ax, cy),
                    strokeWidth = 5f
                )
                drawLine(
                    color = Accent.copy(alpha = 0.75f),
                    start = Offset(ax - dir * 14f, cy + 12f),
                    end = Offset(ax, cy),
                    strokeWidth = 5f
                )
            }
        }
    }
}

/** A phone tilting up from flat to upright. */
@Composable
private fun RaiseAnimation() {
    val transition = rememberInfiniteTransition(label = "raise")
    val lift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300),
            repeatMode = RepeatMode.Reverse
        ),
        label = "lift"
    )

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(180.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f

            // Interpolate between a squashed (flat) and tall (upright) phone.
            val w: Float = 46f + 8f * (1f - lift)
            val h: Float = 30f + 66f * lift
            val y: Float = cy + 26f * (1f - lift)

            drawRoundRect(
                color = Color.White,
                topLeft = Offset(cx - w / 2f, y - h / 2f),
                size = Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(9f, 9f)
            )
            drawRoundRect(
                color = Ink,
                topLeft = Offset(cx - w / 2f + 5f, y - h / 2f + 5f),
                size = Size(w - 10f, (h - 10f).coerceAtLeast(4f)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f, 5f)
            )

            // Upward arrow.
            val tipY = cy - 62f
            drawLine(Accent, Offset(cx, cy - 20f), Offset(cx, tipY), strokeWidth = 6f)
            drawLine(Accent, Offset(cx, tipY), Offset(cx - 13f, tipY + 15f), strokeWidth = 6f)
            drawLine(Accent, Offset(cx, tipY), Offset(cx + 13f, tipY + 15f), strokeWidth = 6f)
        }
    }
}
