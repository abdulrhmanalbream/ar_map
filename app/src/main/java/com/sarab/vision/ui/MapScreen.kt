package com.sarab.vision.ui

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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import com.sarab.vision.core.distanceMeters
import com.sarab.vision.map.CampusMapView
import com.sarab.vision.map.MapStyles

private val Panel = Color(0xE6121A24)
private val Accent = Color(0xFF4FC3F7)
private val Muted = Color(0xFF9FB3C8)

/**
 * How near a tap must land to a pin to count as selecting it, in metres.
 *
 * Generous on purpose: a fingertip covers a wide area on a phone screen, and
 * a tap that lands "close enough" to obviously mean one building should
 * select it rather than doing nothing.
 */
private const val TAP_SELECT_RADIUS_M = 45.0

/**
 * The campus map screen.
 *
 * Built to the conventions of every mapping app people already use: tap a
 * place to select it, see the route drawn as a cased blue line, read the time
 * and distance in a sheet at the bottom, switch travel mode there. Matching
 * those conventions is not imitation for its own sake -- it means nobody has
 * to be taught how to use this.
 */
@Composable
fun MapScreen(
    landmarks: List<Landmark>,
    route: Route?,
    pathNetwork: PathNetwork,
    userFix: GpsFix?,
    headingDegrees: Double?,
    travelMode: TravelMode,
    selectedId: String?,
    selectedName: String?,
    onSelect: (Landmark?) -> Unit,
    onModeChange: (TravelMode) -> Unit,
    onClose: () -> Unit,
    onStartAr: () -> Unit
) {
    var styleKind by remember { mutableStateOf(MapStyles.Kind.SATELLITE) }
    var focusOn by remember { mutableStateOf<LatLng?>(null) }
    var focusNonce by remember { mutableStateOf(0) }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B1520))) {
        CampusMapView(
            landmarks = landmarks,
            route = route,
            pathNetwork = pathNetwork,
            userPosition = userFix?.position,
            styleKind = styleKind,
            focusOn = focusOn,
            modifier = Modifier.fillMaxSize(),
            userAccuracyM = userFix?.accuracyMeters,
            userHeadingDeg = headingDegrees,
            selectedId = selectedId,
            focusNonce = focusNonce,
            onMapTap = { tapped ->
                // Nearest pin within reach, or clear the selection. Tapping
                // empty ground meaning "never mind" is the behaviour every
                // map has, and expecting a dedicated button instead would be
                // one more thing to explain.
                val nearest = landmarks
                    .map { it to distanceMeters(tapped, it.position) }
                    .filter { it.second <= TAP_SELECT_RADIUS_M }
                    .minByOrNull { it.second }
                    ?.first
                onSelect(nearest)
            }
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
            // best from above, and it now carries road and place labels so it
            // is readable rather than merely pretty.
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
        }

        // ---- Recentre ------------------------------------------------------
        userFix?.position?.takeIf { it.isValid }?.let { me ->
            Text(
                "◎",
                color = Color.White,
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 14.dp)
                    .size(46.dp)
                    .background(Panel, CircleShape)
                    .clickable {
                        // Bump the nonce so pressing twice recentres twice,
                        // even though the coordinate is identical.
                        focusOn = LatLng(me.latitude, me.longitude)
                        focusNonce++
                    }
                    .padding(top = 11.dp)
            )
        }

        // ---- Route sheet ---------------------------------------------------
        RouteCard(
            route = route,
            destinationName = selectedName,
            travelMode = travelMode,
            onModeChange = onModeChange,
            onStartAr = onStartAr,
            onClear = { onSelect(null) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
        )
    }
}
