package com.sarab.vision

import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sarab.vision.diagnostics.DiagnosticLog
import com.sarab.vision.ritual.RitualCounterStore
import com.sarab.vision.ritual.RitualWatchState
import com.sarab.vision.ui.HedayahTheme
import com.sarab.vision.ui.ReligiousCameraHud
import com.sarab.vision.vision.EyeVisionController
import com.sarab.vision.vision.VisionPose
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Readability fixture: actual HUD/controller over explicit synthetic pixels, with no network. */
@OptIn(ExperimentalComposeUiApi::class)
class VisionPanelPreviewActivity : ComponentActivity() {
    private lateinit var controller: EyeVisionController
    private lateinit var report: File
    private lateinit var picture: Bitmap
    private var language = "ar"
    private var runId = ""
    private var phase = "starting"
    private var failure = ""
    private var requests = 0
    private var frames = 0
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val emulator = Build.HARDWARE in setOf("ranchu", "goldfish") &&
            (Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val offline = Settings.Global.getInt(contentResolver, "airplane_mode_on", 0) == 1 &&
            Settings.Global.getInt(contentResolver, "wifi_on", 1) == 0 &&
            Settings.Global.getInt(contentResolver, "mobile_data", 1) == 0
        language = intent.getStringExtra("language") ?: "ar"
        runId = intent.getStringExtra("run_id").orEmpty()
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0 || !emulator || !offline ||
            !intent.getBooleanExtra("allow_fixture", false) || runId.isBlank() || language !in setOf("ar", "en")) {
            finish()
            return
        }
        report = File(filesDir, "vision-panel-preview.json")
        val prefs = getSharedPreferences("sarab_platform", MODE_PRIVATE)
        val token = prefs.getString("token", "").orEmpty()
        if (token.isNotBlank() && token != FIXTURE_TOKEN) {
            failure = "Existing non-fixture enrollment; no preferences changed"
            phase = "failed"
            writeReport()
            finish()
            return
        }
        check(prefs.edit().putString("token", FIXTURE_TOKEN).putString("server", "https://127.0.0.1:1")
            .putString("name", "LOCAL QA").putString("group", "LOCAL QA — no server")
            .putString("language", language).putBoolean("sharing", false).commit())
        check(getSharedPreferences("smart_mutawwif_vision", MODE_PRIVATE).edit().putString("language", language).commit())
        requestedOrientation = if (intent.getBooleanExtra("landscape", false))
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        applyCameraWindow()
        DiagnosticLog.initialize(this)
        picture = syntheticPicture()
        controller = EyeVisionController(this, lifecycleScope, requestOverride = { path, body, server, requestToken ->
            check(server == "https://127.0.0.1:1" && requestToken == FIXTURE_TOKEN)
            check(path in setOf("/vision/analyze", "/vision/ask"))
            check(body.getJSONObject("image").getString("data").isNotBlank())
            requests++
            delay(150)
            if (path == "/vision/analyze") analysis() else JSONObject()
                .put("reply", "LOCAL QA — " + (if (language == "ar")
                    "هذا جواب محلي طويل لاختبار وضوح المحادثة فوق الصورة الساطعة. يجب أن تبقى الكاميرا والمايك والإعدادات والعدّاد متاحة أثناء قراءة الإجابة. "
                else "This long local response tests conversation readability over the bright picture. The camera, microphone, settings and counter should remain available while reading. ").repeat(6))
                .put("sources", JSONArray()).put("provider", "local-fixture")
        })
        val counterStore = RitualCounterStore.get(this)
        setContent {
            val state by controller.state.collectAsState()
            val counter by counterStore.state.collectAsState()
            HedayahTheme(camera = true) {
                Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    Image(picture.asImageBitmap(), "LOCAL QA bright busy synthetic scene", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    ReligiousCameraHud(controller, state, enrolled = true, cameraLive = true,
                        counterStore = counterStore, counterState = counter, watch = RitualWatchState(),
                        onConnect = {}, onNavigation = {}, onCameraSettings = {},
                        phoneCamera = !intent.getBooleanExtra("landscape", false), motionAvailable = true)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    // New immutable frame objects are required by the production freshness gate.
                    val frame = picture.copy(Bitmap.Config.ARGB_8888, false)!!
                    controller.onFrame(frame, VisionPose(0.0, 0.0, 940L), SystemClock.elapsedRealtime())
                    frames++
                    writeReport()
                    delay(250)
                }
            }
        }
        lifecycleScope.launch {
            try {
                withTimeout(12_000) {
                    while (!resumed) delay(20)
                    controller.setCameraAvailable(true)
                    controller.setEnabled(true)
                    controller.setLanguage(language)
                    controller.setPaused(false)
                    while (controller.state.value.scene == null || controller.state.value.requestBusy) delay(40)
                    controller.setPaused(true)
                    if (intent.getBooleanExtra("start_selected", false)) {
                        controller.selectItem("local-maqam")
                        controller.pinScene()
                    }
                    if (intent.getBooleanExtra("conversation", false)) {
                        check(controller.beginVoiceTurn())
                        controller.askLive(if (language == "ar") "LOCAL QA — اختبر قراءة الجواب الطويل" else "LOCAL QA — test a long response")
                        while (controller.state.value.requestBusy || controller.state.value.reply == null) delay(40)
                        controller.endVoiceTurn()
                    }
                    phase = "ready"
                }
            } catch (problem: Exception) {
                failure = "${problem.javaClass.simpleName}: ${problem.message.orEmpty().take(300)}"
                phase = "failed"
            }
            writeReport()
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        applyCameraWindow()
        if (::controller.isInitialized) controller.setForeground(true)
    }

    override fun onPause() {
        resumed = false
        if (::controller.isInitialized) controller.setForeground(false)
        super.onPause()
    }

    override fun onStop() {
        if (::controller.isInitialized) controller.onStopped()
        super.onStop()
    }

    override fun onDestroy() {
        if (::controller.isInitialized) controller.close()
        super.onDestroy()
    }

    private fun applyCameraWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun writeReport() {
        if (!::report.isInitialized) return
        val state = if (::controller.isInitialized) controller.state.value else null
        report.writeText(JSONObject().put("runId", runId).put("phase", phase).put("failure", failure)
            .put("language", language).put("requests", requests).put("frames", frames)
            .put("fontScale", resources.configuration.fontScale.toDouble())
            .put("width", window.decorView.width).put("height", window.decorView.height)
            .put("windowRoots", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WindowInspector.getGlobalWindowViews().size else -1)
            .put("resumed", resumed).put("sceneId", state?.scene?.sceneId ?: JSONObject.NULL)
            .put("pinned", state?.scenePinned ?: false).put("markersVisible", state?.markersVisible ?: false)
            .put("selectedItemId", state?.selectedItemId ?: JSONObject.NULL)
            .put("conversationActive", state?.conversationActive ?: false).put("replyLength", state?.reply?.length ?: 0)
            .put("syntheticPixelsOnly", true).put("networkTransport", false).put("speechRecognitionInvoked", false)
            .toString())
    }

    /** Existing catalog excerpt, with explicit nonreligious test prose to stress wrapping. */
    private fun analysis(): JSONObject {
        val arabic = language == "ar"
        val sources = JSONArray().put(JSONObject().put("id", "haramain-maqam")
            .put("title", if (arabic) "مقام إبراهيم — الهيئة العامة للعناية بشؤون الحرمين" else "Maqam Ibrahim — General Authority for the Care of the Two Holy Mosques")
            .put("url", "https://alharamain.gov.sa/public/?page=maqam-ibrahim-ar"))
            .put(JSONObject().put("id", "quran-2-125").put("title", if (arabic) "القرآن الكريم — البقرة ١٢٥" else "The Quran — Al-Baqarah 2:125")
                .put("url", "https://quran.com/2/125"))
        val stressText = if (arabic)
            "هذا نص تجريبي طويل لاختبار القراءة فوق خلفية ساطعة ومزدحمة. ينبغي أن تبقى الحروف واضحة والمسافات مريحة عند تكبير خط الهاتف، وأن يظل زر المايك والرجوع ثابتين بينما يتمرر المحتوى داخل اللوحة. الصورة مرسومة محلياً وليست تصويراً فعلياً للحرم، وهذه الفقرة وصف للاختبار وليست إرشاداً دينياً. "
        else "This deliberately long local test paragraph checks readability over a bright, busy background. Letter spacing and lines must remain comfortable at an enlarged system font size. The microphone and return controls must stay fixed while this content scrolls. This is a synthetic drawing, not a photograph of the sanctuary; this paragraph describes a UI test, not religious guidance. "
        val devotional = JSONObject().put("id", "maqam-quran-2-125")
            .put("title", if (arabic) "تذكير من القرآن عن مقام إبراهيم" else "A Quranic reminder about Maqam Ibrahim")
            .put("arabicText", "وَاتَّخِذُوا مِن مَّقَامِ إِبْرَاهِيمَ مُصَلًّى")
            .put("translation", if (arabic) "المعنى: اتخذوا من مقام إبراهيم مكاناً للصلاة." else "Meaning: take the standing place of Abraham as a place of prayer.")
            .put("note", if (arabic) "مقتطف من البقرة ١٢٥، وليس صيغة دعاء خاصة بالمقام. التعرف على الصورة لا يحدد موضع الصلاة ولا يؤكد انتهاء الطواف." else "An excerpt from Al-Baqarah 2:125, not a special supplication for this landmark. Image recognition does not determine where to pray or confirm completion of tawaf.")
            .put("sourceIds", JSONArray(listOf("quran-2-125")))
        return JSONObject().put("sceneId", "local-panel-$runId")
            .put("summary", "LOCAL QA — bright synthetic scene / local response only")
            .put("provider", "local-fixture").put("sources", sources)
            .put("items", JSONArray().put(JSONObject().put("id", "local-maqam").put("kind", "landmark")
                .put("landmarkId", "maqam_ibrahim").put("label", if (arabic) "مقام إبراهيم · LOCAL QA" else "Maqam Ibrahim · LOCAL QA")
                .put("description", "LOCAL QA — " + stressText.repeat(3))
                .put("box", JSONArray(listOf(300, 370, 660, 630)))
                .put("sourceIds", JSONArray(listOf("haramain-maqam"))).put("devotionals", JSONArray().put(devotional))))
    }

    private fun syntheticPicture(): Bitmap {
        val target = Bitmap.createBitmap(960, 720, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val colors = intArrayOf(0xFFFFFCEC.toInt(), 0xFFD7CDB0.toInt(), 0xFF8EABA0.toInt(), 0xFFF9D578.toInt())
        for (row in 0..23) for (column in 0..31) {
            paint.color = colors[(row * 3 + column) % colors.size]
            canvas.drawRect(column * 30f, row * 30f, column * 30f + 30, row * 30f + 30, paint)
        }
        for (index in 0..45) {
            val x = (index * 97 % 920 + 20).toFloat()
            val y = (index * 71 % 620 + 80).toFloat()
            paint.color = if (index % 2 == 0) 0xFFFAFAF6.toInt() else 0xFF24382D.toInt()
            canvas.drawCircle(x, y, 13f, paint)
            canvas.drawRoundRect(x - 16, y + 10, x + 16, y + 55, 8f, 8f, paint)
        }
        paint.color = 0xFF4C3F1E.toInt()
        canvas.drawRect(356f, 216f, 604f, 476f, paint)
        for (row in 0..11) for (column in 0..10) {
            paint.color = if ((row * 19 + column * 23) % 7 < 3) 0xFFFFD976.toInt() else 0xFF89713D.toInt()
            canvas.drawRect(360f + column * 22, 220f + row * 21, 378f + column * 22, 237f + row * 21, paint)
        }
        paint.color = android.graphics.Color.BLACK
        paint.textSize = 31f
        for (y in listOf(45f, 675f)) canvas.drawText("LOCAL QA / SYNTHETIC / NO CAMERA", 26f, y, paint)
        return target.copy(Bitmap.Config.ARGB_8888, false)!!.also { target.recycle() }
    }

    private companion object { const val FIXTURE_TOKEN = "local-vision-delay-fixture-not-a-real-token" }
}
