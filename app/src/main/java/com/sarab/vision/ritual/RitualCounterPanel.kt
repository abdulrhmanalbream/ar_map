package com.sarab.vision.ritual

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import com.sarab.vision.ui.Hedayah
import com.sarab.vision.ui.HedayahTheme
import com.sarab.vision.ui.HedayahArPanelTheme
import com.sarab.vision.ui.HedayahHologramSurface

/** Compact camera badge; the hosting screen chooses its position outside the route's centre. */
@Composable
fun RitualCounterBadge(
    state: RitualCounterState,
    watch: RitualWatchState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    language: String = "ar",
) {
    val display = ritualCounterDisplay(state, watch)
    val copy = remember(language) { RitualCopy(language) }
    val count = display.count?.let { copy.count(it) } ?: copy.text("noCount")
    val source = copy.text(if (display.editable) "phoneBadge" else "watchBadge")
    val badgeTypography = MaterialTheme.typography
    CompositionLocalProvider(LocalLayoutDirection provides if (copy.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
    HedayahArPanelTheme {
    HedayahHologramSurface(modifier = modifier, shape = MaterialTheme.shapes.medium) {
        TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)
            .semantics { contentDescription = "${copy.name(display.mode)} · $count · $source. ${copy.text("open")}" }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${copy.name(display.mode)} · $count", color = Hedayah.ArAccent,
                    style = badgeTypography.labelLarge.copy(shadow = MaterialTheme.typography.labelLarge.shadow), textAlign = TextAlign.Center)
                Text(source, color = Hedayah.ArMuted,
                    style = badgeTypography.labelSmall.copy(shadow = MaterialTheme.typography.labelSmall.shadow))
            }
        }
    }
    }
    }
}

/** A scrollable panel: root owns its modal placement, outside camera projection and sensor logic. */
@Composable
fun RitualCounterPanel(
    state: RitualCounterState,
    watch: RitualWatchState,
    onModeChange: (String) -> Unit,
    onSourceChange: (RitualCounterSource) -> Unit,
    onIncrement: () -> Unit,
    onUndo: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    language: String = "ar",
) {
    val display = ritualCounterDisplay(state, watch)
    val copy = remember(language) { RitualCopy(language) }
    val density = LocalDensity.current
    val safeHeight = with(density) {
        LocalConfiguration.current.screenHeightDp.dp - WindowInsets.safeDrawing.getTop(this).toDp() -
            WindowInsets.safeDrawing.getBottom(this).toDp() - 24.dp
    }.coerceIn(120.dp, 560.dp)
    var resetSession by remember { mutableStateOf<Pair<String, String>?>(null) }
    var lastIncrementAt by remember { mutableLongStateOf(0L) }
    HedayahTheme(camera = true) {
        CompositionLocalProvider(LocalLayoutDirection provides if (copy.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Surface(modifier = modifier.widthIn(max = 460.dp).fillMaxWidth(), color = Hedayah.Hud,
            shape = MaterialTheme.shapes.large) {
            Column(Modifier.heightIn(max = safeHeight).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(copy.text("title"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(copy.text("close")) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("tawaf", "sai").forEach { mode ->
                        FilterChip(selected = state.selectedMode == mode, onClick = { onModeChange(mode) },
                            label = { Text(copy.name(mode)) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                    }
                }
                Text(display.count?.let { copy.count(it) } ?: "—",
                    color = Hedayah.Ivory, style = MaterialTheme.typography.displaySmall,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Text(copy.note(state, watch), color = Hedayah.HudMuted, style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                if (display.editable) {
                    Button(onClick = {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastIncrementAt >= 500) { lastIncrementAt = now; onIncrement() }
                    }, enabled = display.count != null && display.count < 7,
                        colors = ButtonDefaults.buttonColors(containerColor = Hedayah.HudGold, contentColor = Hedayah.Forest),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(copy.text(if (display.count == 7) "recorded" else "increment"))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onUndo, enabled = (display.count ?: 0) > 0,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(copy.text("undo")) }
                        OutlinedButton(onClick = { resetSession = state.selectedMode to state.localCounter.sessionId },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(copy.text("new")) }
                    }
                }
                Text(copy.text("source"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.source == RitualCounterSource.PHONE,
                        onClick = { onSourceChange(RitualCounterSource.PHONE) }, label = { Text(copy.text("phone")) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                    FilterChip(selected = state.source == RitualCounterSource.WATCH,
                        onClick = { onSourceChange(RitualCounterSource.WATCH) }, label = { Text(copy.text("watch")) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                }
                Text(copy.text("independent"),
                    color = Hedayah.HudMuted, style = MaterialTheme.typography.bodySmall)
                Text(copy.text(if (state.selectedMode == "sai") "manualSai" else "manualTawaf"),
                    color = Hedayah.HudMuted, style = MaterialTheme.typography.bodySmall)
                state.storageError?.let { Text(copy.storageError(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
        resetSession?.let { requested ->
            val stillSelected = state.source == RitualCounterSource.PHONE && state.selectedMode == requested.first &&
                state.localCounter.sessionId == requested.second
            AlertDialog(onDismissRequest = { resetSession = null },
                title = { Text(copy.text("resetTitle").replace("{ritual}", copy.name(requested.first))) },
                text = { Text(copy.text("resetBody")) },
                confirmButton = { TextButton(enabled = stillSelected, onClick = { resetSession = null; onReset() }) { Text(copy.text("reset")) } },
                dismissButton = { TextButton(onClick = { resetSession = null }) { Text(copy.text("cancel")) } })
        }
        }
    }
}
