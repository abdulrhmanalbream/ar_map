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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sarab.vision.core.Route
import com.sarab.vision.core.TravelMode
import com.sarab.vision.core.formatDistance
import com.sarab.vision.core.routeSteps

private val Panel = Color(0xF2101822)
private val Ink = Color(0xFF0B1520)
private val Accent = Color(0xFF2E9BFF)
private val Muted = Color(0xFF9FB3C8)

/**
 * The route summary, in the shape every mapping app uses.
 *
 * Time first and largest, then distance, then the mode switcher, then the
 * steps behind a tap. That order is not arbitrary -- it is what people
 * actually read a route for, and matching the convention means nobody has to
 * work out how to use this.
 */
@Composable
fun RouteCard(
    route: Route?,
    destinationName: String?,
    travelMode: TravelMode,
    onModeChange: (TravelMode) -> Unit,
    onStartAr: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    var stepsOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Panel, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .padding(16.dp)
    ) {
        // ---- Travel mode ---------------------------------------------------
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TravelMode.entries.forEach { mode ->
                val selected = mode == travelMode
                Text(
                    text = mode.labelAr,
                    color = if (selected) Ink else Muted,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (selected) Accent else Color(0xFF1B2836),
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { onModeChange(mode) }
                        .padding(vertical = 11.dp)
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        if (destinationName == null) {
            Text(
                "اختر وجهة",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "اضغط على أي مبنى في الخريطة",
                color = Muted,
                fontSize = 13.sp
            )
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                if (route != null && route.points.size >= 2) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "${route.minutes}",
                            color = Color(0xFF66D9AF),
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "دقيقة",
                            color = Color(0xFF66D9AF),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(bottom = 5.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            formatDistance(route.distanceMeters),
                            color = Muted,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(bottom = 5.dp)
                        )
                    }
                } else {
                    Text(
                        "لا يوجد مسار محسوب",
                        color = Color(0xFFFFB300),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    destinationName,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(
                "✕",
                color = Muted,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .size(34.dp)
                    .background(Color(0xFF1B2836), CircleShape)
                    .clickable(onClick = onClear)
                    .padding(top = 7.dp)
            )
        }

        // A straight-line fallback is exactly two points. Saying so beats
        // drawing a line through three buildings and calling it a route.
        if (route != null && route.points.size == 2) {
            Spacer(Modifier.height(8.dp))
            Text(
                "خط مستقيم — لا توجد طرق مرسومة هنا بعد. استورد الطرق من " +
                    "OpenStreetMap أو ارسمها من وضع الأدوات.",
                color = Color(0xFFFFB300),
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "ابدأ بالكاميرا",
                color = Ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .background(Accent, RoundedCornerShape(12.dp))
                    .clickable(onClick = onStartAr)
                    .padding(vertical = 12.dp)
            )

            val steps = remember(route) { route?.let { routeSteps(it) }.orEmpty() }
            if (steps.isNotEmpty()) {
                Text(
                    if (stepsOpen) "إخفاء الخطوات" else "الخطوات (${steps.size})",
                    color = Color.White,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF1B2836), RoundedCornerShape(12.dp))
                        .clickable { stepsOpen = !stepsOpen }
                        .padding(vertical = 12.dp)
                )
            }
        }

        if (stepsOpen) {
            val steps = remember(route) { route?.let { routeSteps(it) }.orEmpty() }
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                steps.forEachIndexed { index, step ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .background(Color(0xFF1B2836), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("${index + 1}", color = Accent, fontSize = 11.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            step.instructionAr,
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            formatDistance(step.distanceMeters),
                            color = Muted,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}
