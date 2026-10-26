package com.sarab.vision.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** A quiet, one-shot brand handoff; it never gates camera or sensor readiness. */
@Composable
fun IgnitionSplash(onFinished: () -> Unit) {
    val exitAlpha = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        delay(2200)
        exitAlpha.animateTo(0f, tween(350))
        onFinished()
    }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = exitAlpha.value }
        .background(Hedayah.Forest).safeDrawingPadding().padding(32.dp)) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            HedayahLogo(Modifier.width(200.dp).height(124.dp))
            Box(Modifier.width(40.dp).height(2.dp).background(Hedayah.Gold))
            Text("المطوف الذكي", color = Hedayah.Paper, style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center)
            Text("رفيقك في الطريق", color = Hedayah.HudMuted, style = MaterialTheme.typography.bodyLarge)
        }
        Text("هداية ثون · فريق سعي", color = Hedayah.HudGold, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
    }
}
