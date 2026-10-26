package com.sarab.vision.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sarab.vision.R

/** Hedayathon's printed identity: forest, ivory, and restrained gold. */
object Hedayah {
    val Forest = Color(0xFF133F35)
    val Ink = Color(0xFF173F35)
    val Ivory = Color(0xFFF5F3EB)
    val Paper = Color(0xFFFFFEF9)
    val Soft = Color(0xFFE9EEE3)
    val Gold = Color(0xFFB49A5B)
    val DarkGold = Color(0xFF806B38)
    val Muted = Color(0xFF5B6E61)
    val Outline = Color(0xFFB8C4B7)
    val Error = Color(0xFF9C3D2C)
    val Good = Color(0xFF26664C)
    val Hud = Color(0xFF102C25)
    val HudPanel = Color(0xE6102C25)
    val HudRaised = Color(0xFF244D40)
    val HudMuted = Color(0xFFD1DCCB)
    val HudGold = Color(0xFFE3CA87)
    // The camera remains visible through AR panels; only text and edge highlights are solid.
    val ArPanel = Color(0x44102C25)
    val ArPanelRaised = Color(0x80244D40)
    val ArText = Color(0xFFF3FFF9)
    val ArMuted = Color(0xFFD8F4E5)
    val ArAccent = Color(0xFFB7FFE0)
}

private val ArabicFont = FontFamily(
    Font(R.font.ibm_plex_sans_arabic_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_arabic_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_arabic_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans_arabic_bold, FontWeight.Bold),
)

private fun type(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = ArabicFont, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight)

private val HedayahTypography = Typography(
    displayLarge = type(48, 60, FontWeight.Bold), displayMedium = type(40, 52, FontWeight.Bold),
    displaySmall = type(34, 44, FontWeight.Bold), headlineLarge = type(32, 42, FontWeight.Bold),
    headlineMedium = type(28, 38, FontWeight.Bold), headlineSmall = type(24, 34, FontWeight.SemiBold),
    titleLarge = type(22, 30, FontWeight.SemiBold), titleMedium = type(18, 28, FontWeight.SemiBold),
    titleSmall = type(16, 24, FontWeight.SemiBold), bodyLarge = type(17, 28),
    bodyMedium = type(15, 24), bodySmall = type(13, 21), labelLarge = type(15, 22, FontWeight.Medium),
    labelMedium = type(13, 20, FontWeight.Medium), labelSmall = type(11, 17, FontWeight.Medium),
)

/** Holographic camera overlays retain the brand's green while letting the scene show through. */
@Composable
internal fun HedayahArPanelTheme(content: @Composable () -> Unit) {
    val shadow = with(LocalDensity.current) {
        Shadow(Color(0xFF04140D), Offset(0f, 1.dp.toPx()), 2.dp.toPx())
    }
    fun hologramType(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
        type(size, line, weight).copy(shadow = shadow)
    val typography = HedayahTypography.copy(
        titleLarge = hologramType(24, 34, FontWeight.SemiBold),
        titleMedium = hologramType(20, 30, FontWeight.SemiBold),
        titleSmall = hologramType(18, 28, FontWeight.SemiBold),
        bodyLarge = hologramType(19, 30), bodyMedium = hologramType(17, 27), bodySmall = hologramType(15, 24),
        labelLarge = hologramType(17, 26, FontWeight.Medium),
        labelMedium = hologramType(15, 24, FontWeight.Medium), labelSmall = hologramType(14, 22, FontWeight.Medium),
    )
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Hedayah.ArAccent, onPrimary = Hedayah.Forest,
        primaryContainer = Hedayah.ArPanelRaised, onPrimaryContainer = Hedayah.ArText,
        secondary = Hedayah.HudGold, onSecondary = Hedayah.Forest,
        secondaryContainer = Hedayah.ArPanelRaised, onSecondaryContainer = Hedayah.ArText,
        background = Hedayah.ArPanel, onBackground = Hedayah.ArText,
        surface = Hedayah.ArPanel, onSurface = Hedayah.ArText,
        surfaceVariant = Hedayah.ArPanelRaised, onSurfaceVariant = Hedayah.ArMuted,
        outline = Hedayah.ArAccent.copy(alpha = .7f), outlineVariant = Hedayah.ArAccent.copy(alpha = .3f),
        error = Color(0xFFFFC4B5),
    ), typography = typography, shapes = MaterialTheme.shapes, content = content)
}

@Composable
fun HedayahTheme(camera: Boolean = false, content: @Composable () -> Unit) {
    val scheme = if (camera) darkColorScheme(
        primary = Hedayah.HudGold, onPrimary = Hedayah.Forest,
        primaryContainer = Hedayah.HudRaised, onPrimaryContainer = Hedayah.Ivory,
        secondary = Hedayah.HudGold, onSecondary = Hedayah.Forest,
        secondaryContainer = Hedayah.HudRaised, onSecondaryContainer = Hedayah.Ivory,
        tertiary = Hedayah.HudMuted, background = Hedayah.Hud, onBackground = Hedayah.Ivory,
        surface = Hedayah.Hud, onSurface = Hedayah.Ivory,
        surfaceVariant = Hedayah.HudRaised, onSurfaceVariant = Hedayah.HudMuted,
        outline = Hedayah.Muted, error = Color(0xFFFFB4A4),
    ) else lightColorScheme(
        primary = Hedayah.Forest, onPrimary = Hedayah.Paper,
        primaryContainer = Hedayah.Soft, onPrimaryContainer = Hedayah.Ink,
        secondary = Hedayah.DarkGold, onSecondary = Hedayah.Paper,
        secondaryContainer = Hedayah.Soft, onSecondaryContainer = Hedayah.Ink,
        tertiary = Hedayah.DarkGold, background = Hedayah.Ivory, onBackground = Hedayah.Ink,
        surface = Hedayah.Paper, onSurface = Hedayah.Ink,
        surfaceVariant = Hedayah.Soft, onSurfaceVariant = Hedayah.Muted,
        outline = Hedayah.Outline, error = Hedayah.Error,
    )
    MaterialTheme(colorScheme = scheme, typography = HedayahTypography,
        shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(24.dp)), content = content)
}

/** Original supplied logo, with no recoloring or reconstruction. */
@Composable
fun HedayahLogo(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.hedayathon_logo), contentDescription = "شعار هداية ثون",
        contentScale = ContentScale.Fit, modifier = modifier.width(80.dp).height(50.dp))
}

@Composable
fun HedayahMasthead(modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(modifier.fillMaxWidth().background(Hedayah.Forest, RoundedCornerShape(16.dp))
        .padding(horizontal = 18.dp, vertical = if (compact) 12.dp else 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("المطوف الذكي", color = Hedayah.Paper,
                style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall)
            Text("هداية ثون · فريق سعي", color = Hedayah.HudGold, style = MaterialTheme.typography.labelMedium)
        }
        HedayahLogo()
    }
}
