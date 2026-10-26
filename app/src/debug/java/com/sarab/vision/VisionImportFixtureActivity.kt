package com.sarab.vision

import android.content.pm.ApplicationInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
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
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.lifecycleScope
import com.sarab.vision.diagnostics.DiagnosticLog
import com.sarab.vision.ritual.RitualCounterStore
import com.sarab.vision.ritual.RitualWatchState
import com.sarab.vision.ui.HedayahTheme
import com.sarab.vision.ui.ReligiousCameraHud
import com.sarab.vision.vision.EyeVisionController
import com.sarab.vision.vision.VisionError
import com.sarab.vision.vision.VisionPose
import com.sarab.vision.vision.VisionRequestFailure
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

/** Real controller/encoder, synthetic pixels and in-memory transport; no camera, microphone or AI network. */
class VisionImportFixtureActivity : ComponentActivity() {
    private lateinit var controller: EyeVisionController
    private lateinit var report: File
    private lateinit var label: TextView
    private var runId = ""
    private var scenario = ""
    private var phase = "starting"
    private var result = "RUNNING"
    private var failure = ""
    private var resumed = false
    private var stopped = false
    private var didStop = false
    private var started = 0
    private var completed = 0
    private var nextFailure: VisionRequestFailure? = null
    private var uiDraftMatched = false
    private val checks = linkedSetOf<String>()
    private val images = mutableListOf<String>() // Memory only; never written to diagnostics/report.
    private val languages = mutableListOf<String>()
    private val paths = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val emulator = Build.HARDWARE in setOf("ranchu", "goldfish") &&
            (Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val offline = Settings.Global.getInt(contentResolver, "airplane_mode_on", 0) == 1 &&
            Settings.Global.getInt(contentResolver, "wifi_on", 1) == 0 &&
            Settings.Global.getInt(contentResolver, "mobile_data", 1) == 0
        runId = intent.getStringExtra("run_id").orEmpty()
        scenario = intent.getStringExtra("scenario").orEmpty()
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0 || !emulator || !offline ||
            !intent.getBooleanExtra("allow_fixture", false) || runId.isBlank() ||
            scenario !in setOf("photo_context", "photo_background", "photo_retry", "photo_cooldown_ui")) {
            finish(); return
        }
        report = File(filesDir, "vision-import-$scenario.json")
        val prefs = getSharedPreferences("sarab_platform", MODE_PRIVATE)
        val token = prefs.getString("token", "").orEmpty()
        if (token.isNotBlank() && token != FIXTURE_TOKEN) {
            result = "FAIL"; failure = "Existing non-fixture enrollment; no preferences changed"
            writeReport(); finish(); return
        }
        check(prefs.edit().putString("token", FIXTURE_TOKEN).putString("server", "https://127.0.0.1:1")
            .putString("name", "LOCAL QA").putString("group", "LOCAL QA — no server")
            .putString("language", "ar").putBoolean("sharing", false).commit())
        check(getSharedPreferences("smart_mutawwif_vision", MODE_PRIVATE).edit().putString("language", "ar").commit())
        if (scenario == "photo_cooldown_ui") check(getSharedPreferences("smart_mutawwif_audio", MODE_PRIVATE)
            .edit().putBoolean("muted", true).commit())
        DiagnosticLog.initialize(this)
        controller = EyeVisionController(this, lifecycleScope, requestOverride = { path, body, server, requestToken ->
            check(server == "https://127.0.0.1:1" && requestToken == FIXTURE_TOKEN)
            paths += path
            images += body.getJSONObject("image").getString("data")
            languages += body.getString("language")
            if (scenario == "photo_cooldown_ui" && path == "/vision/ask")
                uiDraftMatched = body.getString("question") == "LOCAL_DRAFT"
            val number = ++started
            val injected = nextFailure.also { nextFailure = null }
            writeReport()
            withContext(NonCancellable) {
                delay(2_000)
                completed++
                writeReport()
                if (injected != null) throw injected
                if (path == "/vision/ask") JSONObject().put("reply", "LOCAL QA synthetic photo answer $number")
                    .put("provider", "local-fixture").put("sources", JSONArray())
                else JSONObject().put("sceneId", "local-import-$number").put("summary", "LOCAL QA synthetic landmark")
                    .put("provider", "local-fixture").put("sources", JSONArray())
                    .put("items", JSONArray().put(JSONObject().put("id", "synthetic-marker").put("kind", "object")
                        .put("label", "LOCAL QA").put("description", "Synthetic painted rectangle")
                        .put("box", JSONArray(listOf(200, 200, 800, 800))).put("sourceIds", JSONArray())))
            }
        })
        if (scenario == "photo_cooldown_ui") renderCooldownHud() else {
        label = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(16, 44, 37)); textSize = 21f
            setPadding(32, 60, 32, 32)
        }
        setContentView(label)
        }
        lifecycleScope.launch { controller.state.collect { writeReport() } }
        lifecycleScope.launch {
            try {
                withTimeout(42_000) { runScenario() }
                result = "PASS"; phase = "complete"
            } catch (problem: Exception) {
                result = "FAIL"; failure = "${problem.javaClass.simpleName}: ${problem.message.orEmpty().take(300)}"
            }
            writeReport()
        }
    }

    private suspend fun runScenario() {
        waitFor { resumed }
        controller.setEnabled(true)
        controller.setCameraAvailable(false)
        val source = painted(0)
        val expected = encodeVisionImage(source).base64
        check(controller.selectImportedImage(source))
        val imported = controller.state.value.importedImage!!
        check(imported !== source && !imported.isMutable && imported.width <= 960 && imported.height <= 960)
        val originalPixel = imported.getPixel(0, 0)
        source.eraseColor(Color.MAGENTA)
        source.recycle()
        check(imported.getPixel(0, 0) == originalPixel)
        delay(900)
        check(started == 0) { "Selecting a photo must not automatically upload it" }
        checks += setOf("owned_immutable_bounded_copy", "caller_can_release_original", "select_has_no_upload")
        when (scenario) {
            "photo_cooldown_ui" -> {
                phase = "ready_for_draft"; writeReport()
                waitFor { started == 1 }
                waitFor { controller.state.value.error == VisionError.UPLOAD_TIMEOUT && !controller.state.value.requestBusy }
                check(paths == listOf("/vision/analyze"))
                val deadline = controller.state.value.retryAtElapsedMs
                // Read actual Compose semantics while the short countdown is active.
                // uiautomator's idle wait can otherwise postpone its XML until expiry.
                waitFor { cooldownUiVerified() }
                checks += setOf("actual_hud_countdown_visible", "draft_retained_during_wait", "send_disabled_during_wait")
                phase = "cooldown_visible"; writeReport()
                waitFor { stopped }
                check(SystemClock.elapsedRealtime() < deadline) { "HOME must interrupt the active countdown" }
                check(controller.state.value.importedImage === imported && started == 1)
                phase = "await_resume"; writeReport()
                waitFor { resumed && SystemClock.elapsedRealtime() >= deadline }
                check(started == 1) { "Cooldown expiry/background must never replay a request" }
                checks += "deadline_and_lifecycle_do_not_resend"
                phase = "ready_manual_retry"; writeReport()
                waitFor { started == 2 }
                check(paths.last() == "/vision/ask" && uiDraftMatched) { "Manual send must use the retained UI draft" }
                waitForReply()
                checks += setOf("actual_hud_manual_send_uses_retained_draft", "manual_retry_recovers")
                phase = "await_live_return"; writeReport()
                waitFor { !controller.state.value.isImportedImage }
                check(controller.state.value.scene == null && controller.state.value.reply == null && !controller.state.value.requestBusy)
                checks += "return_to_live_clears_imported_conversation"
            }
            "photo_context" -> {
                check(controller.beginVoiceTurn())
                controller.askLive("LOCAL QA first question without prior recognition")
                waitForReply()
                check(images.single() == expected && controller.state.value.scene == null)
                check(controller.state.value.conversationActive && !controller.state.value.scenePinned)
                checks += "ask_without_camera_or_recognition_uses_selected_photo"
                controller.endVoiceTurn()
                controller.analyzeNow()
                waitFor { started == 2 }
                controller.setCameraAvailable(true)
                controller.onFrame(painted(1), VisionPose(20.0, 4.0, 19))
                controller.setCameraAvailable(false)
                waitFor { controller.state.value.scene != null && !controller.state.value.requestBusy }
                check(images.last() == expected && controller.state.value.scene!!.previewBitmap === imported)
                check(controller.state.value.scene!!.pose == null && !controller.state.value.markersVisible &&
                    controller.state.value.trackedBoxes.isEmpty())
                checks += setOf("camera_change_does_not_replace_import", "imported_boxes_are_not_world_tracking")
                controller.setLanguage("en")
                check(controller.state.value.scene == null && controller.state.value.importedImage === imported)
                controller.analyzeNow()
                waitFor { started == 3 }
                controller.returnToLive()
                waitFor { completed == 3 && !controller.state.value.requestBusy }
                check(controller.state.value.importedImage == null && controller.state.value.scene == null &&
                    controller.state.value.reply == null && !controller.state.value.scenePinned)
                checks += "return_to_live_discards_late_photo_analysis"
                check(controller.selectImportedImage(painted(2)))
                val second = controller.state.value.importedImage!!
                check(controller.beginVoiceTurn())
                controller.askLive("LOCAL QA old-language question")
                waitFor { started == 4 }
                controller.setLanguage("ar")
                waitFor { completed == 4 && !controller.state.value.requestBusy }
                check(controller.state.value.reply == null && controller.state.value.importedImage === second)
                check(controller.beginVoiceTurn())
                controller.askLive("LOCAL QA current-language question")
                waitForReply()
                check(languages.last() == "ar" && images.last() != expected)
                checks += setOf("language_discards_old_reply_retains_photo", "replacement_question_uses_new_photo")
                controller.endVoiceTurn()
                controller.close()
                check(controller.state.value.importedImage == null && !controller.state.value.enabled)
                checks += "close_releases_photo_reference_and_consent"
            }
            "photo_background" -> {
                check(controller.beginVoiceTurn())
                controller.askLive("LOCAL QA pending background question")
                waitFor { started == 1 }
                phase = "await_background"; writeReport()
                waitFor { stopped }
                waitFor { completed == 1 && !controller.state.value.requestBusy }
                check(controller.state.value.reply == null && controller.state.value.importedImage === imported)
                checks += "actual_on_stop_discards_request_but_retains_local_photo"
                phase = "await_resume"; writeReport()
                waitFor { resumed }
                delay(900)
                check(started == 1) { "Resume must not automatically upload imported image" }
                check(controller.beginVoiceTurn())
                controller.askLive("LOCAL QA explicit resumed question")
                waitForReply()
                check(images.last() == expected)
                controller.endVoiceTurn()
                controller.clearConversation()
                check(controller.beginVoiceTurn())
                controller.askLive("LOCAL QA pending consent revoke")
                waitFor { started == 3 }
                controller.setEnabled(false)
                waitFor { completed == 3 && !controller.state.value.requestBusy }
                check(controller.state.value.reply == null && controller.state.value.importedImage === imported)
                check(!controller.beginVoiceTurn() && controller.state.value.error == VisionError.CONSENT_REQUIRED)
                controller.setEnabled(true)
                check(controller.beginVoiceTurn())
                controller.askLive("LOCAL QA consent recovery")
                waitForReply()
                checks += setOf("resume_has_no_upload", "explicit_resume_question_uses_same_photo",
                    "revoked_consent_discards_reply", "new_consent_explicit_question_recovers")
            }
            "photo_retry" -> {
                nextFailure = VisionRequestFailure(VisionError.SERVICE_BUSY, true, 5_000)
                controller.analyzeNow()
                waitFor { started == 1 }
                check(controller.beginVoiceTurn())
                controller.askLive("LOCAL QA queued question behind busy response")
                waitFor { controller.state.value.error == VisionError.SERVICE_BUSY && !controller.state.value.requestBusy }
                check(!controller.state.value.asking && !controller.state.value.voiceBusy)
                repeat(12) {
                    controller.analyzeNow()
                    controller.askLive("LOCAL QA repeated manual attempt")
                    delay(30)
                }
                check(started == 1) { "Queued/manual requests must obey the shared provider cooldown" }
                controller.setForeground(false); controller.onStopped(); controller.setForeground(true)
                controller.analyzeNow()
                check(started == 1 && controller.state.value.importedImage === imported)
                checks += setOf("busy_error_is_distinct", "queued_question_respects_failed_analysis_retry",
                    "manual_cannot_bypass_retry", "lifecycle_cannot_bypass_retry")
                waitFor { SystemClock.elapsedRealtime() >= controller.state.value.retryAtElapsedMs }
                controller.analyzeNow()
                waitFor { controller.state.value.scene != null && !controller.state.value.requestBusy }
                check(started == 2 && controller.state.value.error == null && controller.state.value.retryAtElapsedMs == 0L)
                checks += "explicit_retry_after_advice_recovers_and_resets_budget"
            }
        }
        controller.endVoiceTurn()
    }

    private suspend fun waitForReply() { waitFor { controller.state.value.reply != null && !controller.state.value.requestBusy } }
    private suspend fun waitFor(predicate: () -> Boolean) { while (!predicate()) delay(40) }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun renderCooldownHud() {
        val counterStore = RitualCounterStore.get(this)
        setContent {
            val state by controller.state.collectAsState()
            val counter by counterStore.state.collectAsState()
            HedayahTheme(camera = true) {
                Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    state.importedImage?.let { Image(it.asImageBitmap(), "LOCAL QA synthetic photo",
                        Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    ReligiousCameraHud(controller, state, enrolled = true, cameraLive = false,
                        counterStore = counterStore, counterState = counter, watch = RitualWatchState(),
                        onConnect = {}, onNavigation = {}, onCameraSettings = {}, phoneCamera = true,
                        motionAvailable = false, onReturnToCamera = controller::returnToLive)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (scenario != "photo_cooldown_ui" || !intent.getBooleanExtra("allow_fixture", false) ||
            intent.getStringExtra("run_id") != runId) return
        val command = intent.getStringExtra("fixture_command")
        lifecycleScope.launch {
        waitFor { resumed }
        when (command) {
            "fail_once" -> if (phase == "ready_for_draft" && started == 0) {
                nextFailure = VisionRequestFailure(VisionError.UPLOAD_TIMEOUT, true, 3_500)
                controller.analyzeNow()
            }
            "return_live" -> if (phase == "await_live_return") controller.returnToLive()
        }
        }
    }

    private fun cooldownUiVerified(): Boolean {
        fun composeView(view: View): View? {
            if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
            if (view is ViewGroup) for (index in 0 until view.childCount)
                composeView(view.getChildAt(index))?.let { return it }
            return null
        }
        val view = composeView(window.decorView) ?: return false
        val getter = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 } ?: return false
        val owner = getter.invoke(view) as? SemanticsOwner ?: return false
        val found = mutableMapOf<String, SemanticsNode>()
        fun visit(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.TestTag)?.let { found[it] = node }
            node.children.forEach(::visit)
        }
        visit(owner.unmergedRootSemanticsNode)
        val wait = found["vision-retry-wait"] ?: return false
        val field = found["vision-live-question"] ?: return false
        val send = found["vision-live-send"] ?: return false
        return listOf(wait, field, send).all { it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0 } &&
            wait.config.getOrNull(SemanticsProperties.Text)?.any { it.text.isNotBlank() } == true &&
            field.config.getOrNull(SemanticsProperties.EditableText)?.text == "LOCAL_DRAFT" &&
            send.config.contains(SemanticsProperties.Disabled)
    }

    override fun onResume() {
        super.onResume(); resumed = true; stopped = false
        if (::controller.isInitialized) controller.setForeground(true)
    }
    override fun onPause() {
        resumed = false
        if (::controller.isInitialized) controller.setForeground(false)
        super.onPause()
    }
    override fun onStop() {
        stopped = true
        didStop = true
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
            .put("phase", phase).put("failure", failure).put("checks", JSONArray(checks.toList()))
            .put("requestsStarted", started).put("requestsCompleted", completed).put("paths", JSONArray(paths))
            .put("imported", state?.isImportedImage ?: false).put("requestBusy", state?.requestBusy ?: false)
            .put("errorCode", state?.error?.name ?: JSONObject.NULL).put("syntheticPixelsOnly", true)
            .put("retryRemainingMs", (state?.retryAtElapsedMs?.minus(SystemClock.elapsedRealtime()) ?: 0L).coerceAtLeast(0L))
            .put("uiDraftMatched", uiDraftMatched)
            .put("networkTransport", false).put("speechRecognitionInvoked", false)
            .put("actualActivityStop", didStop).toString())
        if (::label.isInitialized) label.text = "LOCAL QA · SYNTHETIC PHOTO\nNo network / camera / microphone\n\n$scenario\n$phase\n$result\n\n${checks.joinToString("\n")}\n$failure"
    }

    private fun painted(variant: Int): Bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).also {
        Canvas(it).apply {
            drawColor(Color.rgb(20 + variant * 45, 70, 55))
            val paint = Paint().apply { color = Color.rgb(180, 154, 91) }
            drawRect(60f + variant * 12, 45f, 250f, 205f, paint)
            paint.color = Color.WHITE; paint.textSize = 17f
            drawText("LOCAL QA SYNTHETIC $variant", 10f, 25f, paint)
        }
    }

    private companion object { const val FIXTURE_TOKEN = "local-vision-delay-fixture-not-a-real-token" }
}
