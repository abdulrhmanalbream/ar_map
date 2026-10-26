package com.sarab.vision

import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.sarab.vision.core.GuidanceMode
import com.sarab.vision.core.VoiceState
import com.sarab.vision.core.nextVoiceCue
import com.sarab.vision.core.navigationVoiceEligible
import com.sarab.vision.core.voiceCueAr
import com.sarab.vision.diagnostics.DiagnosticLog
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Actual packaged recordings, offline emulator only; no photos, names, tokens, network or STT. */
class NavigationVoiceFixtureActivity : ComponentActivity() {
    private var voice: VoiceGuide? = null
    private var awaitingPause = false
    private var pauseSeen = false
    private var resumedAfterPause = false
    private var reportFile: File? = null
    private var report: JSONObject? = null
    private val checks = linkedSetOf<String>()
    private val snapshots = JSONArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val emulator = Build.HARDWARE in setOf("ranchu", "goldfish") &&
            (Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val offline = Settings.Global.getInt(contentResolver, "airplane_mode_on", 0) == 1 &&
            Settings.Global.getInt(contentResolver, "wifi_on", 1) == 0 &&
            Settings.Global.getInt(contentResolver, "mobile_data", 1) == 0
        val runId = intent.getStringExtra("run_id").orEmpty()
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0 || !emulator || !offline ||
            !intent.getBooleanExtra("allow_fixture", false) || !runId.matches(Regex("[A-Za-z0-9_-]{1,64}")) ||
            savedInstanceState != null) { finish(); return }
        DiagnosticLog.initialize(applicationContext)
        val label = TextView(this).apply { text = "Navigation voice validation: RUNNING" }
        setContentView(label)
        reportFile = File(filesDir, "navigation-voice-fixture.json")
        report = JSONObject().put("runId", runId).put("result", "RUNNING").put("phase", "playback")
            .put("networkRequests", 0).put("offline", true).put("checks", JSONArray()).put("snapshots", snapshots)
        val prefs = getSharedPreferences("smart_mutawwif_audio", MODE_PRIVATE)
        val hadMutePreference = prefs.contains("muted")
        val previousMute = prefs.getBoolean("muted", false)
        prefs.edit().putBoolean("muted", false).commit()
        val guide = VoiceGuide(applicationContext).also { voice = it }
        lifecycleScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            var failure = ""
            try {
                saveReport()
                withTimeout(120_000) {
                    // These real files total7.68s, longer than the normal6s policy gap.
                    // A changed sensor direction must not cut off the spoken distance.
                    val longMode = GuidanceMode.Compass(1300.0, 0.0, 30.0)
                    val followingMode = GuidanceMode.Compass(1300.0, 0.0, 90.0)
                    val beforeLong = guide.playbackSnapshot()
                    check(navigationVoiceEligible(foreground = true, recognitionMode = false, hasTarget = true,
                        eyeSource = false, glassesConnected = false, muted = guide.isMuted))
                    guide.speakGuidance(longMode, "")
                    waitFor { guide.playbackSnapshot().startedClips == beforeLong.startedClips + 1 }
                    checks += "phone_navigation_without_glasses_starts_packaged_clip"
                    val longState = VoiceState(voiceCueAr(longMode, ""), 10_000)
                    delay(6_100)
                    check(guide.isSpeaking)
                    val deferred = nextVoiceCue(longState, voiceCueAr(followingMode, ""), false, 16_100,
                        playbackBusy = guide.isSpeaking)
                    deferred.second?.let { guide.speakGuidance(followingMode, "") }
                    check(deferred.first == longState && deferred.second == null)
                    check(guide.playbackSnapshot().activeClipId == "nav_ar_distance_1300")
                    waitFor { guide.playbackSnapshot().completedClips == beforeLong.completedClips + 2 && idle(guide) }
                    check(guide.playbackSnapshot().startedClips == beforeLong.startedClips + 2)
                    check(guide.playbackSnapshot().cancelledClips == beforeLong.cancelledClips)
                    check(guide.playbackSnapshot().failedClips == 0)
                    checks += "normal_sensor_tick_after6s_preserves_complete_direction_and_distance"
                    capture("long_phrase")

                    val beforeUrgent = guide.playbackSnapshot()
                    guide.speakGuidance(compass(90.0), "")
                    waitFor { guide.playbackSnapshot().startedClips == beforeUrgent.startedClips + 1 }
                    val urgentMode = GuidanceMode.Arrived(0.0)
                    val urgent = nextVoiceCue(VoiceState(voiceCueAr(compass(90.0), ""), 10_000),
                        voiceCueAr(urgentMode, ""), true, 10_500, playbackBusy = guide.isSpeaking)
                    check(urgent.second != null)
                    guide.speakGuidance(urgentMode, "")
                    waitFor { guide.playbackSnapshot().completedClips == beforeUrgent.completedClips + 1 && idle(guide) }
                    check(guide.playbackSnapshot().startedClips == beforeUrgent.startedClips + 2)
                    check(guide.playbackSnapshot().cancelledClips == beforeUrgent.cancelledClips + 1)
                    checks += "urgent_arrival_interrupts_busy_phrase_without_old_pending_distance"
                    capture("urgent")

                    for ((id, angle) in listOf("right" to 90.0, "left" to -90.0, "forward" to 0.0)) {
                        val before = guide.playbackSnapshot()
                        guide.speakGuidance(compass(angle), "")
                        waitFor { guide.playbackSnapshot().activeClipId == "nav_ar_$id" &&
                            guide.playbackSnapshot().startedClips == before.startedClips + 1 }
                        check(guide.playbackSnapshot().queuedSegments == 1)
                        waitFor { guide.playbackSnapshot().activeClipId == "nav_ar_distance_150" }
                        waitFor { guide.playbackSnapshot().completedClips == before.completedClips + 2 && idle(guide) }
                        check(guide.playbackSnapshot().failedClips == 0)
                        check(guide.playbackSnapshot().startedClips == before.startedClips + 2)
                        checks += "${id}_and_distance_completed"
                        capture(id)
                    }

                    var before = guide.playbackSnapshot()
                    guide.speakGuidance(compass(90.0), "")
                    waitFor { guide.playbackSnapshot().startedClips == before.startedClips + 1 }
                    val rightGeneration = guide.playbackSnapshot().generation
                    guide.speakGuidance(compass(-90.0), "")
                    check(guide.playbackSnapshot().generation > rightGeneration)
                    check(guide.playbackSnapshot().cancelledClips == before.cancelledClips + 1)
                    waitFor { guide.playbackSnapshot().completedClips == before.completedClips + 2 && idle(guide) }
                    check(guide.playbackSnapshot().startedClips == before.startedClips + 3)
                    checks += "new_cue_flushes_old_clip_and_pending_distance"
                    capture("new_cue")

                    before = guide.playbackSnapshot()
                    guide.speakGuidance(compass(0.0), "")
                    waitFor { guide.playbackSnapshot().startedClips == before.startedClips + 1 }
                    prefs.edit().putBoolean("muted", true).commit()
                    waitFor { idle(guide) }
                    val mutedSnapshot = guide.playbackSnapshot()
                    check(mutedSnapshot.muted && mutedSnapshot.cancelledClips == before.cancelledClips + 1)
                    val beforeMutedPolicy = VoiceState("previous completed instruction", 10_000)
                    var afterMutedPolicy = beforeMutedPolicy
                    if (navigationVoiceEligible(foreground = true, recognitionMode = false, hasTarget = true,
                            eyeSource = false, glassesConnected = false, muted = guide.isMuted)) {
                        val accepted = nextVoiceCue(beforeMutedPolicy, voiceCueAr(compass(90.0), ""), false,
                            20_000, playbackBusy = guide.isSpeaking)
                        afterMutedPolicy = accepted.first
                        accepted.second?.let { guide.speakGuidance(compass(90.0), "") }
                    }
                    check(afterMutedPolicy == beforeMutedPolicy)
                    check(guide.playbackSnapshot().startedClips == mutedSnapshot.startedClips)
                    checks += "muted_automatic_cue_does_not_consume_voice_policy_state"
                    guide.speakGuidance(compass(90.0), "")
                    delay(200)
                    check(guide.playbackSnapshot().startedClips == mutedSnapshot.startedClips)
                    checks += "mute_flushes_active_and_pending_and_blocks_new_cue"
                    prefs.edit().putBoolean("muted", false).commit()
                    delay(400)
                    check(idle(guide) && guide.playbackSnapshot().startedClips == mutedSnapshot.startedClips)
                    checks += "unmute_does_not_replay"
                    capture("mute")

                    before = guide.playbackSnapshot()
                    guide.speakGuidance(compass(-90.0), "")
                    waitFor { guide.playbackSnapshot().startedClips == before.startedClips + 1 }
                    guide.stop()
                    check(idle(guide))
                    val stoppedStarts = guide.playbackSnapshot().startedClips
                    delay(400)
                    check(guide.playbackSnapshot().startedClips == stoppedStarts && idle(guide))
                    checks += "stop_flushes_active_and_pending"
                    capture("stop")

                    before = guide.playbackSnapshot()
                    guide.speakGuidance(compass(0.0), "")
                    waitFor { guide.playbackSnapshot().startedClips == before.startedClips + 1 }
                    val pauseStarts = guide.playbackSnapshot().startedClips
                    awaitingPause = true
                    report!!.put("phase", "PAUSING")
                    saveReport()
                    check(moveTaskToBack(true))
                    withTimeout(45_000) { while (!pauseSeen || !resumedAfterPause) delay(50) }
                    awaitingPause = false
                    check(idle(guide) && guide.playbackSnapshot().startedClips == pauseStarts)
                    checks += "actual_on_pause_flushes_active_and_pending"
                    checks += "resume_does_not_replay"
                    capture("resume")

                    before = guide.playbackSnapshot()
                    guide.speakGuidance(compass(90.0), "")
                    waitFor { guide.playbackSnapshot().startedClips == before.startedClips + 1 }
                    guide.shutdown()
                    val closed = guide.playbackSnapshot()
                    check(closed.closed && idle(guide))
                    check(closed.cancelledClips == before.cancelledClips + 1)
                    guide.speakGuidance(compass(-90.0), "")
                    delay(400)
                    check(guide.playbackSnapshot().startedClips == closed.startedClips && idle(guide))
                    checks += "shutdown_flushes_active_and_pending_and_blocks_new_cue"
                    capture("shutdown")
                    check(guide.playbackSnapshot().failedClips == 0)
                }
            } catch (_: CancellationException) { failure = "CANCELLED_OR_TIMEOUT" }
            catch (_: Exception) { failure = "CHECK_FAILED" }
            finally {
                guide.shutdown()
                if (hadMutePreference) prefs.edit().putBoolean("muted", previousMute).commit()
                else prefs.edit().remove("muted").commit()
            }
            report!!.put("result", if (failure.isEmpty()) "PASS" else "FAIL").put("phase", "complete")
                .put("error", failure).put("checks", JSONArray(checks.toList()))
                .put("elapsedMs", SystemClock.elapsedRealtime() - startedAt)
            capture("final")
            withContext(NonCancellable) { saveReport() }
            label.text = "Navigation voice validation: ${report!!.getString("result")}"
        }
    }

    override fun onResume() {
        super.onResume()
        if (pauseSeen) resumedAfterPause = true
    }

    override fun onPause() {
        voice?.stop()
        if (awaitingPause) {
            pauseSeen = true
            capture("paused")
            report?.put("phase", "AWAITING_RESUME")
            lifecycleScope.launch { saveReport() }
        }
        super.onPause()
    }

    override fun onDestroy() { voice?.shutdown(); super.onDestroy() }

    private fun compass(degrees: Double) = GuidanceMode.Compass(163.0, 0.0, degrees)
    private fun idle(guide: VoiceGuide) = guide.playbackSnapshot().let {
        it.activeClipId == null && it.queuedSegments == 0 && !it.textActive
    }
    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(25_000) { while (!condition()) delay(20) }

    private fun capture(stage: String) {
        val state = voice?.playbackSnapshot() ?: return
        snapshots.put(JSONObject().put("stage", stage).put("activeClipId", state.activeClipId ?: JSONObject.NULL)
            .put("queuedSegments", state.queuedSegments).put("generation", state.generation)
            .put("startedClips", state.startedClips).put("completedClips", state.completedClips)
            .put("cancelledClips", state.cancelledClips).put("failedClips", state.failedClips)
            .put("textActive", state.textActive).put("muted", state.muted).put("closed", state.closed))
    }

    private suspend fun saveReport() {
        report?.put("checks", JSONArray(checks.toList()))
        val text = report?.toString(2) ?: return
        withContext(Dispatchers.IO) { reportFile?.writeText(text) }
    }
}
