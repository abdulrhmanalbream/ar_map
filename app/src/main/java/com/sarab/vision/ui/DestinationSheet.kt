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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sarab.vision.core.CampusMap
import com.sarab.vision.core.Destination
import kotlin.math.roundToInt

private val SheetBackground = Color(0xFF121A24)
private val Accent = Color(0xFF4FC3F7)
private val Muted = Color(0xFF9FB3C8)

/**
 * "Select Destination" bottom sheet, shown on launch and reopenable from the
 * floating button.
 *
 * Kept as a modal sheet rather than an always-visible panel so it never
 * covers the AR view while the user is actually walking.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DestinationSheet(
    visible: Boolean,
    selectedId: String?,
    onSelect: (Destination) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetBackground,
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 40.dp, height = 4.dp)
                        .background(Muted.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                )
            }
        }
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(
                text = "Select Destination",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Campus routes work fully offline.",
                color = Muted,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(20.dp))

            CampusMap.ALL.forEach { destination ->
                DestinationRow(
                    destination = destination,
                    selected = destination.id == selectedId,
                    onClick = { onSelect(destination) }
                )
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DestinationRow(
    destination: Destination,
    selected: Boolean,
    onClick: () -> Unit
) {
    val border = if (selected) Accent else Color(0xFF223040)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) Color(0xFF16273A) else Color(0xFF18222E),
                RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(border.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = destination.name.take(1),
                color = if (selected) Accent else Muted,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = destination.name,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = destination.category,
                color = Muted,
                fontSize = 13.sp
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${destination.routeLengthMeters.roundToInt()} m",
                color = Accent,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "${destination.waypoints.size} stops",
                color = Muted,
                fontSize = 12.sp
            )
        }
    }
}

/** Floating button that reopens the destination sheet while navigating. */
@Composable
fun ChangeDestinationButton(
    label: String,
    remainingMeters: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .background(Color(0xE6121A24), RoundedCornerShape(28.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(Accent, CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = label,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            if (remainingMeters != null) {
                Text(
                    text = "${remainingMeters.roundToInt()} m remaining · tap to change",
                    color = Muted,
                    fontSize = 12.sp
                )
            } else {
                Text(
                    text = "Tap to change destination",
                    color = Muted,
                    fontSize = 12.sp
                )
            }
        }
    }
}
