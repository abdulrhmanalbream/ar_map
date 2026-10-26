package com.sarab.vision.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sarab.vision.vision.EyeVisionState

@Composable
internal fun VisionMicrophoneButton(speech: VisionSpeech, copy: VisionCopy, enabled: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier, idleLabel: String? = null, holographic: Boolean = false) {
    val interactive = enabled || speech.listening || speech.processing || speech.speaking
    Button(onClick = onClick, enabled = interactive,
        colors = if (holographic) ButtonDefaults.buttonColors(
            containerColor = Hedayah.ArPanelRaised, contentColor = Hedayah.ArText,
            disabledContainerColor = Hedayah.ArPanelRaised.copy(alpha = .18f),
            disabledContentColor = Hedayah.ArMuted.copy(alpha = .55f),
        ) else ButtonDefaults.buttonColors(),
        border = if (holographic) BorderStroke(1.dp, Hedayah.ArAccent.copy(alpha = if (interactive) .8f else .32f)) else null,
        modifier = modifier.heightIn(min = 56.dp).testTag("vision-microphone")) {
        val ink = LocalContentColor.current
        Canvas(Modifier.size(24.dp)) {
            val stroke = 2.dp.toPx()
            drawRoundRect(ink, Offset(size.width * .35f, size.height * .08f),
                Size(size.width * .3f, size.height * .5f), CornerRadius(5.dp.toPx()), style = Stroke(stroke))
            drawArc(ink, 0f, 180f, false, Offset(size.width * .18f, size.height * .31f),
                Size(size.width * .64f, size.height * .44f), style = Stroke(stroke, cap = StrokeCap.Round))
            drawLine(ink, Offset(size.width * .5f, size.height * .75f),
                Offset(size.width * .5f, size.height * .93f), stroke, StrokeCap.Round)
        }
        Spacer(Modifier.width(8.dp))
        Text(when {
            speech.listening -> copy.finishSpeaking
            speech.processing -> copy.close
            speech.permissionPending -> copy.microphonePermission
            speech.speaking -> copy.voice
            else -> idleLabel ?: copy.talk
        }, maxLines = 2)
    }
}

/** A bounded overlay keeps the camera visible and the mic outside the scrolling content. */
@Composable
internal fun VisionConversationPanel(state: EyeVisionState, speech: VisionSpeech, copy: VisionCopy,
    status: String, pendingQuestion: String, canTalk: Boolean, onTalk: () -> Unit,
    onAsk: (String) -> Unit, onCloseConversation: () -> Unit, modifier: Modifier = Modifier,
    retrySeconds: Long = 0,
    actions: @Composable RowScope.() -> Unit) {
    var typing by remember(state.language) { mutableStateOf(false) }
    var question by remember(state.language) { mutableStateOf("") }
    var showHistory by remember(state.language) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val bodyScroll = rememberScrollState()
    val maxHeight = (LocalConfiguration.current.screenHeightDp * .43f).dp.coerceIn(184.dp, 380.dp)
    fun submit() {
        if (question.isBlank() || state.asking || !state.enabled || retrySeconds > 0) return
        speech.stop()
        onAsk(question.trim())
        question = ""
        typing = false
        focus.clearFocus()
    }
    // Open the answer at its beginning, not at the playback notice below a long reply.
    LaunchedEffect(state.conversationRevision, pendingQuestion, typing) { bodyScroll.scrollTo(0) }
    HedayahArPanelTheme {
    HedayahHologramSurface(shape = MaterialTheme.shapes.large,
        modifier = modifier.testTag("vision-conversation-panel")) {
        Column(Modifier.heightIn(max = maxHeight).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(bodyScroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (typing) {
                    OutlinedTextField(question, onValueChange = { question = it.take(1000) }, label = { Text(copy.question) },
                        modifier = Modifier.fillMaxWidth().testTag("vision-live-question"), maxLines = 2,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submit() }), enabled = !state.asking)
                    Button(onClick = { submit() }, enabled = question.isNotBlank() && !state.asking && state.enabled && retrySeconds == 0L,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("vision-live-send")) { Text(copy.send) }
                }
                if (state.conversationActive) {
                    val waitingForNewAnswer = pendingQuestion.isNotBlank() &&
                        state.history.lastOrNull { it.role == "user" }?.text != pendingQuestion
                    if (waitingForNewAnswer)
                        Text(pendingQuestion, color = Hedayah.ArAccent, style = MaterialTheme.typography.labelLarge)
                    val reply = state.reply?.takeUnless { waitingForNewAnswer }
                    if (reply != null) {
                        Text(reply, style = MaterialTheme.typography.bodyMedium)
                        VisionSources(state.replySources, copy)
                        TextButton(onClick = { speech.read(reply) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(copy.read) }
                    }
                    if (state.history.isNotEmpty()) {
                        TextButton(onClick = { showHistory = !showHistory }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(copy.conversation)
                        }
                        if (showHistory) state.history.forEach { entry ->
                            Text(entry.text, color = if (entry.role == "user") Hedayah.ArAccent else Hedayah.ArText,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Text(when {
                    retrySeconds > 0 -> copy.retryWaiting(retrySeconds)
                    speech.notice != null -> speech.notice!!
                    speech.permissionPending -> copy.microphonePermission
                    speech.listening -> copy.listening
                    speech.processing -> copy.recognizingSpeech
                    speech.speaking -> copy.speakingReply
                    else -> status
                }, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(if (retrySeconds > 0) "vision-retry-wait" else "vision-conversation-status")
                        .semantics { if (retrySeconds == 0L) liveRegion = LiveRegionMode.Polite },
                    color = if (speech.notice != null || state.error != null) MaterialTheme.colorScheme.error else Hedayah.ArMuted)
                if (state.asking || (state.voiceBusy && !speech.listening && !speech.processing && !speech.speaking && !speech.permissionPending))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                VisionMicrophoneButton(speech, copy, canTalk, onTalk, Modifier.weight(1f),
                    idleLabel = copy.talkShort, holographic = true)
                if (speech.listening || speech.speaking || speech.processing) {
                    TextButton(onClick = speech.stop, modifier = Modifier.heightIn(min = 48.dp)) { Text(copy.stop) }
                } else {
                    TextButton(onClick = { typing = !typing }, enabled = !state.asking,
                        modifier = Modifier.widthIn(max = 100.dp).heightIn(min = 48.dp)) { Text(copy.writeShort, maxLines = 1) }
                }
                if (state.conversationActive) {
                    IconButton(onClick = { typing = false; question = ""; showHistory = false;
                        focus.clearFocus(); onCloseConversation() },
                        modifier = Modifier.size(48.dp).testTag("vision-close-conversation")
                            .semantics { contentDescription = copy.close }) {
                        Canvas(Modifier.size(20.dp)) {
                            val inset = 3.dp.toPx()
                            drawLine(Hedayah.ArText, Offset(inset, inset), Offset(size.width - inset, size.height - inset),
                                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                            drawLine(Hedayah.ArText, Offset(size.width - inset, inset), Offset(inset, size.height - inset),
                                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                        }
                    }
                }
            }
            if (!state.conversationActive && !typing) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
            }
        }
    }
    }
}
