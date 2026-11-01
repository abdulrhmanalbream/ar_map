package com.sarab.vision.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.sarab.vision.diagnostics.DiagnosticLog
import com.sarab.vision.platform.AssistantTurn
import com.sarab.vision.platform.PlatformClient
import com.sarab.vision.platform.PlatformStore
import com.sarab.vision.platform.assistantRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/** Same six-turn memory the vision conversation keeps; older context only confuses replies. */
private const val TEXT_HISTORY_TURNS = 6

data class TextAssistantState(
    val busy: Boolean = false,
    val speaking: Boolean = false,
    val reply: String? = null,
    val revision: Long = 0L,
    val notice: String? = null,
)

/**
 * The hands-free path when no camera snapshot applies (navigation, or the
 * companion without image consent): the text-only /assistant endpoint, spoken
 * back with the installed TTS voice. It never acts on a proposed destination;
 * the server marks those as requiring confirmation and the phone UI owns that.
 */
class TextAssistantVoice(context: Context, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext
    private val store = PlatformStore.get(appContext)
    private val client = PlatformClient(store)
    private val history = ArrayDeque<AssistantTurn>()
    private val mutable = MutableStateFlow(TextAssistantState())
    val state: StateFlow<TextAssistantState> = mutable.asStateFlow()
    private var job: Job? = null
    private var ttsReady = false
    private val tts: TextToSpeech = TextToSpeech(appContext) { status -> ttsReady = status == TextToSpeech.SUCCESS }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { mutable.value = mutable.value.copy(speaking = true) }
            override fun onDone(utteranceId: String?) { mutable.value = mutable.value.copy(speaking = false) }
            @Deprecated("Required override on older APIs")
            override fun onError(utteranceId: String?) { mutable.value = mutable.value.copy(speaking = false) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                mutable.value = mutable.value.copy(speaking = false)
            }
        })
    }

    fun ask(question: String, language: String, muted: Boolean, notEnrolledMessage: String) {
        if (!store.snapshot.enrolled) {
            say(notEnrolledMessage, language, muted)
            mutable.value = mutable.value.copy(reply = notEnrolledMessage, revision = mutable.value.revision + 1)
            return
        }
        job?.cancel()
        mutable.value = mutable.value.copy(busy = true, notice = null)
        val previous = history.toList()
        job = scope.launch {
            try {
                val result = client.request("/assistant", assistantRequest(appContext, question, language, previous))
                val reply = result.getString("reply").take(5000)
                history.addLast(AssistantTurn("user", question))
                history.addLast(AssistantTurn("assistant", reply))
                while (history.size > TEXT_HISTORY_TURNS) history.removeFirst()
                mutable.value = mutable.value.copy(busy = false, reply = reply, revision = mutable.value.revision + 1)
                say(reply, result.optString("language", language), muted)
                DiagnosticLog.record("HandsFree", "text reply provider=${result.optString("provider")}")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                // Only the failure class is logged; the question and any reply text never are.
                DiagnosticLog.record("HandsFree", "text request failed ${failure.javaClass.simpleName}")
                mutable.value = mutable.value.copy(busy = false, notice = failure.message)
            }
        }
    }

    private fun say(text: String, language: String, muted: Boolean) {
        if (muted || !ttsReady || text.isBlank()) return
        val supported = tts.setLanguage(Locale.forLanguageTag(when (language) {
            "ar" -> "ar-SA"; "ur" -> "ur-PK"; "id" -> "id-ID"; "tr" -> "tr-TR"; else -> language
        }))
        if (supported >= TextToSpeech.LANG_AVAILABLE) tts.speak(text.take(3900), TextToSpeech.QUEUE_FLUSH, null, "hands_free_reply")
    }

    fun stopSpeaking() { tts.stop(); mutable.value = mutable.value.copy(speaking = false) }

    fun close() {
        job?.cancel()
        tts.stop()
        tts.shutdown()
    }
}
