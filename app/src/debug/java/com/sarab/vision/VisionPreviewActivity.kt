package com.sarab.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.sarab.vision.ui.HedayahTheme
import com.sarab.vision.ui.SceneDetail
import com.sarab.vision.ui.SceneMarkers
import com.sarab.vision.vision.*

/** Non-networked fixture for emulator layout QA, not a camera or recognition simulation. */
class VisionPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bitmap = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(android.graphics.Color.rgb(210, 210, 190))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = android.graphics.Color.rgb(34, 47, 40)
            drawRect(300f, 150f, 660f, 430f, paint)
            paint.color = android.graphics.Color.rgb(180, 154, 91)
            drawRect(300f, 210f, 660f, 240f, paint)
            paint.color = android.graphics.Color.BLACK; paint.textSize = 30f
            drawText("LOCAL UI FIXTURE - NOT A CAMERA RESULT", 100f, 70f, paint)
        }
        val state = EyeVisionState(enabled = true, paused = true, selectedItemId = "test-kaaba", scene = VisionScene(
            sceneId = "local-fixture", summary = "معاينة محلية لاختبار الواجهة فقط.",
            items = listOf(VisionItem("test-kaaba", "landmark", "الكعبة المشرفة · معاينة",
                "هذا محتوى تجريبي لمراجعة القراءة واللغة في واجهة معلومات المعلم. هذه الصورة مرسومة للاختبار، ولم تحللها كاميرا Eye أو المساعد الذكي.",
                VisionBox(277.0, 312.0, 796.0, 687.0)),
                VisionItem("test-sign", "text", "EXIT", "ترجمة مقترحة: مخرج", VisionBox(300.0, 350.0, 420.0, 600.0))),
            sources = emptyList(), provider = "local-ui-fixture", previewBitmap = bitmap,
            capturedAtElapsedMs = 0, pose = null), reply = "هنا يظهر رد المساعد عن المعلم المحدد باللغة المختارة، مع مصادر المعلومات عند توفرها.")
        setContent {
            HedayahTheme(camera = true) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(Modifier.fillMaxSize()) {
                        if (intent.getBooleanExtra("markers", false)) {
                            Image(bitmap.asImageBitmap(), "LOCAL UI FIXTURE", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            SceneMarkers(state.scene!!, Modifier.fillMaxSize()) { finish() }
                            Text("معاينة محلية — ليست نتيجة تعرف حقيقية")
                        } else SceneDetail(state, onSelect = {}, onAsk = {}, onDismiss = { finish() })
                    }
                }
            }
        }
    }
}
