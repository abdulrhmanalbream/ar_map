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
import com.sarab.vision.core.PathEdge
import com.sarab.vision.core.PathNetwork
import com.sarab.vision.core.PathNode
import com.sarab.vision.core.distanceMeters
import com.sarab.vision.map.CampusMapView
import com.sarab.vision.map.MapStyles

private val Panel = Color(0xE6121A24)
private val Accent = Color(0xFF4FC3F7)
private val Amber = Color(0xFFFFB300)
private val Good = Color(0xFF66D9AF)
private val Danger = Color(0xFFEF5350)
private val Muted = Color(0xFF9FB3C8)

/** Snap radius when tapping near an existing node, in metres. */
private const val SNAP_METERS = 12.0

/**
 * Draws the campus path network on satellite imagery.
 *
 * This is what makes routing real. The engine is built and tested, but until
 * someone traces the paths it has no graph to search, and every route falls
 * back to a straight line through buildings.
 *
 * Drawing is deliberately tap-to-place rather than freehand: junctions must
 * land on shared nodes for the graph to connect, and a dragged line cannot
 * express that. Tapping near an existing node snaps to it, which is what
 * turns a pile of separate lines into a network A* can actually traverse.
 */
@Composable
fun PathEditorScreen(
    network: PathNetwork,
    landmarks: List<Landmark>,
    userFix: GpsFix?,
    onNetworkChange: (PathNetwork) -> Unit,
    onExport: () -> Unit,
    onClose: () -> Unit
) {
    // The node the next tap will connect from. Null means "start a new line".
    var activeNodeId by remember { mutableStateOf<String?>(null) }
    var allowsFoot by remember { mutableStateOf(true) }
    var allowsBike by remember { mutableStateOf(true) }
    var allowsCar by remember { mutableStateOf(false) }
    var hasStairs by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B1520))) {
        CampusMapView(
            landmarks = landmarks,
            route = null,
            pathNetwork = network,
            userPosition = userFix?.position,
            styleKind = MapStyles.Kind.SATELLITE,
            // Start on the user rather than wherever the map defaults to:
            // paths are drawn where you are standing, and hunting for your
            // own campus across a world map is not a reasonable first step.
            focusOn = if (network.isEmpty) userFix?.position else null,
            modifier = Modifier.fillMaxSize(),
            onMapTap = { tapped ->
                // Snap to a nearby node so junctions actually join up. Two
                // lines that merely cross on screen are not connected in the
                // graph, and the route would refuse to turn there.
                val existing = network.nodes
                    .map { it to distanceMeters(tapped, it.position) }
                    .filter { it.second <= SNAP_METERS }
                    .minByOrNull { it.second }
                    ?.first

                val node = existing ?: PathNode(
                    id = "n-${System.currentTimeMillis()}",
                    position = tapped
                )

                val nodes = if (existing == null) network.nodes + node else network.nodes

                val from = activeNodeId
                val edges = if (from != null && from != node.id) {
                    // Do not duplicate a segment that already exists.
                    val duplicate = network.edges.any {
                        (it.fromNodeId == from && it.toNodeId == node.id) ||
                            (it.fromNodeId == node.id && it.toNodeId == from)
                    }
                    if (duplicate) network.edges
                    else network.edges + PathEdge(
                        id = "e-${System.currentTimeMillis()}",
                        fromNodeId = from,
                        toNodeId = node.id,
                        allowsFoot = allowsFoot,
                        allowsBike = allowsBike,
                        allowsCar = allowsCar,
                        hasStairs = hasStairs
                    )
                } else {
                    network.edges
                }

                onNetworkChange(PathNetwork(nodes, edges))
                activeNodeId = node.id
            }
        )

        // ---- Top bar -------------------------------------------------------
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
            Text(
                "${network.nodes.size} نقطة · ${network.edges.size} مسار",
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier
                    .background(Panel, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
            Spacer(Modifier.width(8.dp))
            if (network.edges.isNotEmpty()) {
                Text(
                    "تصدير",
                    color = Good,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .background(Panel, RoundedCornerShape(12.dp))
                        .clickable(onClick = onExport)
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
                if (activeNodeId == null) "اضغط على الخريطة لبدء مسار"
                else "اضغط لإضافة نقطة تالية · أو أنهِ المسار",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "الضغط قرب نقطة موجودة يربط بها، وهكذا تتكوّن التقاطعات.",
                color = Muted,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )

            Spacer(Modifier.height(14.dp))
            Text("من يستطيع استخدام هذا المسار؟", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Toggle("مشاة", allowsFoot, Modifier.weight(1f)) { allowsFoot = !allowsFoot }
                Toggle("دراجات", allowsBike, Modifier.weight(1f)) { allowsBike = !allowsBike }
                Toggle("سيارات", allowsCar, Modifier.weight(1f)) { allowsCar = !allowsCar }
            }
            Spacer(Modifier.height(8.dp))
            Toggle(
                "فيه درج (يمنع الدراجات والسيارات)",
                hasStairs,
                Modifier.fillMaxWidth(),
                activeColour = Amber
            ) { hasStairs = !hasStairs }

            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("إنهاء المسار", Modifier.weight(1f), enabled = activeNodeId != null) {
                    activeNodeId = null
                }
                Action("تراجع", Modifier.weight(1f), enabled = network.edges.isNotEmpty()) {
                    // Remove the last edge, and any node it orphaned.
                    val edges = network.edges.dropLast(1)
                    val used = edges.flatMap { listOf(it.fromNodeId, it.toNodeId) }.toSet()
                    val nodes = network.nodes.filter { it.id in used }
                    onNetworkChange(PathNetwork(nodes, edges))
                    activeNodeId = null
                }
                Action(
                    "مسح الكل",
                    Modifier.weight(1f),
                    enabled = network.nodes.isNotEmpty(),
                    colour = Danger
                ) {
                    onNetworkChange(PathNetwork())
                    activeNodeId = null
                }
            }
        }
    }
}

@Composable
private fun Toggle(
    label: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    activeColour: Color = Accent,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .background(
                if (on) activeColour.copy(alpha = 0.2f) else Color(0xFF1B2836),
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp, horizontal = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(if (on) activeColour else Muted.copy(alpha = 0.4f), CircleShape)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            label,
            color = if (on) activeColour else Muted,
            fontSize = 12.sp,
            fontWeight = if (on) FontWeight.Medium else FontWeight.Normal
        )
    }
}

@Composable
private fun Action(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colour: Color = Accent,
    onClick: () -> Unit
) {
    Text(
        text = label,
        color = if (enabled) colour else Muted.copy(alpha = 0.35f),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        modifier = modifier
            .background(Color(0xFF1B2836), RoundedCornerShape(12.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp)
    )
}
