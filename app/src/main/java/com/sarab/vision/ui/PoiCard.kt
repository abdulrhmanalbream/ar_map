package com.sarab.vision.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sarab.vision.core.Destination
import com.sarab.vision.core.Fact
import kotlin.math.roundToInt

private val CardBg = Color(0xF2141C26)
private val Accent = Color(0xFF4FC3F7)
private val Amber = Color(0xFFFFB300)
private val Muted = Color(0xFF9FB3C8)
private val Divider = Color(0xFF243244)

/**
 * The destination detail sheet, shown when the user taps the 3D marker.
 *
 * Rendered by Compose over the GLSurfaceView rather than as a textured quad
 * in 3D, so text stays crisp and touch targets behave normally.
 *
 * Height is capped and the body scrolls: on a phone held in AR the card must
 * never grow tall enough to hide the camera view behind it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PoiCard(
    destination: Destination,
    remainingMeters: Float?,
    visible: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 3 },
        exit = fadeOut() + slideOutVertically { it / 3 },
        modifier = modifier
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                // Cap as a FRACTION of the screen, not a fixed dp: a fixed
                // 520dp cap clipped the facilities chips on a tall phone.
                // 78% still leaves the camera view clearly visible above.
                .fillMaxHeight(0.78f)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {

                // ---- Header ----------------------------------------------
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(Amber, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = destination.name.take(1),
                            color = Color(0xFF1B2430),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = destination.name,
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = destination.category,
                            color = Muted,
                            fontSize = 13.sp
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ---- Distance / time / stops summary ---------------------
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF18232F), RoundedCornerShape(14.dp))
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    SummaryStat(
                        value = "${(remainingMeters ?: destination.routeLengthMeters).roundToInt()}",
                        unit = "metres",
                        // Live remaining distance once we are navigating.
                        highlight = remainingMeters != null
                    )
                    StatDivider()
                    SummaryStat(
                        value = "${destination.walkMinutes}",
                        unit = if (destination.walkMinutes == 1) "minute" else "minutes"
                    )
                    StatDivider()
                    SummaryStat(
                        value = "${destination.waypoints.size}",
                        unit = "stops"
                    )
                }

                Spacer(Modifier.height(16.dp))

                // ---- Scrollable body -------------------------------------
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (destination.hours.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(Color(0xFF66D9AF), CircleShape)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = destination.hours,
                                color = Color(0xFF66D9AF),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                    }

                    Text(
                        text = destination.detail,
                        color = Color(0xFFC7D3DF),
                        fontSize = 14.sp,
                        lineHeight = 21.sp
                    )

                    if (destination.facts.isNotEmpty()) {
                        Spacer(Modifier.height(18.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Divider)
                        )
                        Spacer(Modifier.height(16.dp))

                        // Two-column grid, built from chunks so it works
                        // without pulling in a lazy grid inside a scroller.
                        destination.facts.chunked(2).forEach { pair ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                pair.forEach { fact ->
                                    FactCell(fact, Modifier.weight(1f))
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(14.dp))
                        }
                    }

                    if (destination.amenities.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Facilities",
                            color = Muted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(10.dp))
                        // FlowRow wraps chips by their real width instead of
                        // forcing two per line, which left ragged gaps.
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            destination.amenities.forEach { AmenityChip(it) }
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2A3A4D),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Close", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun SummaryStat(value: String, unit: String, highlight: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = if (highlight) Accent else Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(text = unit, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun StatDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(32.dp)
            .background(Divider)
    )
}

@Composable
private fun FactCell(fact: Fact, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(end = 8.dp)) {
        Text(text = fact.label, color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(3.dp))
        Text(
            text = fact.value,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun AmenityChip(text: String) {
    Text(
        text = text,
        color = Accent,
        fontSize = 12.sp,
        modifier = Modifier
            .background(Color(0x334FC3F7), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/** Bottom-anchored container so the card sits above the system nav bar. */
@Composable
fun PoiCardHost(
    destination: Destination,
    remainingMeters: Float?,
    visible: Boolean,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(verticalArrangement = Arrangement.Bottom) {
            PoiCard(
                destination = destination,
                remainingMeters = remainingMeters,
                visible = visible,
                onClose = onClose
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}
