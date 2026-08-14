package com.sarab.vision.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sarab.vision.core.TourState

private val Ink = Color(0xFF0B1520)
private val Accent = Color(0xFF4FC3F7)
private val Muted = Color(0xFF9FB3C8)

/**
 * Narration for the scripted demo.
 *
 * Sits at the top, above the compass, and states what is being shown. In a
 * pitch the audience is watching a phone screen on a projector without
 * commentary on every detail, so the app has to caption itself.
 *
 * Deliberately unobtrusive: it explains the current beat and shows overall
 * progress, and never covers the thing being demonstrated.
 */
@Composable
fun TourOverlay(
    tour: TourState,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val step = tour.step
    val progress by animateFloatAsState(
        targetValue = tour.overallProgress,
        animationSpec = tween(400),
        label = "tourProgress"
    )

    AnimatedVisibility(
        visible = tour.running && step != null,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Ink.copy(alpha = 0.96f), Ink.copy(alpha = 0.75f))
                    )
                )
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 18.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        step?.titleAr.orEmpty(),
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        step?.captionAr.orEmpty(),
                        color = Accent,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "إيقاف",
                    color = Muted,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .background(Color(0x33FFFFFF), RoundedCornerShape(10.dp))
                        .clickable(onClick = onStop)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            Spacer(Modifier.height(10.dp))

            // Progress through the whole script, so a presenter can see how
            // much is left without counting beats.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color(0x33FFFFFF), RoundedCornerShape(2.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(Accent, RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

/**
 * The button that starts the demo.
 *
 * Prominent by design: in a pitch it is the first thing that gets pressed,
 * and hunting for it on stage is exactly the kind of small failure that
 * undermines confidence.
 */
@Composable
fun StartTourButton(
    visible: Boolean,
    onStart: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .background(Accent, RoundedCornerShape(24.dp))
                .clickable(onClick = onStart)
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text("▶", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(
                "تشغيل العرض التقديمي",
                color = Ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
