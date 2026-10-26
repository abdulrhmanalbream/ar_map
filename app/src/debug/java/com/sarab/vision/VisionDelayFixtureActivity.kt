package com.sarab.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import com.sarab.vision.vision.visionFrameSignature
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
 * DUMP-protected, explicitly enabled emulator QA. Uses production controller/encoder/UI with
 * synthetic pixels and a delayed in-memory transport. No URL connection or real camera is used.
 */
class VisionDelayFixtureActivity : ComponentActivity() {
    private lateinit var controller: EyeVisionController
    private lateinit var report: File
    private var scenario = ""
    private var runId = ""
    private var phase = "starting"
    private var result = "RUNNING"
    private var failure = ""
    private var resumed = false
    private var moved = false
    private var started = 0
    private var completed = 0
    private var encoderSignatureVerified = false
    private var backgroundDiscardVerified = false
    private var sourceDiscardVerified = false
    private var capturedImageBase64 = ""
    private var askCapturedImageVerified = false
    private var pauseRestorationVerified = false
    private val publishedScenes = linkedSetOf<String>()
    private var bitmap by mutableStateOf<Bitmap?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val emulator = Build.HARDWARE in setOf("ranchu", "goldfish") &&
            (Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        scenario = intent.getStringExtra("scenario").orEmpty()
        runId = intent.getStringExtra("run_id").orEmpty()
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0 || !emulator || !intent.getBooleanExtra("allow_fixture", false) ||
            scenario !in setOf("manual_move", "manual_signature", "auto_move", "source_revoke", "background_revoke") || runId.isBlank()) {
            finish()
            return
        }
        report = File(filesDir, "vision-delay-$scenario.json")
        // Never replace an existing real enrollment, even on an emulator.
        val prefs = getSharedPreferences("sarab_platform", MODE_PRIVATE)
        val existingToken = prefs.getString("token", "").orEmpty()
        if (existingToken.isNotBlank() && existingToken != FIXTURE_TOKEN) {
            result = "FAIL"
            failure = "Existing non-fixture enrollment; no preferences changed"
            writeReport()
            finish()
            return
        }
        check(prefs.edit().putString("token", FIXTURE_TOKEN).putString("server", "https://127.0.0.1:1")
            .putString("name", "LOCAL QA").putString("group", "LOCAL QA — no server")
            .putString("language", "ar").putBoolean("sharing", false).commit())
        DiagnosticLog.initialize(this)
        controller = EyeVisionController(this, lifecycleScope, requestOverride = { path, body, server, token ->
            check(server == "https://127.0.0.1:1" && token == FIXTURE_TOKEN)
            check(path in setOf("/vision/analyze", "/vision/ask"))
            val payloadImage = body.getJSONObject("image").getString("data")
            if (path == "/vision/analyze") capturedImageBase64 = payloadImage
            else {
                check(capturedImageBase64.isNotBlank() && payloadImage == capturedImageBase64) {
                    "Question must use the captured image, not the changed live frame"
                }
                askCapturedImageVerified = true
            }
            val number = ++started
            writeReport()
            // Deliberately emulate an uncooperative late transport result after cancellation.
            withContext(NonCancellable) {
                delay(2_000)
                completed++
                writeReport()
                if (path == "/vision/ask") JSONObject().put("reply", "LOCAL QA — رد محلي تجريبي")
                    .put("provider", "local-fixture").put("sources", JSONArray())
                else response(number)
            }
        })
        val counterStore = RitualCounterStore.get(this)
        setContent {
            val state by controller.state.collectAsState()
            val counter by counterStore.state.collectAsState()
            HedayahTheme(camera = true) {
                Box(Modifier.fillMaxSize()) {
                    bitmap?.let { Image(it.asImageBitmap(), "LOCAL QA synthetic scene", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    ReligiousCameraHud(controller, state, enrolled = true, cameraLive = true,
                        counterStore = counterStore, counterState = counter, watch = RitualWatchState(),
                        onConnect = {}, onNavigation = {}, onCameraSettings = {},
                        phoneCamera = true, motionAvailable = true)
                    Text("LOCAL QA · صورة مرسومة · بلا شبكة", Modifier.align(Alignment.Center)
                        .background(Color.Black.copy(alpha = .8f)).padding(8.dp), color = Color.White)
                }
            }
        }
        lifecycleScope.launch {
            controller.state.collect { state ->
                state.scene?.sceneId?.let(publishedScenes::add)
                writeReport()
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    val next = syntheticFrame(moved)
                    bitmap = next
                    controller.onFrame(next, VisionPose(if (moved && scenario != "manual_signature") 25.0 else 0.0, 0.0, 800L), SystemClock.elapsedRealtime())
                    delay(100)
                }
            }
        }
        lifecycleScope.launch {
            try {
                withTimeout(22_000) { runScenario() }
                result = "PASS"
                phase = "complete"
            } catch (problem: Exception) {
                result = "FAIL"
                failure = "${problem.javaClass.simpleName}: ${problem.message.orEmpty().take(300)}"
            }
            writeReport()
        }
    }

    private suspend fun runScenario() {
        // Regression: the encoded image keeps the signature of the captured original, including resize.
        val highFrequency = Bitmap.createBitmap(IntArray(1280 * 720) { index ->
            if (((index % 1280) + (index / 1280)) % 2 == 0) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }, 1280, 720, Bitmap.Config.ARGB_8888)
        val encoded = encodeVisionImage(highFrequency)
        check(encoded.preview.width == 960 && encoded.preview.height == 540)
        check(encoded.signature == visionFrameSignature(highFrequency))
        check(encoded.signature != visionFrameSignature(encoded.preview))
        encoderSignatureVerified = true
        highFrequency.recycle()
        encoded.preview.recycle()

        controller.setCameraAvailable(true)
        controller.setEnabled(true)
        controller.setPaused(scenario != "auto_move")
        delay(250)
        if (scenario != "auto_move") controller.analyzeNow()
        waitFor { started == 1 }
        when (scenario) {
            "manual_move", "manual_signature", "auto_move" -> {
                moved = true
                phase = "moved_during_request"
                writeReport()
                waitFor { controller.state.value.scene != null && !controller.state.value.requestBusy }
                val state = controller.state.value
                check(state.scene?.sceneId == "local-$scenario-1")
                check(!state.markersVisible) { "Moved scene must not show live markers" }
                if (scenario.startsWith("manual_")) check(state.scenePinned) { "Manual result must be a pinned snapshot" }
                if (scenario == "manual_move") {
                    phase = "ask_about_capture"
                    writeReport()
                    controller.ask("LOCAL QA fixture question")
                    waitFor { controller.state.value.reply != null && !controller.state.value.requestBusy }
                    check(controller.state.value.scene?.sceneId == "local-$scenario-1")
                    check(controller.state.value.scenePinned && askCapturedImageVerified)
                    // Keep the real result visible for screenshot QA while independent real-controller
                    // probes check both pre-pin choices, background invalidation and explicit unpin.
                    verifyPauseRestoration(wasPaused = false)
                    verifyPauseRestoration(wasPaused = true)
                    pauseRestorationVerified = true
                }
            }
            "source_revoke" -> {
                controller.setEnabled(false)
                controller.setCameraAvailable(false)
                moved = true
                phase = "source_revoked"
                writeReport()
                waitFor { completed == 1 && !controller.state.value.requestBusy }
                check(publishedScenes.isEmpty() && controller.state.value.scene == null)
                check(!controller.state.value.enabled)
                sourceDiscardVerified = true
                recoverAndCapture()
            }
            "background_revoke" -> {
                phase = "await_background"
                writeReport()
                waitFor { !resumed }
                waitFor { completed == 1 && !controller.state.value.requestBusy }
                check(publishedScenes.isEmpty() && controller.state.value.scene == null)
                backgroundDiscardVerified = true
                phase = "await_resume"
                writeReport()
                waitFor { resumed }
                recoverAndCapture()
            }
        }
    }

    private suspend fun recoverAndCapture() {
        phase = "recovery_capture"
        controller.setForeground(true)
        controller.setCameraAvailable(true)
        controller.setEnabled(true)
        controller.setPaused(true)
        writeReport()
        delay(300)
        controller.analyzeNow()
        waitFor { controller.state.value.scene != null && !controller.state.value.requestBusy }
        check(controller.state.value.scene?.sceneId == "local-$scenario-2")
        check(publishedScenes.none { it.endsWith("-1") }) { "Revoked request published after recovery" }
    }

    private suspend fun verifyPauseRestoration(wasPaused: Boolean) {
        val probe = EyeVisionController(this, lifecycleScope, requestOverride = { _, _, _, _ -> response(900) })
        try {
            probe.setForeground(true)
            probe.setCameraAvailable(true)
            probe.setEnabled(true)
            probe.setPaused(wasPaused)
            probe.onFrame(syntheticFrame(false), VisionPose(0.0, 0.0, 901L))
            probe.analyzeNow()
            waitFor { probe.state.value.scene != null && !probe.state.value.requestBusy }
            check(probe.state.value.scenePinned && probe.state.value.paused)
            probe.setForeground(false)
            check(probe.state.value.scene == null && probe.state.value.paused == wasPaused) {
                "Background must restore the pre-pin pause choice: $wasPaused"
            }
            probe.setForeground(true)
            probe.onFrame(syntheticFrame(false), VisionPose(0.0, 0.0, 901L))
            probe.analyzeNow()
            waitFor { probe.state.value.scene != null && !probe.state.value.requestBusy }
            probe.unpinScene()
            check(!probe.state.value.scenePinned && probe.state.value.paused == wasPaused) {
                "Closing a capture must restore the pre-pin pause choice: $wasPaused"
            }
        } finally { probe.close() }
    }

    private suspend fun waitFor(condition: () -> Boolean) {
        while (!condition()) delay(40)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (::controller.isInitialized) controller.setForeground(true)
    }

    override fun onPause() {
        resumed = false
        if (::controller.isInitialized) controller.setForeground(false)
        super.onPause()
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
            .put("sceneId", state?.scene?.sceneId ?: JSONObject.NULL).put("pinned", state?.scenePinned ?: false)
            .put("markersVisible", state?.markersVisible ?: false).put("enabled", state?.enabled ?: false)
            .put("requestBusy", state?.requestBusy ?: false).put("publishedScenes", JSONArray(publishedScenes.toList()))
            .put("encoderSourceSignatureVerified", encoderSignatureVerified).put("sourceDiscardVerified", sourceDiscardVerified)
            .put("backgroundDiscardVerified", backgroundDiscardVerified).put("syntheticPixelsOnly", true)
            .put("askCapturedImageVerified", askCapturedImageVerified)
            .put("pauseRestorationVerified", pauseRestorationVerified)
            .put("networkTransport", false).toString())
    }

    private fun response(number: Int) = JSONObject().put("sceneId", "local-$scenario-$number")
        .put("summary", "LOCAL QA — نتيجة محلية متأخرة لصورة مرسومة. ليست نتيجة المساعد الذكي.")
        .put("provider", "local-fixture").put("sources", JSONArray())
        .put("items", JSONArray().put(JSONObject().put("id", "local-item").put("kind", "object")
            .put("label", "عنصر اختبار محلي").put("description", "هذا وصف تجريبي للصورة الملتقطة قبل تحريك المشهد.")
            .put("box", JSONArray(listOf(250, 250, 750, 750))).put("sourceIds", JSONArray())))

    private fun syntheticFrame(changed: Boolean): Bitmap {
        val mutable = Bitmap.createBitmap(480, 360, Bitmap.Config.ARGB_8888)
        Canvas(mutable).apply {
            drawColor(if (changed) android.graphics.Color.rgb(215, 220, 210) else android.graphics.Color.rgb(30, 65, 55))
            val paint = Paint().apply { color = android.graphics.Color.rgb(180, 154, 91) }
            drawRect(if (changed) 15f else 130f, 100f, if (changed) 160f else 350f, 290f, paint)
            paint.color = android.graphics.Color.WHITE
            paint.textSize = 22f
            drawText("LOCAL QA / SYNTHETIC ONLY", 20f, 55f, paint)
        }
        return mutable.copy(Bitmap.Config.ARGB_8888, false)!!.also { mutable.recycle() }
    }

    private companion object {
        const val FIXTURE_TOKEN = "local-vision-delay-fixture-not-a-real-token"
    }
}
