package com.sarab.vision.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import com.sarab.vision.core.LandmarkFix
import com.sarab.vision.core.formatDistanceAr
import com.sarab.vision.core.matchesQuery
import com.sarab.vision.core.rankByDistance

private val Sheet = Color(0xF5121A24)
private val Row1 = Color(0xFF1B2634)
private val Accent = Color(0xFF4FC3F7)
private val Muted = Color(0xFF9FB3C8)

/**
 * Destination sheet that slides over the camera.
 *
 * Deliberately a sheet rather than a separate screen: the camera is the app,
 * and making the user leave the live view to pick a destination breaks the
 * one thing the app is for. It covers about two thirds of the screen, so the
 * camera stays visible behind it.
 */
@Composable
fun DestinationPicker(
    landmarks: List<Landmark>,
    userFix: GpsFix?,
    visible: Boolean,
    onSelect: (Landmark) -> Unit,
    onDismiss: () -> Unit,
    onOpenTools: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf<LandmarkCategory?>(null) }

    val ranked = remember(landmarks, userFix, query, categoryFilter) {
        val position = userFix?.position
        val filtered = landmarks
            .filter { categoryFilter == null || it.category == categoryFilter }
            .filter { matchesQuery(it, query) }

        if (position != null && position.isValid) rankByDistance(filtered, position)
        else filtered.map { LandmarkFix(it, Double.NaN, Double.NaN) }
    }

    // Scrim, so a tap outside dismisses without hunting for a close button.
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(onClick = onDismiss)
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut()
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.72f)
                    .background(Sheet, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                    .padding(horizontal = 18.dp)
            ) {
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(38.dp)
                        .height(4.dp)
                        .background(Muted.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.height(14.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "إلى أين تريد الذهاب؟",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            userFix?.let { "دقة الموقع ±${it.accuracyMeters.toInt()} م" }
                                ?: "جاري تحديد موقعك…",
                            color = Muted,
                            fontSize = 12.sp
                        )
                    }
                    Text(
                        "أدوات",
                        color = Accent,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .background(Row1, RoundedCornerShape(10.dp))
                            .clickable(onClick = onOpenTools)
                            .padding(horizontal = 14.dp, vertical = 9.dp)
                    )
                }

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("ابحث… مثال: وحدة 13", color = Muted, fontSize = 14.sp) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Row1,
                        unfocusedContainerColor = Row1,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedIndicatorColor = Accent,
                        unfocusedIndicatorColor = Color(0xFF243244),
                        cursorColor = Accent
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    Chip("الكل", categoryFilter == null) { categoryFilter = null }
                    LandmarkCategory.entries.forEach { c ->
                        if (landmarks.any { it.category == c }) {
                            Chip(c.labelAr, categoryFilter == c) {
                                categoryFilter = if (categoryFilter == c) null else c
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (ranked.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            if (landmarks.isEmpty()) "لا توجد معالم مسجلة بعد"
                            else "لا نتائج للبحث",
                            color = Muted,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(ranked, key = { it.landmark.id }) { fix ->
                            DestinationRow(fix) { onSelect(fix.landmark) }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun DestinationRow(fix: LandmarkFix, onClick: () -> Unit) {
    val lm = fix.landmark
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(Row1, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(Accent.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(lm.category.letter, color = Accent, fontSize = 15.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(lm.name, color = Color.White, fontSize = 16.sp)
            Text(lm.category.labelAr, color = Muted, fontSize = 12.sp)
        }
        if (!fix.distanceMeters.isNaN()) {
            Text(
                formatDistanceAr(fix.distanceMeters),
                color = Accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) Color(0xFF0B1520) else Muted,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .background(if (selected) Accent else Row1, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 9.dp)
    )
}
