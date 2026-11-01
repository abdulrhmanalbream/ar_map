package com.sarab.vision.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sarab.vision.assistant.HandsFreeStatus
import com.sarab.vision.core.GlassesOpticsCalibration
import java.util.Locale

/** One nudge is half a degree: about 19 px on a 1920-wide, 50.7-degree display. */
private const val NUDGE_DEGREES = 0.5
private const val SCALE_STEP = 0.02

/** Phone-side controls for the glasses picture, shown inside the camera settings sheet. */
@Composable
fun GlassesLinesSettings(
    linesMode: Boolean,
    onLinesModeChange: (Boolean) -> Unit,
    handsFree: Boolean,
    handsFreeStatus: HandsFreeStatus,
    onHandsFreeChange: (Boolean) -> Unit,
    displayFov: Float,
    onDisplayFovChange: (Float) -> Unit,
    calibration: GlassesOpticsCalibration,
    calibrating: Boolean,
    onCalibratingChange: (Boolean) -> Unit,
    onCalibrationChange: (GlassesOpticsCalibration) -> Unit,
    eyeCamera: Boolean,
) {
    val accent = Hedayah.HudGold
    val muted = Hedayah.HudMuted
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("عرض النظارة", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (linesMode) "الخطوط فقط: الأسهم وإطارات المعالم دون صورة الكاميرا"
                else "عكس شاشة الجوال كاملة", color = muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(checked = linesMode, onCheckedChange = onLinesModeChange,
                colors = SwitchDefaults.colors(checkedTrackColor = accent))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("المساعد الصوتي الدائم", color = Color.White, fontSize = 15.sp)
                Text(when (handsFreeStatus) {
                    HandsFreeStatus.WAITING_FOR_WAKE -> "قل «يا مطوف» ثم سؤالك"
                    HandsFreeStatus.LISTENING_FOR_QUESTION -> "أسمعك…"
                    HandsFreeStatus.PAUSED -> "متوقف مؤقتاً أثناء الكلام"
                    HandsFreeStatus.PERMISSION_REQUIRED -> "يحتاج إذن الميكروفون"
                    HandsFreeStatus.UNAVAILABLE -> "التعرف على الكلام غير متاح لهذه اللغة على الجوال"
                    HandsFreeStatus.OFF -> if (handsFree) "يعمل عند توصيل النظارة والتطبيق مفتوح" else "متوقف"
                }, color = muted, fontSize = 13.sp)
            }
            Switch(checked = handsFree, onCheckedChange = onHandsFreeChange,
                colors = SwitchDefaults.colors(checkedTrackColor = accent))
        }
        Text("لا يُرسل شيء قبل «يا مطوف»؛ ما يُقال بعدها فقط يذهب إلى المساعد.", color = muted, fontSize = 12.sp)
        if (linesMode) {
            Spacer(Modifier.height(8.dp))
            Text("زاوية شاشة النظارة: ${displayFov.toInt()}°", color = Color.White, fontSize = 15.sp)
            Slider(value = displayFov, onValueChange = onDisplayFovChange, valueRange = 35f..70f)
            if (eyeCamera) {
                if (!calibrating) {
                    OutlinedButton(onClick = { onCalibratingChange(true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("مطابقة الإطارات مع الواقع", color = accent)
                    }
                    Text("مرة واحدة: يطابق ما تراه كاميرا Eye مع ما تراه عينك عبر النظارة.", color = muted, fontSize = 12.sp)
                } else {
                    Text("1) وجّه رأسك حتى تقع علامة + في منتصف شاشة الجوال على شيء واضح بعيد (باب، لوحة، عمود).\n" +
                        "2) حرّك الإطار البرتقالي في النظارة بالأسهم حتى يحيط بالشيء نفسه.",
                        color = muted, fontSize = 13.sp)
                    fun nudge(dx: Double = 0.0, dy: Double = 0.0, ds: Double = 0.0) = onCalibrationChange(
                        calibration.copy(offsetXDegrees = calibration.offsetXDegrees + dx,
                            offsetYDegrees = calibration.offsetYDegrees + dy, scale = calibration.scale + ds).bounded())
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Arrow labels describe movement in the wearer's view, independent of RTL layout.
                        NudgeButton("↑", Modifier.weight(1f)) { nudge(dy = -NUDGE_DEGREES) }
                        NudgeButton("↓", Modifier.weight(1f)) { nudge(dy = NUDGE_DEGREES) }
                        NudgeButton("←", Modifier.weight(1f)) { nudge(dx = -NUDGE_DEGREES) }
                        NudgeButton("→", Modifier.weight(1f)) { nudge(dx = NUDGE_DEGREES) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NudgeButton("تصغير", Modifier.weight(1f)) { nudge(ds = -SCALE_STEP) }
                        NudgeButton("تكبير", Modifier.weight(1f)) { nudge(ds = SCALE_STEP) }
                    }
                    Text(String.format(Locale.US, "إزاحة %.1f° · %.1f°  ·  مقياس %.2f",
                        calibration.offsetXDegrees, calibration.offsetYDegrees, calibration.scale), color = muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onCalibratingChange(false) },
                            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Hedayah.Hud),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("تم") }
                        TextButton(onClick = { onCalibrationChange(GlassesOpticsCalibration()) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("إعادة الضبط", color = accent) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NudgeButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) {
        Text(label, color = Hedayah.HudGold, fontSize = 18.sp)
    }
}
