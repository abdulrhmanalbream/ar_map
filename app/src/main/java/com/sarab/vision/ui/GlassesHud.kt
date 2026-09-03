package com.sarab.vision.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sarab.vision.core.GuidanceMode
import com.sarab.vision.core.HudMenuView
import com.sarab.vision.core.formatDistanceAr
import com.sarab.vision.core.instructionAr

private val Accent = Color(0xFF4FC3F7)
private val Amber = Color(0xFFFFB300)
private val Muted = Color(0xFF9FB3C8)
private val Green = Color(0xFF7CE38B)

/**
 * The navigation HUD for USB-C display glasses (XREAL One and similar).
 *
 * Everything about this layout follows from one hardware fact: on optical
 * see-through glasses, black pixels emit no light and are invisible. A pure
 * black background turns this screen into a floating instrument cluster over
 * the real world. The translucent panels used on the phone would render as
 * grey fog over everything the wearer sees, so this is bright strokes on
 * black and nothing else.
 *
 * The arrow turns with the PHONE's compass, not the wearer's head -- the
 * glasses expose no sensors to an app, only a display. Held normally in
 * front of the body while walking, the two agree closely enough to steer by.
 */
@Composable
fun GlassesHud(
    guidance: GuidanceMode,
    targetName: String?,
    relativeDegrees: Double?,
    needsCalibration: Boolean,
    menu: HudMenuView? = null
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            when {
                // A menu replaces the navigation readout entirely: the wearer
                // is making a choice, and an arrow still turning underneath
                // it would compete for the same glance.
                menu != null -> HudMenuPanel(menu)

                targetName == null -> Centered {
                    HudText("اختر وجهتك — مرّر بالإيماءة أو بأزرار الصوت", Muted, 30.sp)
                }

                guidance is GuidanceMode.NoFix -> Centered {
                    HudText(targetName, Color.White, 30.sp, FontWeight.SemiBold)
                    Spacer(Modifier.height(14.dp))
                    HudText("جاري تحديد موقعك…", Muted, 26.sp)
                }

                guidance is GuidanceMode.Arrived -> Centered {
                    HudText("وصلت إلى $targetName", Green, 46.sp, FontWeight.Bold)
                }

                guidance is GuidanceMode.Ambiguous -> Centered {
                    // The honest answer, mirrored from the phone: with
                    // buildings closer together than GPS error, asserting an
                    // arrival on the glasses would be confidently wrong.
                    HudText("أنت قريب من عدة مبانٍ", Amber, 38.sp, FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    HudText("دقة GPS لا تكفي — تأكد بالنظر ومن صور الهاتف", Muted, 24.sp)
                }

                else -> {
                    val distance = when (guidance) {
                        is GuidanceMode.Compass -> guidance.distanceMeters
                        is GuidanceMode.ArApproach -> guidance.distanceMeters
                        else -> null
                    }
                    Centered {
                        HudText(targetName, Color.White, 30.sp, FontWeight.SemiBold)
                        Spacer(Modifier.height(16.dp))
                        if (relativeDegrees != null) {
                            HudArrow(relativeDegrees)
                        } else {
                            HudText("جاري تحديد الاتجاه…", Muted, 24.sp)
                        }
                        Spacer(Modifier.height(16.dp))
                        if (distance != null) {
                            HudText(formatDistanceAr(distance), Amber, 68.sp, FontWeight.Bold)
                        }
                        Spacer(Modifier.height(6.dp))
                        HudText(instructionAr(guidance, targetName), Accent, 26.sp)
                    }
                }
            }

            if (needsCalibration) {
                HudText(
                    "البوصلة تحتاج معايرة — حرّك الجوال على شكل ٨",
                    Amber, 20.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 28.dp)
                )
            }
        }
    }
}

/**
 * The eyes-free menu, drawn to be read in a single glance mid-stride.
 *
 * The selected row is marked by a bright border and larger text rather than
 * a filled highlight: a filled bar would be a glowing slab on see-through
 * glasses, and the empty rectangle reads just as unambiguously.
 */
@Composable
private fun HudMenuPanel(menu: HudMenuView) {
    Centered {
        HudText(menu.title, Muted, 26.sp)
        Spacer(Modifier.height(20.dp))

        menu.rows.forEach { row ->
            val shape = RoundedCornerShape(14.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = if (row.selected) {
                    Modifier
                        .border(2.dp, Accent, shape)
                        .padding(horizontal = 26.dp, vertical = 10.dp)
                } else {
                    Modifier.padding(horizontal = 28.dp, vertical = 10.dp)
                }
            ) {
                HudText(
                    row.name,
                    if (row.selected) Color.White else Muted,
                    if (row.selected) 34.sp else 26.sp,
                    if (row.selected) FontWeight.SemiBold else FontWeight.Normal
                )
                row.distanceText?.let {
                    Spacer(Modifier.width(18.dp))
                    HudText(it, if (row.selected) Amber else Muted, if (row.selected) 26.sp else 20.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.height(22.dp))
        HudText(menu.hint, Muted, 18.sp)
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        content()
    }
}

@Composable
private fun HudText(
    text: String,
    color: Color,
    size: TextUnit,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        color = color,
        fontSize = size,
        fontWeight = weight,
        textAlign = TextAlign.Center,
        modifier = modifier
    )
}

@Composable
private fun HudArrow(relativeDegrees: Double) {
    Canvas(modifier = Modifier.size(190.dp)) {
        val w = size.width
        val h = size.height
        rotate(degrees = relativeDegrees.toFloat()) {
            drawPath(
                Path().apply {
                    moveTo(w * 0.50f, h * 0.10f)
                    lineTo(w * 0.84f, h * 0.82f)
                    lineTo(w * 0.50f, h * 0.64f)
                    lineTo(w * 0.16f, h * 0.82f)
                    close()
                },
                Accent
            )
        }
        // A faint static ring gives the arrow a fixed frame to turn inside;
        // without it a rotating shape on empty black reads as drift, not turn.
        drawCircle(
            color = Color.White.copy(alpha = 0.18f),
            radius = w * 0.48f,
            style = Stroke(width = 3f)
        )
    }
}
