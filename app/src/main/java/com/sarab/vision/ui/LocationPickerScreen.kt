package com.sarab.vision.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sarab.vision.core.GpsFix
import com.sarab.vision.core.Landmark
import com.sarab.vision.core.LatLng
import com.sarab.vision.core.MapsLinkResult
import com.sarab.vision.core.PathNetwork
import com.sarab.vision.core.parseGoogleMapsLink
import com.sarab.vision.map.CampusMapView
import com.sarab.vision.map.MapStyles

private val Panel = Color(0xE6121A24)
private val Accent = Color(0xFF4FC3F7)
private val Good = Color(0xFF66D9AF)
private val Bad = Color(0xFFEF5350)
private val Muted = Color(0xFF9FB3C8)

/**
 * Picks a coordinate without needing a GPS fix at the spot.
 *
 * ## Why this is necessary
 *
 * Survey mode originally required a live GPS fix better than 20m. Indoors
 * that fix never arrives, so the whole flow sat on "waiting for GPS" and
 * nothing could be added at all -- the app was unusable anywhere except
 * standing outside the building being recorded.
 *
 * Two ways in, both offline-friendly:
 *  - drag the map under a fixed crosshair, which is the standard gesture and
 *    keeps the target visible rather than hidden under a finger
 *  - paste a Google Maps link, which is how places get added from a laptop
 *
 * A manually placed point records no accuracy figure, and the caller is
 * expected to keep that distinction: a hand-placed coordinate is only as
 * good as the imagery it was placed on.
 */
@Composable
fun LocationPickerScreen(
    landmarks: List<Landmark>,
    pathNetwork: PathNetwork,
    userFix: GpsFix?,
    initial: LatLng?,
    onConfirm: (LatLng) -> Unit,
    onClose: () -> Unit
) {
    // The map reports its centre as it moves; the crosshair is fixed on
    // screen, so the centre IS the selection.
    var centre by remember {
        mutableStateOf(initial ?: userFix?.position ?: LatLng(24.4672, 39.6111))
    }
    var linkText by remember { mutableStateOf("") }
    var linkError by remember { mutableStateOf<String?>(null) }
    var focusOn by remember { mutableStateOf(initial ?: userFix?.position) }
    var focusNonce by remember { mutableStateOf(0) }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B1520))) {
        CampusMapView(
            landmarks = landmarks,
            route = null,
            pathNetwork = pathNetwork,
            userPosition = userFix?.position,
            styleKind = MapStyles.Kind.SATELLITE,
            focusOn = focusOn,
            focusNonce = focusNonce,
            modifier = Modifier.fillMaxSize(),
            onCentreChanged = { centre = it }
        )

        // Fixed crosshair. Dragging the map under it beats tapping, because a
        // fingertip covers exactly the point being aimed at.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            drawCircle(Accent.copy(alpha = 0.18f), radius = 46f, center = Offset(cx, cy))
            drawCircle(Accent, radius = 9f, center = Offset(cx, cy))
            drawLine(Accent, Offset(cx - 34f, cy), Offset(cx - 16f, cy), strokeWidth = 4f)
            drawLine(Accent, Offset(cx + 16f, cy), Offset(cx + 34f, cy), strokeWidth = 4f)
            drawLine(Accent, Offset(cx, cy - 34f), Offset(cx, cy - 16f), strokeWidth = 4f)
            drawLine(Accent, Offset(cx, cy + 16f), Offset(cx, cy + 34f), strokeWidth = 4f)
        }

        // ---- Header --------------------------------------------------------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(14.dp)
        ) {
            Text(
                "إلغاء",
                color = Accent,
                fontSize = 13.sp,
                modifier = Modifier
                    .background(Panel, RoundedCornerShape(12.dp))
                    .clickable(onClick = onClose)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "حرّك الخريطة لتحديد الموقع",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .background(Panel, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
            userFix?.position?.takeIf { it.isValid }?.let { me ->
                Spacer(Modifier.width(8.dp))
                Text(
                    "موقعي",
                    color = Good,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .background(Panel, RoundedCornerShape(12.dp))
                        .clickable {
                            focusOn = LatLng(me.latitude, me.longitude)
                            focusNonce++
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
        }

        // ---- Controls ------------------------------------------------------
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(14.dp)
                .background(Panel, RoundedCornerShape(18.dp))
                .padding(16.dp)
        ) {
            Text(
                "%.6f, %.6f".format(centre.latitude, centre.longitude),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = linkText,
                onValueChange = {
                    linkText = it
                    linkError = null
                },
                label = { Text("أو الصق رابط خرائط جوجل", fontSize = 12.sp) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF16202C),
                    unfocusedContainerColor = Color(0xFF16202C),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedLabelColor = Accent,
                    unfocusedLabelColor = Muted,
                    focusedIndicatorColor = Accent,
                    unfocusedIndicatorColor = Color(0xFF243244),
                    cursorColor = Accent
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            linkError?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Bad, fontSize = 11.sp)
            }

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "استخدم الرابط",
                    color = if (linkText.isBlank()) Muted.copy(alpha = 0.4f) else Accent,
                    fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF1B2836), RoundedCornerShape(12.dp))
                        .then(
                            if (linkText.isBlank()) Modifier
                            else Modifier.clickable {
                                when (val result = parseGoogleMapsLink(linkText)) {
                                    is MapsLinkResult.Found -> {
                                        centre = result.position
                                        focusOn = result.position
                                        focusNonce++
                                        linkError = null
                                    }
                                    is MapsLinkResult.NeedsNetwork ->
                                        linkError = "الرابط المختصر يحتاج فتحه في المتصفح أولاً ثم نسخ الرابط الكامل"
                                    is MapsLinkResult.NotFound ->
                                        linkError = result.reason
                                }
                            }
                        )
                        .padding(vertical = 12.dp)
                )
                Text(
                    "تأكيد الموقع",
                    color = Color(0xFF0B1520),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .background(Accent, RoundedCornerShape(12.dp))
                        .clickable { onConfirm(centre) }
                        .padding(vertical = 12.dp)
                )
            }
        }
    }
}
