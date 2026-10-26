package com.sarab.vision.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Transient hint pill shown at the top of the screen while scanning. */
@Composable
fun ScanHint(text: String, visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 56.dp, start = 24.dp, end = 24.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Text(
                text = text,
                color = Color.White,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(Hedayah.HudRaised.copy(alpha = 0xCC / 255f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }
    }
}

/**
 * Full-screen blocking message with an optional action.
 *
 * Used for the camera-permission rationale and the ARCore-unsupported case,
 * both of which leave nothing meaningful to show behind them.
 */
@Composable
fun BlockingMessage(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Hedayah.Ivory),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HedayahMasthead()
            Box(modifier = Modifier.height(32.dp))
            Text(
                text = title,
                color = Hedayah.Ink,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Box(modifier = Modifier.height(12.dp))
            Text(
                text = body,
                color = Hedayah.Muted,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center
            )

            if (actionLabel != null && onAction != null) {
                Box(modifier = Modifier.height(28.dp))
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Hedayah.Forest,
                        contentColor = Hedayah.Ivory
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(actionLabel, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
