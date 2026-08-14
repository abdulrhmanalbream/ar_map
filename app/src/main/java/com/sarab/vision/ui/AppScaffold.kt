package com.sarab.vision.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Bg = Color(0xFF0B1520)
private val Bar = Color(0xFF121A24)
private val Accent = Color(0xFF4FC3F7)
private val Muted = Color(0xFF9FB3C8)
private val Hairline = Color(0xFF1E2A38)

/** The main sections of the app, shown in the bottom bar. */
enum class AppTab(val labelAr: String) {
    CAMERA("الكاميرا"),
    MAP("الخريطة"),
    PLACES("الأماكن"),
    ADMIN("الإدارة")
}

/**
 * Standard chrome: a title bar at the top and tabs at the bottom.
 *
 * Added because the app previously had neither. Every screen floated its own
 * ad-hoc buttons, there was no consistent way back, and launching always
 * dumped the user into the camera with no visible way to reach anything
 * else. A fixed bar makes the structure legible and gives every screen the
 * same exit.
 *
 * Insets are honoured explicitly: the app draws edge to edge, so without this
 * the title sits under the status bar and the tabs under the gesture bar.
 */
@Composable
fun AppScaffold(
    title: String,
    subtitle: String? = null,
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    /** Optional action shown at the leading edge of the header. */
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    /** Camera and map fill the whole screen behind transparent chrome. */
    immersive: Boolean = false,
    content: @Composable (Modifier) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().background(Bg)) {

        // Content sits behind the bars in immersive mode so the camera and
        // map are not letterboxed by opaque chrome.
        content(Modifier.fillMaxSize())

        Column(modifier = Modifier.fillMaxSize()) {
            AppHeader(
                title = title,
                subtitle = subtitle,
                actionLabel = actionLabel,
                onAction = onAction,
                transparent = immersive
            )

            Spacer(Modifier.weight(1f))

            AppBottomBar(
                selected = selectedTab,
                onSelected = onTabSelected,
                transparent = immersive
            )
        }
    }
}

@Composable
private fun AppHeader(
    title: String,
    subtitle: String?,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    transparent: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (transparent) Bar.copy(alpha = 0.82f) else Bar)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = Muted, fontSize = 12.sp)
            }
        }
        if (actionLabel != null && onAction != null) {
            Text(
                actionLabel,
                color = Accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .background(Color(0xFF1B2836), RoundedCornerShape(10.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            )
        }
    }
}

@Composable
private fun AppBottomBar(
    selected: AppTab,
    onSelected: (AppTab) -> Unit,
    transparent: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Hairline))
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier
                .fillMaxWidth()
                .background(if (transparent) Bar.copy(alpha = 0.92f) else Bar)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(vertical = 8.dp)
        ) {
            AppTab.entries.forEach { tab ->
                TabItem(tab, tab == selected) { onSelected(tab) }
            }
        }
    }
}

@Composable
private fun TabItem(tab: AppTab, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(if (selected) 8.dp else 6.dp)
                .background(
                    if (selected) Accent else Muted.copy(alpha = 0.35f),
                    CircleShape
                )
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tab.labelAr,
            color = if (selected) Accent else Muted,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    }
}
