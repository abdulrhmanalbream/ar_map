package com.sarab.vision.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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

private val Bg = Color(0xFF0D1B2A)
private val Panel = Color(0xFF16202C)
private val Accent = Color(0xFF4FC3F7)
private val Muted = Color(0xFF9FB3C8)

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "إلى أين تريد الذهاب؟",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (userFix != null) "دقة الموقع ±${userFix.accuracyMeters.toInt()} م"
                    else "جاري تحديد موقعك…",
                    color = Muted,
                    fontSize = 12.sp
                )
            }
            Text(
                "إغلاق",
                color = Accent,
                fontSize = 14.sp,
                modifier = Modifier
                    .background(Panel, RoundedCornerShape(10.dp))
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
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedIndicatorColor = Accent,
                unfocusedIndicatorColor = Color(0xFF243244),
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

        if (ranked.isEmpty()) {
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
                            .clickable(onClick = onOpenSurvey)
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(ranked, key = { it.landmark.id }) { fix ->
                    LandmarkRow(
                        landmark = fix.landmark,
                        distanceMeters = fix.distanceMeters,
                        onClick = { onSelect(fix.landmark) }
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp)
        ) {
            BottomAction("الخريطة", Modifier.weight(1f), onOpenMap)
            BottomAction("وضع المسح", Modifier.weight(1f), onOpenSurvey)
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
            .background(Panel, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(Accent.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(landmark.category.letter, color = Accent, fontSize = 16.sp)
        }
        Spacer(Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(landmark.name, color = Color.White, fontSize = 16.sp)
            Text(
                buildString {
                    append(landmark.category.labelAr)
                    if (landmark.photos.isNotEmpty()) {
                        append(" · ${landmark.photos.size} صور")
                    }
                },
                color = Muted,
                fontSize = 12.sp
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
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 9.dp)
    )
}

@Composable
private fun BottomAction(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text = label,
        color = Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier
            .background(Color(0xFF243244), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp)
    )
}
