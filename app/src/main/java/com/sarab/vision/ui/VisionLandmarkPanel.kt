package com.sarab.vision.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.sarab.vision.vision.EyeVisionState

/** Selected information remains in the camera's activity, with no scrim or camera resize. */
@Composable
internal fun SceneDetail(
    state: EyeVisionState,
    onSelect: (String?) -> Unit,
    onAsk: (String) -> Unit,
    phoneCamera: Boolean = false,
    sharedSpeech: VisionSpeech? = null,
    cameraLive: Boolean = true,
    topInset: Dp = 0.dp,
    onDismiss: () -> Unit,
) {
    val scene = state.scene ?: return
    val copy = remember(state.language, phoneCamera, state.isImportedImage) { VisionCopy(state.language, phoneCamera, state.isImportedImage) }
    val retrySeconds = rememberVisionRetrySeconds(state.retryAtElapsedMs)
    var reading by remember(scene.sceneId) { mutableStateOf(true) }
    var question by remember(scene.sceneId) { mutableStateOf("") }
    var submittedQuestion by remember(scene.sceneId) { mutableStateOf("") }
    val speech = sharedSpeech ?: rememberVisionSpeech(state.language, state.conversationRevision) {
        submittedQuestion = it
        onAsk(it)
    }
    val focus = LocalFocusManager.current
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val safeTop = with(density) { WindowInsets.safeDrawing.getTop(this).toDp() }
    fun close() { focus.clearFocus(); speech.stop(); onDismiss() }
    fun submit() {
        if (question.isBlank() || state.requestBusy || !state.enabled || retrySeconds > 0) return
        speech.stop()
        submittedQuestion = question.trim()
        onAsk(submittedQuestion)
        question = ""
        focus.clearFocus()
    }
    BackHandler(onBack = ::close)
    // Selection actions already stop speech. Mounting the panel must not cancel
    // the newly accepted scene's automatic explanation.
    LaunchedEffect(state.selectedItemId) { scroll.scrollTo(0) }
    LaunchedEffect(state.reply, state.conversationRevision) {
        if (sharedSpeech == null) state.reply?.let { speech.readReply(it, state.conversationRevision) }
    }
    LaunchedEffect(state.asking, state.error, state.reply) {
        if (!state.asking && state.error != null && question.isBlank() && submittedQuestion.isNotBlank()) {
            question = submittedQuestion
            submittedQuestion = ""
        } else if (!state.asking && state.reply != null) submittedQuestion = ""
    }
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        // topInset is the measured HUD including its safe inset, already consumed here.
        val headerSpace = (topInset - safeTop).coerceAtLeast(0.dp)
        val availableHeight = (maxHeight - headerSpace - 24.dp).coerceAtLeast(0.dp)
        val sidePanel = landscape && maxWidth >= 640.dp
        val readingHeight = if (sidePanel || imeVisible) availableHeight
            else minOf(availableHeight, maxHeight * .58f)
        val compactHeight = minOf(readingHeight, 264.dp * density.fontScale.coerceAtLeast(1f))
        val panelWidth = if (sidePanel) minOf(maxWidth * .46f, 480.dp) else maxWidth - 24.dp
        HedayahArPanelTheme {
            HedayahHologramSurface(
                modifier = Modifier.align(if (sidePanel) Alignment.BottomEnd else Alignment.BottomCenter)
                    .padding(12.dp).width(panelWidth).height(if (reading) readingHeight else compactHeight)
                    .testTag("vision-landmark-panel")
                    .semantics { stateDescription = if (reading) copy.readingMode else copy.compactMode },
                shape = MaterialTheme.shapes.large,
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth().testTag("vision-landmark-header"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(state.selectedItem?.label ?: copy.resultTitle, modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { focus.clearFocus(); reading = !reading },
                            modifier = Modifier.widthIn(max = 116.dp).heightIn(min = 48.dp).testTag("vision-landmark-mode")
                                .semantics { contentDescription = if (reading) copy.compactMode else copy.readingMode }) {
                            Text(if (reading) copy.compactAction else copy.readingAction, maxLines = 1)
                        }
                        IconButton(onClick = ::close, modifier = Modifier.size(48.dp)
                            .testTag("vision-landmark-close").semantics { contentDescription = copy.live }) {
                            Canvas(Modifier.size(20.dp)) {
                                val stroke = 2.dp.toPx()
                                drawLine(Hedayah.ArText, Offset(3.dp.toPx(), 3.dp.toPx()),
                                    Offset(size.width - 3.dp.toPx(), size.height - 3.dp.toPx()), stroke, StrokeCap.Round)
                                drawLine(Hedayah.ArText, Offset(size.width - 3.dp.toPx(), 3.dp.toPx()),
                                    Offset(3.dp.toPx(), size.height - 3.dp.toPx()), stroke, StrokeCap.Round)
                            }
                        }
                    }
                    Text(if (state.isImportedImage) copy.importedSource else if (cameraLive) copy.capturedContext else copy.cameraPausedContext,
                        style = MaterialTheme.typography.labelMedium, color = Hedayah.ArMuted,
                        modifier = Modifier.testTag("vision-landmark-context"), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).testTag("vision-landmark-body"),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.requestBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (retrySeconds > 0) Text(copy.retryWaiting(retrySeconds),
                            modifier = Modifier.testTag("vision-retry-wait"),
                            style = MaterialTheme.typography.bodySmall, color = Hedayah.ArAccent)
                        val speechStatus = speech.notice ?: when {
                            speech.permissionPending -> copy.microphonePermission
                            speech.listening -> copy.listening
                            speech.processing -> copy.recognizingSpeech
                            speech.speaking -> copy.speakingReply
                            state.asking || state.voiceBusy -> copy.answering
                            else -> null
                        }
                        if (speechStatus != null) Text(speechStatus, style = MaterialTheme.typography.bodySmall,
                            color = if (speech.notice != null) MaterialTheme.colorScheme.error else Hedayah.ArMuted)
                        if (!reading) {
                            Text(copy.compactHint, style = MaterialTheme.typography.bodySmall, color = Hedayah.ArMuted)
                            Text(state.selectedItem?.description ?: scene.summary.ifBlank { copy.noItems },
                                style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            state.reply?.let { Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 2,
                                overflow = TextOverflow.Ellipsis) }
                        } else {
                            Text(state.selectedItem?.description ?: scene.summary.ifBlank { copy.noItems },
                                style = MaterialTheme.typography.bodyLarge)
                            Text(copy.readingHint, style = MaterialTheme.typography.bodySmall, color = Hedayah.ArMuted)
                            if (scene.items.size > 1) {
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    scene.items.forEach { item ->
                                        FilterChip(state.selectedItemId == item.id,
                                            onClick = { speech.stop(); onSelect(item.id) },
                                            label = { Text(item.label, maxLines = 2) },
                                            modifier = Modifier.heightIn(min = 48.dp))
                                    }
                                }
                            }
                            Text(copy.estimate, style = MaterialTheme.typography.bodySmall, color = Hedayah.ArMuted)
                            val relevantSources = state.selectedItem?.sourceIds?.let { ids ->
                                scene.sources.filter { it.id in ids }
                            } ?: scene.sources
                            VisionSources(relevantSources, copy, mutedColor = Hedayah.ArMuted)
                            val devotionals = (state.selectedItem?.devotionals ?: scene.items.flatMap { it.devotionals })
                                .distinctBy { it.id }
                            if (devotionals.isNotEmpty()) {
                                HorizontalDivider()
                                Text(copy.devotionalTitle, style = MaterialTheme.typography.titleMedium, color = Hedayah.ArAccent)
                                devotionals.forEach { devotional ->
                                    Text(devotional.title, style = MaterialTheme.typography.titleSmall)
                                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                                        Text(devotional.arabicText, style = MaterialTheme.typography.bodyLarge)
                                    }
                                    if (state.language != "ar" && devotional.translation.isNotBlank())
                                        Text(devotional.translation, style = MaterialTheme.typography.bodyMedium)
                                    if (devotional.note.isNotBlank()) Text(devotional.note,
                                        style = MaterialTheme.typography.bodySmall, color = Hedayah.ArMuted)
                                    TextButton(onClick = { speech.readArabic(devotional.arabicText) },
                                        modifier = Modifier.heightIn(min = 48.dp)) { Text(copy.readArabic) }
                                    VisionSources(scene.sources.filter { it.id in devotional.sourceIds }, copy,
                                        mutedColor = Hedayah.ArMuted)
                                }
                            }
                            HorizontalDivider()
                            Text(copy.ask, style = MaterialTheme.typography.titleMedium)
                            state.history.forEach { entry ->
                                Text(entry.text, color = if (entry.role == "user") Hedayah.ArAccent else Hedayah.ArText,
                                    style = if (entry.role == "user") MaterialTheme.typography.titleSmall
                                        else MaterialTheme.typography.bodyLarge)
                            }
                            state.reply?.let { reply ->
                                if (state.history.lastOrNull()?.text != reply)
                                    Text(reply, style = MaterialTheme.typography.bodyLarge)
                                VisionSources(state.replySources, copy, mutedColor = Hedayah.ArMuted)
                                TextButton(onClick = { speech.read(reply) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(copy.read)
                                }
                            }
                            if (retrySeconds == 0L) state.error?.let { Text(visionErrorText(it, copy), color = MaterialTheme.colorScheme.error) }
                            OutlinedTextField(question, onValueChange = { question = it.take(1000) },
                                label = { Text(copy.question) }, modifier = Modifier.fillMaxWidth(),
                                minLines = 1, maxLines = 3, enabled = !state.requestBusy,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { submit() }))
                            Button(onClick = ::submit, enabled = question.isNotBlank() && !state.requestBusy && state.enabled && retrySeconds == 0L,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("vision-detail-send")) { Text(copy.send) }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VisionMicrophoneButton(speech, copy, state.enabled && !state.asking && !state.voiceBusy && retrySeconds == 0L,
                            speech.listen, Modifier.weight(1f), idleLabel = copy.talkShort, holographic = true)
                        if (speech.listening || speech.processing || speech.speaking)
                            TextButton(onClick = speech.stop, modifier = Modifier.heightIn(min = 48.dp)) { Text(copy.stop) }
                    }
                }
            }
        }
    }
}
