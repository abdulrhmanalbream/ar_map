package com.sarab.vision.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.sarab.vision.core.GuidanceMode
import com.sarab.vision.core.formatDistanceAr

private val Panel = Color(0xE6121A24)
private val Accent = Color(0xFF4FC3F7)
private val Amber = Color(0xFFFFB300)
private val Good = Color(0xFF66D9AF)
private val Muted = Color(0xFF9FB3C8)

/**
 * Controls for trying the whole system away from the campus.
 *
 * Without this there is no way to judge the arrow, the distant markers or
 * the ambiguity prompt except by physically standing on site, which the user
 * cannot currently do. Walking is simulated so the distance counts down and
 * the guidance moves through its modes while sitting still.
 */
@Composable
fun DemoControls(
    active: Boolean,
    guidance: GuidanceMode,
    targetName: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onWalk: (Double) -> Unit,
    onTeleportToTarget: () -> Unit,
    onTurn: (Double) -> Unit,
    useRealHeading: Boolean = false,
    onToggleRealHeading: () -> Unit = {},
    onPlaceMarkerHere: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(14.dp)
            .background(Panel, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(if (active) Good else Muted, CircleShape)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "الوضع التجريبي",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (active) "معالم وهمية حول موقعك الحالي"
                    else "جرّب النظام من أي مكان بدون الذهاب للجامعة",
                    color = Muted,
                    fontSize = 12.sp
                )
            }
            Text(
                text = if (active) "إيقاف" else "تشغيل",
                color = if (active) Color(0xFFEF5350) else Accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .background(Color(0xFF1B2836), RoundedCornerShape(10.dp))
                    .clickable { if (active) onStop() else onStart() }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }

        if (!active) return@Column

        Spacer(Modifier.height(14.dp))

        // Current state, so it is obvious which mode the app is in and why.
        val stateLabel = when (guidance) {
            is GuidanceMode.NoFix -> "بانتظار الموقع…"
            is GuidanceMode.Compass ->
                "بوصلة · ${formatDistanceAr(guidance.distanceMeters)}"
            is GuidanceMode.ArApproach ->
                "اقتراب AR · ${formatDistanceAr(guidance.distanceMeters)}"
            is GuidanceMode.Ambiguous ->
                "مبانٍ متقاربة — سؤال التأكيد"
            is GuidanceMode.Arrived -> "وصلت"
        }
        val stateColour = when (guidance) {
            is GuidanceMode.Ambiguous -> Amber
            is GuidanceMode.Arrived -> Good
            else -> Accent
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF16202C), RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(targetName ?: "لم تختر وجهة", color = Color.White, fontSize = 14.sp)
                Text(stateLabel, color = stateColour, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(14.dp))

        Text("المشي (محاكاة)", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DemoButton("+5 م", Modifier.weight(1f)) { onWalk(5.0) }
            DemoButton("+25 م", Modifier.weight(1f)) { onWalk(25.0) }
            DemoButton("+100 م", Modifier.weight(1f)) { onWalk(100.0) }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DemoButton("للخلف 25 م", Modifier.weight(1f)) { onWalk(-25.0) }
            DemoButton("انتقل للوجهة", Modifier.weight(1f), Amber) { onTeleportToTarget() }
        }

        Spacer(Modifier.height(14.dp))

        Text("الاتجاه (محاكاة)", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DemoButton("↺ 45°", Modifier.weight(1f)) { onTurn(-45.0) }
            DemoButton("↻ 45°", Modifier.weight(1f)) { onTurn(45.0) }
            DemoButton("استدر 180°", Modifier.weight(1f)) { onTurn(180.0) }
        }

        Spacer(Modifier.height(14.dp))

        // Real-compass mode. Simulated turning proves the arrow maths, but
        // only the real magnetometer proves the phone knows which way it is
        // actually pointing -- which is the thing the user asked about.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF16202C), RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("البوصلة الحقيقية", color = Color.White, fontSize = 14.sp)
                Text(
                    if (useRealHeading)
                        "لِف الجوال بيدك وشوف السهم يتحرك"
                    else
                        "الاتجاه محاكى بالأزرار أعلاه",
                    color = if (useRealHeading) Good else Muted,
                    fontSize = 11.sp
                )
            }
            Text(
                text = if (useRealHeading) "مفعّلة" else "تفعيل",
                color = if (useRealHeading) Good else Accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .background(Color(0xFF243244), RoundedCornerShape(10.dp))
                    .clickable { onToggleRealHeading() }
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            )
        }

        Spacer(Modifier.height(10.dp))

        // Marker-on-a-screen test: put the printed marker on a laptop and
        // have the app guide you to it. It closes the loop between GPS
        // guidance and the close-range visual marker.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x33FFB300), RoundedCornerShape(12.dp))
                .clickable(onClick = onPlaceMarkerHere)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("ضع وجهة أمامي (5 م)", color = Amber, fontSize = 14.sp)
                Text(
                    "افتح صورة العلامة على اللاب وضعها أمامك، ثم اضغط هنا",
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "ملاحظة: عند تشغيل الوضع التجريبي تُستخدم مواقع وهمية بدل GPS، " +
                "فلا تتأثر بياناتك الحقيقية.",
            color = Muted,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    }
}

@Composable
private fun DemoButton(
    label: String,
    modifier: Modifier = Modifier,
    colour: Color = Accent,
    onClick: () -> Unit
) {
    Text(
        text = label,
        color = colour,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        modifier = modifier
            .background(Color(0xFF243244), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp)
    )
}
