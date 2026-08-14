package com.sarab.vision.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.sarab.vision.core.GpsFix
import com.sarab.vision.core.Landmark
import com.sarab.vision.core.LatLng
import com.sarab.vision.core.PathNetwork
import com.sarab.vision.core.Route
import com.sarab.vision.core.TravelMode
import com.sarab.vision.core.formatDistanceAr
import com.sarab.vision.core.travelMinutes
import com.sarab.vision.map.CampusMapView
import com.sarab.vision.map.MapStyles

private val Panel = Color(0xE6121A24)
private val Accent = Color(0xFF4FC3F7)
private val Amber = Color(0xFFFFB300)
private val Muted = Color(0xFF9FB3C8)

/**
 * The campus map screen.
 *
 * Replaces the hand-drawn Canvas map. That version could never look like a
 * real map because it was not one -- no imagery, no proper projection, no
 * gestures. This is MapLibre with satellite imagery, matching the engine and
 * quality of the reference web map.
 */
@Composable
fun MapScreen(
    landmarks: List<Landmark>,
    route: Route?,
    pathNetwork: PathNetwork,
    userFix: GpsFix?,
    travelMode: TravelMode,
    selectedName: String?,
    onModeChange: (TravelMode) -> Unit,
    onClose: () -> Unit,
    onStartAr: () -> Unit
) {
    var styleKind by remember { mutableStateOf(MapStyles.Kind.SATELLITE) }
    var focusOn by remember { mutableStateOf<LatLng?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B1520))) {
        CampusMapView(
            landmarks = landmarks,
            route = route,
            pathNetwork = pathNetwork,
            userPosition = userFix?.position,
            styleKind = styleKind,
            focusOn = focusOn,
            modifier = Modifier.fillMaxSize()
        )

        // ---- Top controls -------------------------------------------------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(14.dp)
        ) {
            Text(
                "رجوع",
                color = Accent,
                fontSize = 13.sp,
                modifier = Modifier
                    .background(Panel, RoundedCornerShape(12.dp))
                    .clickable(onClick = onClose)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            )
            Spacer(Modifier.width(8.dp))

            // Basemap switch: satellite is the default because a campus reads
            // best from above, but imagery can be too dark to label-read.
            Row(
                modifier = Modifier
                    .background(Panel, RoundedCornerShape(12.dp))
                    .padding(3.dp)
            ) {
                MapStyles.Kind.entries.forEach { kind ->
                    val selected = kind == styleKind
                    Text(
                        text = kind.labelAr,
                        color = if (selected) Color(0xFF0B1520) else Muted,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .background(
                                if (selected) Accent else Color.Transparent,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { styleKind = kind }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            userFix?.position?.takeIf { it.isValid }?.let { me ->
                Text(
                    "موقعي",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .background(Panel, RoundedCornerShape(12.dp))
                        .clickable {
                            // Nudge the value so the same position still
                            // triggers a recentre.
                            focusOn = LatLng(me.latitude, me.longitude)
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
        }

        // ---- Bottom summary -----------------------------------------------
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(14.dp)
                .background(Panel, RoundedCornerShape(18.dp))
                .padding(16.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                TravelMode.entries.forEach { mode ->
                    val selected = mode == travelMode
                    Text(
                        text = mode.labelAr,
                        color = if (selected) Color(0xFF0B1520) else Muted,
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

            if (route != null && route.points.size >= 2) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(10.dp).background(Amber, CircleShape)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            selectedName ?: "المسار",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "${formatDistanceAr(route.distanceMeters)} · " +
                                "${travelMinutes(route.distanceMeters, travelMode)} دقيقة",
                            color = Muted,
                            fontSize = 12.sp
                        )
                    }
                    Text(
                        "ابدأ بالكاميرا",
                        color = Color(0xFF0B1520),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .background(Accent, RoundedCornerShape(12.dp))
                            .clickable(onClick = onStartAr)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            } else {
                Spacer(Modifier.height(10.dp))
                Text(
                    if (landmarks.isEmpty()) "لا توجد معالم بعد"
                    else "اختر وجهة لعرض المسار",
                    color = Muted,
                    fontSize = 12.sp
                )
            }
        }
    }
}
