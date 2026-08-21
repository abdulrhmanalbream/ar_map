package com.sarab.vision.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sarab.vision.core.CampusSeed
import com.sarab.vision.core.GpsFix
import com.sarab.vision.core.Landmark
import com.sarab.vision.core.LandmarkCategory
import com.sarab.vision.core.LandmarkPhoto
import com.sarab.vision.core.LatLng
import com.sarab.vision.core.Viewpoint
import com.sarab.vision.data.ImageImporter
import java.io.File
import kotlin.math.roundToInt

private val Bg = Color(0xFF0D1B2A)
private val Panel = Color(0xF2141C26)
private val Accent = Color(0xFF4FC3F7)
private val Good = Color(0xFF66D9AF)
private val Warn = Color(0xFFFFB300)
private val Bad = Color(0xFFEF5350)
private val Muted = Color(0xFF9FB3C8)

/** Accuracy at or below this is good enough to record a landmark. */
private const val GOOD_ACCURACY_M = 10f

/** Above this we actively discourage saving. */
private const val POOR_ACCURACY_M = 20f

/**
 * Survey mode: walk the campus and record landmarks.
 *
 * Designed for use outdoors while walking, so the controls are large, the GPS
 * quality is always visible, and the app refuses to silently record a bad
 * fix -- a landmark saved at 30m accuracy would send every future user to the
 * wrong side of a building, and that error is invisible later.
 */
@Composable
fun SurveyScreen(
    fix: GpsFix?,
    samplesCollected: Int,
    capturedLandmarks: List<Landmark>,
    pendingPhotoCount: Int,
    pendingPhotos: List<LandmarkPhoto>,
    photoDir: File,
    /** Hand-picked coordinate, when the surveyor chose one on the map. */
    manualPosition: LatLng?,
    onPickLocation: () -> Unit,
    onClearManualPosition: () -> Unit,
    onCapturePhoto: (Viewpoint) -> Unit,
    onSaveLandmark: (name: String, category: LandmarkCategory, detail: String) -> Unit,
    onExport: () -> Unit,
    onExit: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(LandmarkCategory.FACULTY) }

    val accuracy = fix?.accuracyMeters
    val accuracyColour = when {
        accuracy == null -> Bad
        accuracy <= GOOD_ACCURACY_M -> Good
        accuracy <= POOR_ACCURACY_M -> Warn
        else -> Bad
    }

    // A hand-picked point replaces the GPS requirement outright.
    //
    // The old gate demanded a live fix better than 20m, which never arrives
    // indoors -- so the screen sat on "waiting for GPS" and no landmark could
    // ever be added. Photos were never part of this gate and still are not.
    val hasUsablePosition = manualPosition != null ||
        (fix != null && accuracy != null && accuracy <= POOR_ACCURACY_M)
    val canSave = hasUsablePosition && name.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "إضافة معلم",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "قف عند المدخل واحفظ، أو حدد الموقع على الخريطة",
                    color = Muted,
                    fontSize = 13.sp
                )
            }
            Text(
                "تم",
                color = Accent,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .background(Color(0xFF1B2836), RoundedCornerShape(10.dp))
                    .clickable(onClick = onExit)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }

        Spacer(Modifier.height(18.dp))

        // ---- Where this landmark is --------------------------------------
        //
        // Two independent ways to answer that question. Standing there with a
        // good fix is the better one, but it must never be the only one --
        // that is what left this screen stuck on "waiting for GPS".
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            if (manualPosition != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(Accent, CircleShape)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "موقع محدد يدوياً",
                        color = Accent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "إلغاء",
                        color = Muted,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .background(Color(0xFF243244), RoundedCornerShape(10.dp))
                            .clickable(onClick = onClearManualPosition)
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "%.6f, %.6f".format(
                        manualPosition.latitude,
                        manualPosition.longitude
                    ),
                    color = Muted,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "دقة تقديرية ±8 م — أقل من قياس فعلي على الأرض، لكنه كافٍ للتوجيه.",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(accuracyColour, CircleShape)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = when {
                            fix == null -> "بانتظار GPS…"
                            accuracy == null -> "دقة GPS غير معروفة"
                            accuracy <= GOOD_ACCURACY_M ->
                                "GPS ممتاز · ±${accuracy.roundToInt()} م"
                            accuracy <= POOR_ACCURACY_M ->
                                "GPS مقبول · ±${accuracy.roundToInt()} م"
                            else -> "GPS ضعيف · ±${accuracy.roundToInt()} م"
                        },
                        color = accuracyColour,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (fix == null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "أول قراءة قد تستغرق دقيقة في مكان مكشوف. إن كنت داخل مبنى " +
                            "فلن تصل أبداً — استخدم تحديد الموقع على الخريطة بالأسفل.",
                        color = Muted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "%.6f, %.6f · %d قراءات".format(
                            fix.position.latitude,
                            fix.position.longitude,
                            samplesCollected
                        ),
                        color = Muted,
                        fontSize = 12.sp
                    )
                    if (accuracy != null && accuracy > POOR_ACCURACY_M) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "الدقة ضعيفة جداً للحفظ. اخرج للمكشوف، أو حدد الموقع " +
                                "على الخريطة.",
                            color = Bad,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = if (manualPosition == null) {
                    "تحديد الموقع على الخريطة"
                } else {
                    "تعديل الموقع على الخريطة"
                },
                color = Bg,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Accent, RoundedCornerShape(12.dp))
                    .clickable(onClick = onPickLocation)
                    .padding(vertical = 13.dp)
            )
        }

        Spacer(Modifier.height(16.dp))

        // ---- Quick-fill templates ------------------------------------------
        // Typing Arabic on a phone outdoors in the sun is miserable, so the
        // planned landmarks are one tap away with their details prefilled.
        val remaining = CampusSeed.remaining(capturedLandmarks)
        if (remaining.isNotEmpty()) {
            Text("المعالم المتبقية", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                remaining.forEach { t ->
                    Text(
                        text = t.name,
                        color = Warn,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .background(Color(0x33FFB300), RoundedCornerShape(20.dp))
                            .clickable {
                                name = t.name
                                detail = t.detail
                                category = t.category
                            }
                            .padding(horizontal = 14.dp, vertical = 9.dp)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // ---- Landmark details --------------------------------------------
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("اسم المعلم") },
            placeholder = { Text("مثال: كلية الحاسب الآلي") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            colors = fieldColours(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        Text("التصنيف", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            LandmarkCategory.entries.forEach { c ->
                val selected = c == category
                Text(
                    text = c.labelAr,
                    color = if (selected) Bg else Muted,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .background(
                            if (selected) Accent else Color(0xFF1B2836),
                            RoundedCornerShape(20.dp)
                        )
                        .clickable { category = c }
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = detail,
            onValueChange = { detail = it },
            label = { Text("ملاحظات (اختياري)") },
            placeholder = { Text("الأدوار، المعامل، المداخل…") },
            colors = fieldColours(),
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
        )

        Spacer(Modifier.height(18.dp))

        // ---- Photos -------------------------------------------------------
        Text(
            "الصور ($pendingPhotoCount)",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "اختيارية. الأفضل عدة صور: عند المدخل، من الجانب، ومن بعيد.",
            color = Muted,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PhotoButton("مدخل", Modifier.weight(1f)) { onCapturePhoto(Viewpoint.ENTRANCE) }
            PhotoButton("جانب", Modifier.weight(1f)) { onCapturePhoto(Viewpoint.SIDE) }
            PhotoButton("بعيد", Modifier.weight(1f)) { onCapturePhoto(Viewpoint.FAR) }
        }

        // Thumbnails of what has actually been attached. Without them the
        // only feedback is a counter, and there is no way to tell a correct
        // photo from a mis-tap until the survey is finished.
        if (pendingPhotos.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                pendingPhotos.forEach { photo ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val bitmap = remember(photo.file) {
                            ImageImporter.loadThumbnail(photoDir, photo.file, 240)
                        }
                        Box(
                            modifier = Modifier
                                .size(88.dp)
                                .background(Color(0xFF16202C), RoundedCornerShape(12.dp))
                        ) {
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = photo.viewpoint.label,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(12.dp))
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when (photo.viewpoint) {
                                Viewpoint.ENTRANCE -> "مدخل"
                                Viewpoint.SIDE -> "جانب"
                                Viewpoint.FAR -> "بعيد"
                                Viewpoint.OTHER -> "أخرى"
                            },
                            color = Muted,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = { onSaveLandmark(name.trim(), category, detail.trim()); name = ""; detail = "" },
            enabled = canSave,
            colors = ButtonDefaults.buttonColors(
                containerColor = Accent,
                contentColor = Bg,
                disabledContainerColor = Color(0xFF1B2836),
                disabledContentColor = Muted
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
        ) {
            Text(
                text = when {
                    name.isBlank() -> "اكتب اسم المعلم أولاً"
                    !hasUsablePosition -> "حدد الموقع على الخريطة"
                    manualPosition != null -> "حفظ في الموقع المحدد"
                    else -> "حفظ المعلم هنا"
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(Modifier.height(24.dp))

        // ---- Already captured ---------------------------------------------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "المعالم المحفوظة (${capturedLandmarks.size})",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            if (capturedLandmarks.isNotEmpty()) {
                Text(
                    "تصدير",
                    color = Accent,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .background(Color(0xFF1B2836), RoundedCornerShape(10.dp))
                        .clickable(onClick = onExport)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        if (capturedLandmarks.isEmpty()) {
            Text(
                "لا يوجد شيء محفوظ بعد.",
                color = Muted,
                fontSize = 13.sp
            )
        } else {
            capturedLandmarks.forEach { lm ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .background(Color(0xFF16202C), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(Accent.copy(alpha = 0.18f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(lm.category.letter, color = Accent, fontSize = 14.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(lm.name, color = Color.White, fontSize = 15.sp)
                        Text(
                            buildString {
                                append(lm.category.labelAr)
                                append(" · ${lm.photos.size} صور · ")
                                append("±${lm.capturedAccuracyM.roundToInt()} م")
                                // Flagged plainly: a hand-placed point is an
                                // estimate, and whoever revisits this survey
                                // needs to know which rows were measured.
                                if (lm.placedManually) append(" · يدوي")
                            },
                            color = if (lm.placedManually) Warn else Muted,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun PhotoButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text = label,
        color = Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .background(Color(0xFF243244), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}

@Composable
private fun fieldColours() = TextFieldDefaults.colors(
    focusedContainerColor = Color(0xFF16202C),
    unfocusedContainerColor = Color(0xFF16202C),
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedLabelColor = Accent,
    unfocusedLabelColor = Muted,
    focusedIndicatorColor = Accent,
    unfocusedIndicatorColor = Color(0xFF243244),
    cursorColor = Accent
)

