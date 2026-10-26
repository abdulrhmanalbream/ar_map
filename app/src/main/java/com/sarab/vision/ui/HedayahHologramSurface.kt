package com.sarab.vision.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

/** A single translucent layer. No backdrop blur, camera copy, or perpetual animation. */
@Composable
internal fun HedayahHologramSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val material = modifier.clip(shape).drawWithCache {
        val tint = Brush.verticalGradient(listOf(
            Hedayah.Hud.copy(alpha = .34f),
            Hedayah.Hud.copy(alpha = .18f),
            Hedayah.Hud.copy(alpha = .26f),
        ))
        val inset = 7.dp.toPx()
        val arm = minOf(16.dp.toPx(), size.width / 8f, size.height / 8f)
        val highlightWidth = 1.5.dp.toPx()
        onDrawWithContent {
            drawRect(tint)
            drawContent()
            // Short luminous corners give the frame its AR character without covering the view.
            for (column in 0..1) for (row in 0..1) {
                val x = if (column == 1) size.width - inset else inset
                val y = if (row == 1) size.height - inset else inset
                val dx = if (column == 1) -arm else arm
                val dy = if (row == 1) -arm else arm
                drawLine(Hedayah.ArAccent.copy(alpha = .85f), Offset(x, y), Offset(x + dx, y),
                    highlightWidth, StrokeCap.Round)
                drawLine(Hedayah.ArAccent.copy(alpha = .85f), Offset(x, y), Offset(x, y + dy),
                    highlightWidth, StrokeCap.Round)
            }
        }
    }.border(1.dp, Hedayah.ArAccent.copy(alpha = .58f), shape)
    if (onClick == null) {
        Surface(modifier = material, shape = shape, color = Color.Transparent,
            contentColor = Hedayah.ArText, tonalElevation = 0.dp, shadowElevation = 0.dp,
            content = content)
    } else {
        Surface(onClick = onClick, modifier = material, shape = shape, color = Color.Transparent,
            contentColor = Hedayah.ArText, tonalElevation = 0.dp, shadowElevation = 0.dp,
            content = content)
    }
}
