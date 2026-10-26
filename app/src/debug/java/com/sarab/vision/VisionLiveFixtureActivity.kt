package com.sarab.vision

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
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
import com.sarab.vision.vision.encodeVisionImage
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * Explicit, DUMP-protected offline emulator fixture. Production controller, encoder and HUD;
 * synthetic pixels and delayed in-memory responses only. STT/TTS engine quality is not tested.
 */
@OptIn(ExperimentalComposeUiApi::class)
class VisionLiveFixtureActivity : ComponentActivity() {
    private lateinit var controller: EyeVisionController
    private lateinit var report: File
    private var scenario = ""
    private var runId = ""
    private var phase = "starting"
    private var result = "RUNNING"
    private var failure = ""
    private var resumed = false
    private var stopped = false
    private var hudEnabledCommitted = false
    private var feeding = true
    private var variant = 0
    private var trackingOffset = 0
    private var trackingOccluded = false
    private val trackedCenters = mutableListOf<Double>()
    private var started = 0
    private var completed = 0
    private val paths = mutableListOf<String>()
    private val askImages = mutableListOf<String>()
    private val askHistories = mutableListOf<JSONArray>()
    private val checks = linkedSetOf<String>()
    private var lastAnalyzeImage = ""
    private var bitmap by mutableStateOf<Bitmap?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val emulator = Build.HARDWARE in setOf("ranchu", "goldfish") &&
            (Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        scenario = intent.getStringExtra("scenario").orEmpty()
        runId = intent.getStringExtra("run_id").orEmpty()
        val offline = Settings.Global.getInt(contentResolver, "airplane_mode_on", 0) == 1 &&
            Settings.Global.getInt(contentResolver, "wifi_on", 1) == 0 &&
            Settings.Global.getInt(contentResolver, "mobile_data", 1) == 0
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0 || !emulator || !offline ||
            !intent.getBooleanExtra("allow_fixture", false) || runId.isBlank() ||
            scenario !in setOf("live_without_scene", "queued_turns", "manual_queue", "revoke_pending", "pinned_permission", "selected_ui", "tracking_move")) {
            finish()
            return
        }
        report = File(filesDir, "vision-live-$scenario.json")
        val prefs = getSharedPreferences("sarab_platform", MODE_PRIVATE)
        val token = prefs.getString("token", "").orEmpty()
        // The existing delayed fixture uses this same unmistakably local token. Real bindings are refused.
        if (token.isNotBlank() && token != FIXTURE_TOKEN) {
            result = "FAIL"
            failure = "Existing non-fixture enrollment; no preferences changed"
            writeReport()
            finish()
            return
        }
        check(prefs.edit().putString("token", FIXTURE_TOKEN).putString("server", "https://127.0.0.1:1")
            .putString("name", "LOCAL QA").putString("group", "LOCAL QA — no server")
            .putString("language", "ar").putBoolean("sharing", false).commit())
        check(getSharedPreferences("smart_mutawwif_vision", MODE_PRIVATE).edit().putString("language", "ar").commit())
        DiagnosticLog.initialize(this)
        controller = EyeVisionController(this, lifecycleScope, requestOverride = { path, body, server, requestToken ->
            check(server == "https://127.0.0.1:1" && requestToken == FIXTURE_TOKEN)
            check(path in setOf("/vision/analyze", "/vision/ask"))
            val image = body.getJSONObject("image").getString("data")
            check(image.isNotBlank())
            if (path == "/vision/ask") {
                askImages += image
                askHistories += JSONArray(body.getJSONArray("history").toString())
            } else lastAnalyzeImage = image
            paths += path
            val number = ++started
            writeReport()
            // Late transport completion must still be rejected after a source/consent epoch changes.
            withContext(NonCancellable) {
                delay(1_200)
                completed++
                writeReport()
                if (path == "/vision/ask") JSONObject().put("reply", "LOCAL QA — الرد المحلي $number")
                    .put("provider", "local-fixture").put("sources", JSONArray())
                else analysis(number)
            }
        })
        val counterStore = RitualCounterStore.get(this)
        setContent {
            val state by controller.state.collectAsState()
            val counter by counterStore.state.collectAsState()
            LaunchedEffect(state.enabled) { hudEnabledCommitted = state.enabled }
            HedayahTheme(camera = true) {
                Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    bitmap?.let { Image(it.asImageBitmap(), "LOCAL QA synthetic scene", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    ReligiousCameraHud(controller, state, enrolled = true, cameraLive = true,
                        counterStore = counterStore, counterState = counter, watch = RitualWatchState(),
                        onConnect = {}, onNavigation = {}, onCameraSettings = {}, phoneCamera = true, motionAvailable = true)
                    Text("LOCAL QA · صورة مرسومة · بلا شبكة", Modifier.align(Alignment.Center)
                        .background(Color.Black.copy(alpha = .8f)).padding(8.dp), color = Color.White)
                }
            }
        }
        lifecycleScope.launch { controller.state.collect { writeReport() } }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    if (feeding) publishFrame()
                    delay(250)
                }
            }
        }
        lifecycleScope.launch {
            try {
                withTimeout(32_000) { runScenario() }
                result = "PASS"
                phase = "complete"
            } catch (problem: Exception) {
                result = "FAIL"
                failure = "${problem.javaClass.simpleName}: ${problem.message.orEmpty().take(400)}"
            }
            writeReport()
        }
    }

    private suspend fun runScenario() {
        waitFor { resumed }
        controller.setCameraAvailable(true)
        controller.setEnabled(true)
        controller.setPaused(scenario !in setOf("queued_turns", "tracking_move"))
        settleEnabledHud()
        publishFrame()
        when (scenario) {
            "live_without_scene" -> {
                check(controller.state.value.scene == null)
                val imageAtTap = beginTurn()
                variant++
                publishFrame()
                controller.askLive("LOCAL QA سؤال عن اللقطة عند لمس المايك")
                waitForReply(1)
                check(askImages.single() == imageAtTap) { "Live question must retain the exact JPEG captured at the mic tap" }
                check(askHistories.single().length() == 0) { "First live question must have no fabricated history" }
                check(controller.state.value.scene == null && !controller.state.value.scenePinned && controller.state.value.conversationActive) {
                    "First live question must remain an unpinned conversation without a recognized scene"
                }
                checks += setOf("live_without_recognition", "mic_tap_image_held", "unpinned_conversation", "reply_revision")
            }
            "queued_turns", "manual_queue" -> {
                val manual = scenario == "manual_queue"
                if (manual) controller.analyzeNow()
                waitFor { paths == listOf("/vision/analyze") }
                val imageAtTap = beginTurn()
                variant++
                publishFrame()
                controller.askLive("LOCAL QA السؤال الأول في الطابور")
                check(controller.state.value.analyzing && controller.state.value.asking && askImages.isEmpty())
                waitForReply(1)
                check(paths == listOf("/vision/analyze", "/vision/ask"))
                check(askImages.single() == imageAtTap && askHistories.single().length() == 0)
                check(!controller.state.value.scenePinned && controller.state.value.conversationActive)
                controller.endVoiceTurn()
                if (manual) {
                    controller.clearConversation()
                    check(!controller.state.value.scenePinned && !controller.state.value.conversationActive &&
                        controller.state.value.history.isEmpty())
                    checks += "manual_queued_reply_never_pins"
                    checks += "closing_manual_queue_leaves_live_context"
                }
                variant++
                publishFrame()
                val secondImage = beginTurn()
                controller.askLive("LOCAL QA السؤال الثاني عن الصورة الجديدة")
                waitForReply(2)
                check(askImages.size == 2 && askImages[1] == secondImage && askImages[1] != askImages[0])
                val history = askHistories[1]
                if (manual) {
                    check(history.length() == 0 && controller.state.value.history.size == 2)
                    checks += "closed_conversation_history_cleared"
                } else {
                    check(history.length() == 2 && history.getJSONObject(0).getString("role") == "user" &&
                        history.getJSONObject(1).getString("role") == "assistant")
                    check(history.getJSONObject(0).getString("text") == "LOCAL QA السؤال الأول في الطابور")
                    check(controller.state.value.history.size == 4)
                    checks += "two_entry_history"
                }
                check(!controller.state.value.scenePinned)
                checks += setOf(if (manual) "voice_priority_after_manual" else "voice_priority_after_auto",
                    "queued_exact_image", "new_turn_new_image", "reply_revision")
            }
            "revoke_pending" -> {
                for (consent in listOf(true, false)) {
                    beginTurn()
                    controller.askLive("LOCAL QA طلب سيلغى")
                    waitFor { started > completed }
                    val pending = started
                    if (consent) controller.setEnabled(false) else controller.setCameraAvailable(false)
                    waitFor { completed >= pending && !controller.state.value.requestBusy }
                    check(controller.state.value.reply == null && controller.state.value.history.isEmpty())
                    check(controller.state.value.conversationRevision == 0L)
                    checks += if (consent) "consent_late_reply_discard" else "source_late_reply_discard"
                    controller.setCameraAvailable(true)
                    controller.setEnabled(true)
                    controller.setPaused(true)
                    settleEnabledHud()
                    variant++
                    publishFrame()
                }
                beginTurn()
                controller.askLive("LOCAL QA طلب جديد بعد العودة")
                waitForReply(1)
                checks += "voice_recovery"
            }
            "pinned_permission" -> {
                controller.analyzeNow()
                waitFor { controller.state.value.scene != null && !controller.state.value.requestBusy }
                controller.selectItem("local-maqam")
                val scene = controller.state.value.scene
                val retained = lastAnalyzeImage
                feeding = false
                controller.setMicrophonePermissionPending(true)
                controller.setForeground(false)
                controller.setCameraAvailable(false)
                delay(100)
                check(controller.state.value.scene === scene && controller.state.value.scenePinned)
                controller.setForeground(true)
                controller.setMicrophonePermissionPending(false)
                check(controller.beginVoiceTurn())
                controller.ask("LOCAL QA سؤال عن اللقطة المحفوظة")
                waitForReply(1)
                check(askImages.single() == retained && controller.state.value.scene === scene)
                checks += setOf("permission_pause_retains_pin", "camera_unavailable_retained_ask")
                // The harness takes a screenshot, then sends actual HOME. onPause preserves this
                // pending-permission snapshot; actual onStop must finally clear it.
                controller.setMicrophonePermissionPending(true)
                phase = "await_background_clear"
                writeReport()
                waitFor { stopped }
                check(controller.state.value.scene == null && !controller.state.value.scenePinned && controller.state.value.reply == null)
                checks += "actual_on_stop_clears_pin"
                phase = "await_resume"
                writeReport()
                waitFor { resumed }
                controller.setCameraAvailable(true)
                controller.setPaused(true)
                feeding = true
                publishFrame()
            }
            "selected_ui" -> {
                controller.analyzeNow()
                waitFor { controller.state.value.scene != null && !controller.state.value.requestBusy }
                controller.selectItem("local-maqam")
                val item = controller.state.value.selectedItem!!
                check(item.landmarkId == "maqam_ibrahim" && item.devotionals.single().sourceIds == listOf("quran-2-125"))
                check(controller.state.value.scene!!.sources.size == 2)
                checks += "curated_metadata_parsed"
            }
            "tracking_move" -> {
                waitFor { controller.state.value.markersVisible && !controller.state.value.requestBusy &&
                    controller.state.value.trackedBoxes.any { it.itemId == "local-maqam" && it.confidence >= .90 } }
                controller.setPaused(true) // Keep this one recognition while testing measured local motion.
                val first = controller.state.value.trackedBoxes.single()
                trackedCenters += (first.box.xMin + first.box.xMax) / 2
                var previousAt = first.atElapsedMs
                for (step in 1..4) {
                    trackingOffset = step * 2
                    publishFrame()
                    val expectedCenter = (80.0 + trackingOffset) / 160 * 1000
                    waitFor {
                        controller.state.value.markersVisible && controller.state.value.trackedBoxes.any {
                            it.itemId == "local-maqam" && it.atElapsedMs > previousAt && it.confidence >= .90 &&
                                kotlin.math.abs((it.box.xMin + it.box.xMax) / 2 - expectedCenter) < 10
                        }
                    }
                    val measured = controller.state.value.trackedBoxes.single()
                    previousAt = measured.atElapsedMs
                    trackedCenters += (measured.box.xMin + measured.box.xMax) / 2
                }
                check(trackedCenters.last() - trackedCenters.first() > 35)
                check(!controller.state.value.scenePinned)
                checks += setOf("automatic_textured_acquisition", "pixel_box_moves_with_image", "motion_beyond_old_pose_gate")
                phase = "tracking_moved"
                writeReport()
                delay(3_500) // Bounded screenshot window; no external camera or image transport.
                trackingOccluded = true
                publishFrame()
                waitFor { !controller.state.value.markersVisible && controller.state.value.trackedBoxes.isEmpty() }
                checks += "occlusion_hides_eligible_target"
            }
        }
        controller.endVoiceTurn()
    }

    private suspend fun beginTurn(): String {
        publishFrame()
        val atTap = bitmap!!
        check(controller.beginVoiceTurn())
        val encoded = encodeVisionImage(atTap)
        return encoded.base64
    }

    private suspend fun settleEnabledHud() {
        // A user cannot tap the enabled mic before its composition commits. Direct fixture calls
        // must likewise wait for initial disabled-state speech cleanup to finish before capturing.
        waitFor { hudEnabledCommitted }
        delay(150)
    }

    private suspend fun waitForReply(revision: Long) {
        waitFor { controller.state.value.reply != null && !controller.state.value.requestBusy }
        check(controller.state.value.conversationRevision == revision) { "Expected reply revision $revision" }
    }

    private suspend fun waitFor(condition: () -> Boolean) { while (!condition()) delay(40) }

    private fun publishFrame() {
        val next = if (scenario == "tracking_move") trackingFrame() else syntheticFrame(variant)
        bitmap = next
        controller.onFrame(next, VisionPose(if (scenario == "tracking_move") trackingOffset * 1.5 else variant * 8.0,
            0.0, 810L), SystemClock.elapsedRealtime())
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        stopped = false
        if (::controller.isInitialized) controller.setForeground(true)
    }

    override fun onPause() {
        resumed = false
        if (::controller.isInitialized) controller.setForeground(false)
        super.onPause()
    }

    override fun onStop() {
        stopped = true
        if (::controller.isInitialized) controller.onStopped()
        super.onStop()
    }

    override fun onDestroy() {
        if (::controller.isInitialized) controller.close()
        super.onDestroy()
    }

    private fun writeReport() {
        if (!::report.isInitialized) return
        val state = if (::controller.isInitialized) controller.state.value else null
        report.writeText(JSONObject().put("runId", runId).put("scenario", scenario).put("result", result)
            .put("phase", phase).put("failure", failure).put("requestsStarted", started).put("requestsCompleted", completed)
            .put("paths", JSONArray(paths)).put("checks", JSONArray(checks.toList()))
            .put("askHistoryLengths", JSONArray(askHistories.map { it.length() }))
            .put("trackedCentersNormalized", JSONArray(trackedCenters)).put("trackingPixelsPerStep", 2)
            .put("sceneId", state?.scene?.sceneId ?: JSONObject.NULL).put("pinned", state?.scenePinned ?: false)
            .put("conversationActive", state?.conversationActive ?: false).put("conversationRevision", state?.conversationRevision ?: 0)
            .put("historyEntries", state?.history?.size ?: 0).put("requestBusy", state?.requestBusy ?: false)
            .put("syntheticPixelsOnly", true).put("networkTransport", false)
            .put("speechRecognitionInvoked", false).put("permissionPauseSimulated", scenario == "pinned_permission")
            .toString())
    }

    /** Fixed catalog excerpt copied from server religious-landmarks.mjs; transport/recognition is synthetic. */
    private fun analysis(number: Int): JSONObject {
        val sources = JSONArray().put(JSONObject().put("id", "haramain-maqam")
            .put("title", "مقام إبراهيم — الهيئة العامة للعناية بشؤون الحرمين")
            .put("url", "https://alharamain.gov.sa/public/?page=maqam-ibrahim-ar"))
            .put(JSONObject().put("id", "quran-2-125").put("title", "القرآن الكريم — البقرة ١٢٥")
                .put("url", "https://quran.com/2/125"))
        val devotional = JSONObject().put("id", "maqam-quran-2-125")
            .put("title", "تذكير من القرآن عن مقام إبراهيم")
            .put("arabicText", "وَاتَّخِذُوا مِن مَّقَامِ إِبْرَاهِيمَ مُصَلًّى")
            .put("translation", "المعنى: اتخذوا من مقام إبراهيم مكاناً للصلاة.")
            .put("note", "مقتطف من البقرة ١٢٥، وليس صيغة دعاء خاصة بالمقام. التعرف على الصورة لا يحدد موضع الصلاة ولا يؤكد انتهاء الطواف.")
            .put("sourceIds", JSONArray(listOf("quran-2-125")))
        return JSONObject().put("sceneId", "local-live-$scenario-$number")
            .put("summary", "LOCAL QA — صورة مرسومة ونتيجة محلية، ليست تعرفاً فعلياً.")
            .put("provider", "local-fixture").put("sources", sources)
            .put("items", JSONArray().put(JSONObject().put("id", "local-maqam").put("kind", "landmark")
                .put("landmarkId", "maqam_ibrahim").put("label", "مقام إبراهيم · LOCAL QA")
                .put("description", "LOCAL QA — مقام إبراهيم هو الحجر الذي وقف عليه إبراهيم عليه السلام عند بناء الكعبة مع ابنه إسماعيل.")
                .put("box", JSONArray(if (scenario == "tracking_move") listOf(350, 375, 650, 625) else listOf(250, 250, 750, 750)))
                .put("sourceIds", JSONArray(listOf("haramain-maqam"))).put("devotionals", JSONArray().put(devotional))))
    }

    private fun syntheticFrame(number: Int): Bitmap {
        val source = Bitmap.createBitmap(480, 360, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            drawColor(android.graphics.Color.rgb(30 + number * 20, 65, 55))
            val paint = Paint().apply { color = android.graphics.Color.rgb(180, 154, 91) }
            drawRect(130f + number * 12, 100f, 340f + number * 12, 290f, paint)
            paint.color = android.graphics.Color.WHITE
            paint.textSize = 22f
            drawText("LOCAL QA / SYNTHETIC $number", 20f, 55f, paint)
        }
        return source.copy(Bitmap.Config.ARGB_8888, false)!!.also { source.recycle() }
    }

    private fun trackingFrame(): Bitmap {
        // Three display pixels per analysis pixel; unique 4px details survive the 160x120 sampler.
        val pixels = IntArray(480 * 360) { index ->
            val localX = index % 480 / 3 - 60 - trackingOffset
            val localY = index / 480 / 3 - 42
            val value = if (!trackingOccluded && localX in 0 until 40 && localY in 0 until 36) {
                val hash = ((localX / 4) * 73856093) xor ((localY / 4) * 19349663) xor 83492791
                40 + (hash ushr 3) % 180
            } else 24
            android.graphics.Color.rgb(value, value, value)
        }
        return Bitmap.createBitmap(pixels, 480, 360, Bitmap.Config.ARGB_8888)
    }

    private companion object { const val FIXTURE_TOKEN = "local-vision-delay-fixture-not-a-real-token" }
}
