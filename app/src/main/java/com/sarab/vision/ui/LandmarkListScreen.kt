package com.sarab.vision.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sarab.vision.core.GpsFix
import com.sarab.vision.core.Landmark
import com.sarab.vision.core.LandmarkCategory
import com.sarab.vision.core.formatDistanceAr
import com.sarab.vision.core.matchesQuery
import com.sarab.vision.core.rankByDistance

private val Bg = Hedayah.Ivory
private val Panel = Hedayah.Paper
private val Accent = Hedayah.Forest
private val Muted = Hedayah.Muted

/**
 * Landmark picker: search, filter by category, sorted by distance.
 *
 * Distance-first ordering is deliberate. On a campus where some buildings are
 * 250m away and others are 20m, "what is near me" is almost always the
 * question, and it saves the user from reading names they do not need.
 */
@Composable
fun LandmarkListScreen(
    landmarks: List<Landmark>,
    userFix: GpsFix?,
    onSelect: (Landmark) -> Unit,
    onOpenMap: () -> Unit,
    onOpenSurvey: () -> Unit,
    onOpenPaths: () -> Unit,
    onClose: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf<LandmarkCategory?>(null) }

    val ranked = remember(landmarks, userFix, query, categoryFilter) {
        val position = userFix?.position
        val filtered = landmarks
            .filter { categoryFilter == null || it.category == categoryFilter }
            .filter { matchesQuery(it, query) }

        if (position != null && position.isValid) {
            rankByDistance(filtered, position)
        } else {
            filtered.map { com.sarab.vision.core.LandmarkFix(it, Double.NaN, Double.NaN) }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            // Without these the title sits under the system clock and the
            // bottom buttons under the gesture bar.
            .windowInsetsPadding(WindowInsets.systemBars)
            .imePadding()
            .padding(horizontal = 16.dp)
    ) {
        item(key = "heading") {
            Spacer(Modifier.height(12.dp))
            HedayahMasthead(compact = true)
            Spacer(Modifier.height(24.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "إلى أين تريد الذهاب؟",
                        color = Hedayah.Ink,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (userFix != null) "دقة الموقع ±${userFix.accuracyMeters.toInt()} م"
                        else "جاري تحديد موقعك…",
                        color = Muted,
                        fontSize = 13.sp
                    )
                }
                Text(
                    "إغلاق",
                    color = Accent,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .background(Panel, RoundedCornerShape(10.dp))
                        .heightIn(min = 48.dp)
                        .clickable(onClick = onClose)
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                )
            }

            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("ابحث… مثال: وحدة 13", color = Muted, fontSize = 14.sp) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Panel,
                    unfocusedContainerColor = Panel,
                    focusedTextColor = Hedayah.Ink,
                    unfocusedTextColor = Hedayah.Ink,
                    focusedIndicatorColor = Accent,
                    unfocusedIndicatorColor = Hedayah.Soft,
                    cursorColor = Accent
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                FilterChip("الكل", categoryFilter == null) { categoryFilter = null }
                LandmarkCategory.entries.forEach { c ->
                    // Only offer filters that would actually return something.
                    if (landmarks.any { it.category == c }) {
                        FilterChip(c.labelAr, categoryFilter == c) {
                            categoryFilter = if (categoryFilter == c) null else c
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

        }
        if (ranked.isEmpty()) {
            item(key = "empty") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        if (landmarks.isEmpty()) "لا توجد معالم مسجلة بعد"
                        else "لا نتائج للبحث",
                        color = Muted,
                        fontSize = 15.sp
                    )
                    if (landmarks.isEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "ابدأ وضع المسح",
                            color = Accent,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .background(Panel, RoundedCornerShape(12.dp))
                                .heightIn(min = 48.dp)
                        .clickable(onClick = onOpenSurvey)
                                .padding(horizontal = 20.dp, vertical = 12.dp)
                        )
                    }
                }
            }
        } else {
            items(ranked, key = { "landmark:${it.landmark.id}" }) { fix ->
                LandmarkRow(
                    landmark = fix.landmark,
                    distanceMeters = fix.distanceMeters,
                    onClick = { onSelect(fix.landmark) }
                )
            }
        }

        item(key = "tools") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
            ) {
                BottomAction("الخريطة", Modifier.weight(1f), onOpenMap)
                BottomAction("وضع المسح", Modifier.weight(1f), onOpenSurvey)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 14.dp)
            ) {
                // Drawing the paths is what turns the tested routing engine into
                // something that actually produces routes, so it earns its own
                // entry point rather than hiding in a menu.
                BottomAction("رسم الطرق", Modifier.weight(1f), onOpenPaths)
            }
        }
    }
}

@Composable
private fun LandmarkRow(
    landmark: Landmark,
    distanceMeters: Double,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .background(Panel, RoundedCornerShape(12.dp))
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(Accent.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(landmark.category.letter, color = Accent, fontSize = 16.sp)
        }
        Spacer(Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(landmark.name, color = Hedayah.Ink, fontSize = 16.sp)
            Text(
                buildString {
                    append(landmark.category.labelAr)
                    if (landmark.photos.isNotEmpty()) {
                        append(" · ${landmark.photos.size} صور")
                    }
                },
                color = Muted,
                fontSize = 13.sp
            )
        }
        if (!distanceMeters.isNaN()) {
            Text(
                formatDistanceAr(distanceMeters),
                color = Accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) Bg else Muted,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .background(if (selected) Accent else Panel, RoundedCornerShape(20.dp))
            .heightIn(min = 48.dp)
                    .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 9.dp)
    )
}

@Composable
private fun BottomAction(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text = label,
        color = Hedayah.Ink,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier
            .background(Hedayah.Soft, RoundedCornerShape(12.dp))
            .heightIn(min = 48.dp)
                    .clickable(onClick = onClick)
            .padding(vertical = 14.dp)
    )
}
