package com.sarab.vision.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sarab.vision.ritual.*
import com.sarab.vision.vision.*
import kotlin.math.roundToInt

@Composable
fun BoxScope.ReligiousCameraHud(
    controller: EyeVisionController,
    state: EyeVisionState,
    enrolled: Boolean,
    cameraLive: Boolean,
    counterStore: RitualCounterStore,
    counterState: RitualCounterState,
    watch: RitualWatchState,
    onConnect: () -> Unit,
    onNavigation: () -> Unit,
    onCameraSettings: () -> Unit,
    phoneCamera: Boolean = false,
    motionAvailable: Boolean = true,
    onChooseImage: (() -> Unit)? = null,
    onReturnToCamera: (() -> Unit)? = null,
    onLanguageChange: ((String) -> Unit)? = null,
    imageBusy: Boolean = false,
    imageError: String? = null,
) {
    val copy = remember(state.language, phoneCamera, state.isImportedImage) { VisionCopy(state.language, phoneCamera, state.isImportedImage) }
    val retrySeconds = rememberVisionRetrySeconds(state.retryAtElapsedMs)
    var settings by remember { mutableStateOf(false) }
    var consent by remember(phoneCamera) { mutableStateOf(false) }
    var counter by remember { mutableStateOf(false) }
    var detail by remember(phoneCamera) { mutableStateOf(false) }
    var pendingQuestion by remember(phoneCamera) { mutableStateOf("") }
    var headerHeightPx by remember { mutableIntStateOf(0) }
    var conversationHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val topReserve = if (headerHeightPx > 0) with(density) { headerHeightPx.toDp() } else 76.dp
    val bottomReserve = if (conversationHeightPx > 0) with(density) { conversationHeightPx.toDp() } else 130.dp
    val appContext = LocalContext.current.applicationContext
    val audioPrefs = remember(appContext) { appContext.getSharedPreferences("smart_mutawwif_audio", Context.MODE_PRIVATE) }
    var muted by remember { mutableStateOf(audioPrefs.getBoolean("muted", false)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var resumedAt by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val narrationGate = remember { VisionNarrationGate() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { resumedAt = SystemClock.elapsedRealtime(); foreground = true }
            else if (event == Lifecycle.Event.ON_PAUSE) foreground = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val speech = rememberVisionSpeech(state.language, state.conversationRevision,
        muted = muted, cloudEnabled = state.enabled && enrolled,
        onBeforeListen = controller::beginVoiceTurn,
        onPermissionPendingChange = controller::setMicrophonePermissionPending,
        onTurnFinished = controller::endVoiceTurn,
    ) { question ->
        pendingQuestion = question
        if (detail) controller.ask(question) else controller.askLive(question)
    }
    val rtl = state.language in setOf("ar", "ur")
    fun activate() { if (!enrolled) onConnect() else consent = true }
    fun select(item: VisionItem?) {
        speech.stop()
        controller.pinScene()
        controller.selectItem(item?.id)
        detail = true
    }
    LaunchedEffect(state.scene?.sceneId, state.scenePinned) {
        if (state.scene == null) {
            detail = false
        } else if (state.scenePinned && !state.conversationActive) {
            // An explicit capture returns a snapshot to review, even after the camera moves.
            settings = false
            counter = false
            consent = false
            detail = true
        }
    }
    LaunchedEffect(state.enabled, phoneCamera) { if (!state.enabled) speech.stop() }
    LaunchedEffect(state.reply, state.conversationRevision) {
        state.reply?.let { speech.readReply(it, state.conversationRevision) }
        if (state.reply != null) pendingQuestion = ""
    }
    LaunchedEffect(state.scene?.sceneId, state.markersVisible, state.scenePinned, state.enabled, state.isImportedImage,
        foreground, resumedAt, muted, speech.listening, speech.processing, speech.speaking, state.voiceBusy, state.asking,
        settings, counter, consent, enrolled) {
        val scene = state.scene ?: return@LaunchedEffect
        val item = scene.items.firstOrNull { it.kind == "landmark" } ?: return@LaunchedEffect
        if (!foreground || !state.enabled || !enrolled) return@LaunchedEffect
        val source = if (state.isImportedImage) "photo:${System.identityHashCode(state.importedImage)}" else if (phoneCamera) "phone" else "eye"
        val key = "$source:${state.language}:${item.landmarkId ?: item.label}"
        val busy = state.voiceBusy || state.asking || state.conversationActive || speech.listening ||
            speech.processing || speech.speaking || speech.permissionPending || settings || counter || consent
        if ((state.isImportedImage || state.markersVisible || state.scenePinned) &&
            narrationGate.shouldSpeak(scene.sceneId, key, scene.capturedAtElapsedMs, SystemClock.elapsedRealtime(), resumedAt, muted, busy)) {
            visionLandmarkNarration(item, state.language).takeIf { it.isNotBlank() }?.let(speech.read)
        }
    }
    BackHandler(enabled = state.conversationActive && !detail && !settings && !counter && !consent) {
        speech.stop(); pendingQuestion = ""; controller.clearConversation()
    }
    CompositionLocalProvider(LocalContentColor provides Hedayah.Ivory,
        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        if ((state.markersVisible || state.isImportedImage) && state.scene != null && !detail) {
            SceneMarkers(state.scene, Modifier.fillMaxSize(), state.trackedBoxes, topReserve, bottomReserve,
                fitImage = state.isImportedImage) { select(it) }
        }
        BoxWithConstraints(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { headerHeightPx = it.height }.safeDrawingPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)) {
            val headerTypography = MaterialTheme.typography
            val branding: @Composable (Modifier) -> Unit = { brandModifier ->
            HedayahArPanelTheme {
            HedayahHologramSurface(shape = MaterialTheme.shapes.medium, modifier = brandModifier) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HedayahLogo(Modifier.width(38.dp).height(28.dp))
                    Column(Modifier.weight(1f)) {
                        Text(copy.title, style = headerTypography.titleSmall.copy(shadow = MaterialTheme.typography.titleSmall.shadow),
                            color = Hedayah.ArText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${copy.cameraSource} · ${when {
                            !state.enabled -> copy.off
                            state.requestBusy -> copy.requestPending
                            state.isImportedImage -> copy.importedSource
                            state.paused -> copy.pause
                            phoneCamera && !motionAvailable -> copy.manualRecognition
                            else -> copy.automatic
                        }}",
                            style = headerTypography.labelSmall.copy(shadow = MaterialTheme.typography.labelSmall.shadow),
                            color = Hedayah.ArAccent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            }
            }
            val controls: @Composable RowScope.() -> Unit = {
            RitualCounterBadge(counterState, watch, { speech.stop(); counter = true }, language = state.language)
            HedayahArPanelTheme {
                HedayahHologramSurface(shape = MaterialTheme.shapes.medium) {
                    TextButton(onClick = { muted = !muted; audioPrefs.edit().putBoolean("muted", muted).apply() },
                        modifier = Modifier.widthIn(max = 76.dp).heightIn(min = 48.dp).testTag("vision-mute")
                            .semantics { contentDescription = if (muted) copy.unmuteNarration else copy.muteNarration }) {
                        Text(if (muted) copy.audioShort else copy.muteShort,
                            style = headerTypography.labelLarge.copy(shadow = MaterialTheme.typography.labelLarge.shadow), maxLines = 1)
                    }
                }
            }
            HedayahArPanelTheme {
            HedayahHologramSurface(shape = MaterialTheme.shapes.medium) {
                TextButton(onClick = { speech.stop(); settings = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(copy.settings, style = headerTypography.labelLarge.copy(shadow = MaterialTheme.typography.labelLarge.shadow))
                }
            }
            }
            }
            if (maxWidth < 600.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    branding(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically, content = controls)
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    branding(Modifier.weight(1f))
                    controls()
                }
            }
        }
        // The camera's waiting view owns its retry/source actions. Keep that viewport
        // unobstructed until live frames return; speech remains owned by this HUD.
        if ((cameraLive || state.isImportedImage) && !detail) VisionConversationPanel(state, speech, copy,
            status = when {
                imageBusy -> copy.imageLoading
                imageError != null -> imageError
                retrySeconds > 0 -> copy.retryWaiting(retrySeconds)
                state.error != null -> visionErrorText(state.error, copy)
                state.asking -> copy.answering
                state.voiceBusy -> copy.requestPending
                state.analyzing -> copy.examining
                !state.enabled -> copy.purpose
                state.isImportedImage && state.scene == null -> copy.photoReady
                state.conversationActive -> copy.tapToTalk
                state.scene != null && !state.markersVisible && !state.isImportedImage -> copy.previousSnapshotResult
                state.scene != null -> if (state.scene.items.isEmpty()) copy.noItems else copy.resultReady
                phoneCamera && !state.paused && cameraLive && !motionAvailable -> copy.phoneMotionUnavailable
                else -> copy.tapToTalk
            },
            pendingQuestion = pendingQuestion,
            canTalk = retrySeconds == 0L && !state.asking && !state.voiceBusy && (!state.enabled || cameraLive || state.isImportedImage),
            onTalk = { if (!state.enabled) activate() else speech.listen() },
            onAsk = { pendingQuestion = it; controller.askLive(it) },
            onCloseConversation = { speech.stop(); pendingQuestion = ""; controller.clearConversation() },
            retrySeconds = retrySeconds,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { conversationHeightPx = it.height }.safeDrawingPadding()
                .imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            TextButton(onClick = { speech.stop(); if (state.enabled) controller.analyzeNow() else activate() },
                enabled = retrySeconds == 0L && !state.requestBusy && !state.voiceBusy && (!state.enabled || cameraLive || state.isImportedImage),
                modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.enabled) copy.identify else copy.enable) }
            if (state.enabled && !state.isImportedImage) {
                TextButton(onClick = { speech.stop(); controller.setPaused(!state.paused) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (state.paused) copy.resume else copy.pause)
                }
            }
            if (onChooseImage != null) TextButton(onClick = { speech.stop(); onChooseImage() }, enabled = !imageBusy,
                modifier = Modifier.heightIn(min = 48.dp).testTag("vision-choose-image")) { Text(copy.chooseImage) }
            if (state.isImportedImage && onReturnToCamera != null) TextButton(onClick = { speech.stop(); onReturnToCamera() },
                modifier = Modifier.heightIn(min = 48.dp)) { Text(copy.returnToCamera) }
            val devotionalItem = state.scene?.items?.firstOrNull { it.devotionals.isNotEmpty() }
            if (devotionalItem != null) {
                OutlinedButton(onClick = { select(devotionalItem) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(copy.devotional) }
            } else if (state.scene != null) {
                OutlinedButton(onClick = { select(null) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(copy.review) }
            }
        }
        if (consent) {
            VisionDialog(onDismiss = { consent = false }) {
                Text(copy.sharing, style = MaterialTheme.typography.titleLarge)
                Text(copy.privacy, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { consent = false; controller.setEnabled(true); if (state.isImportedImage) controller.analyzeNow() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(copy.agree) }
                TextButton(onClick = { consent = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(copy.close) }
            }
        }
        if (settings) {
            VisionDialog(onDismiss = { settings = false }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(copy.settings, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { settings = false }) { Text(copy.close) }
                }
                Text(copy.sourceLabel, style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { settings = false; onCameraSettings() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("${copy.cameraSource} · ${copy.changeCamera}")
                }
                Text(copy.languageLabel, style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ar" to "العربية", "en" to "English", "ur" to "اردو", "id" to "Indonesia", "tr" to "Türkçe").forEach { (code, label) ->
                        FilterChip(selected = code == state.language, onClick = { onLanguageChange?.invoke(code) ?: controller.setLanguage(code) },
                            label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(copy.sharing, modifier = Modifier.weight(1f))
                    Switch(state.enabled, onCheckedChange = { if (it) activate() else controller.setEnabled(false) })
                }
                Text(copy.privacy, style = MaterialTheme.typography.bodySmall, color = Hedayah.HudMuted)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(copy.translate, modifier = Modifier.weight(1f))
                    Switch(state.translate, onCheckedChange = controller::setTranslate)
                }
                Text(copy.translateNote, style = MaterialTheme.typography.bodySmall, color = Hedayah.HudMuted)
                if (!enrolled) {
                    Text(copy.connectNote, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onConnect, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(copy.connect) }
                }
                HorizontalDivider()
                Text(copy.extras, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { settings = false; onNavigation() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(copy.navigation) }
                TextButton(onClick = onConnect, modifier = Modifier.heightIn(min = 48.dp)) { Text(copy.group) }
                TextButton(onClick = { settings = false; onCameraSettings() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(copy.camera) }
            }
        }
        if (counter) {
            Dialog(onDismissRequest = { counter = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(Modifier.widthIn(max = 500.dp).fillMaxWidth().safeDrawingPadding().padding(12.dp),
                    color = Hedayah.Hud, shape = MaterialTheme.shapes.large) {
                    RitualCounterPanel(counterState, watch, counterStore::selectMode, counterStore::selectSource,
                        counterStore::increment, counterStore::undo, counterStore::reset, { counter = false }, language = state.language)
                }
            }
        }
        if (detail && state.scene != null) {
            SceneDetail(state, controller::selectItem, controller::ask, phoneCamera = phoneCamera, sharedSpeech = speech,
                cameraLive = cameraLive, topInset = topReserve) {
                speech.stop()
                detail = false
                controller.unpinScene()
            }
        }
    }
}

@Composable
internal fun SceneMarkers(scene: VisionScene, modifier: Modifier, onSelect: (VisionItem) -> Unit) {
    SceneMarkers(scene, modifier, emptyList(), onSelect = onSelect)
}

@Composable
internal fun SceneMarkers(scene: VisionScene, modifier: Modifier, trackedBoxes: List<VisionTrackedBox>,
    topInset: Dp = 76.dp, bottomInset: Dp = 130.dp, fitImage: Boolean = false, onSelect: (VisionItem) -> Unit) {
    val density = LocalDensity.current
    val tracked = trackedBoxes.associateBy { it.itemId }
    BoxWithConstraints(modifier, contentAlignment = AbsoluteAlignment.TopLeft) {
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        val projected = scene.items.mapNotNull { item ->
            if (trackedBoxes.isNotEmpty() && item.id !in tracked) return@mapNotNull null
            val box = tracked[item.id]?.box ?: item.box
            (if (fitImage) fitVisionPhotoBox(box, scene.imageWidth, scene.imageHeight, width, height)
                else cropVisionBox(box, scene.imageWidth, scene.imageHeight, width, height))?.let { item to it }
        }
        val labels = with(density) { layoutVisionLabels(projected.map { it.second }, width, height,
            220.dp.toPx(), 80.dp.toPx() * fontScale.coerceAtLeast(1f), topInset.toPx(), bottomInset.toPx(), 8.dp.toPx()) }
        projected.forEachIndexed { index, (item, box) ->
            val label = labels[index]
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Hedayah.ArAccent.copy(alpha = .18f), Offset(box.left, box.top),
                    Size(box.right - box.left, box.bottom - box.top), style = Stroke(5.dp.toPx()))
                drawRect(Hedayah.ArAccent.copy(alpha = .9f), Offset(box.left, box.top),
                    Size(box.right - box.left, box.bottom - box.top), style = Stroke(1.5.dp.toPx()))
                drawLine(Hedayah.ArAccent.copy(alpha = .16f),
                    Offset((box.left + box.right) / 2, (box.top + box.bottom) / 2),
                    Offset((label.left + label.right) / 2, label.bottom), strokeWidth = 3.dp.toPx())
                drawLine(Hedayah.ArAccent.copy(alpha = .7f),
                    Offset((box.left + box.right) / 2, (box.top + box.bottom) / 2),
                    Offset((label.left + label.right) / 2, label.bottom), strokeWidth = 1.dp.toPx())
            }
            Box(Modifier.absoluteOffset { IntOffset(box.left.roundToInt(), box.top.roundToInt()) }
                .width(with(density) { (box.right - box.left).toDp() })
                .height(with(density) { (box.bottom - box.top).toDp() })
                .semantics { contentDescription = item.label }
                .clickable(role = Role.Button, onClickLabel = item.label) { onSelect(item) })
            HedayahArPanelTheme {
            HedayahHologramSurface(shape = RoundedCornerShape(10.dp),
                modifier = Modifier.zIndex(1f).absoluteOffset { IntOffset(label.left.roundToInt(), label.top.roundToInt()) }
                    .width(with(density) { (label.right - label.left).toDp() })
                    .height(with(density) { (label.bottom - label.top).toDp() })
                    .clickable(onClickLabel = item.label) { onSelect(item) }) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center) {
                    if (item.kind == "text") {
                        Text(item.description, style = MaterialTheme.typography.labelLarge,
                            color = Hedayah.ArText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.label, style = MaterialTheme.typography.labelMedium,
                            color = Hedayah.ArMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Text(item.label, style = MaterialTheme.typography.titleSmall,
                            color = Hedayah.ArText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            }
        }
    }
}

@Composable
internal fun VisionDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 760.dp).fillMaxWidth().safeDrawingPadding().imePadding().padding(12.dp),
            color = Hedayah.Hud, contentColor = Hedayah.Ivory, shape = MaterialTheme.shapes.large) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable
internal fun VisionSources(sources: List<VisionSource>, copy: VisionCopy,
    mutedColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val uri = LocalUriHandler.current
    if (sources.isNotEmpty()) {
        Text(copy.sources, style = MaterialTheme.typography.labelMedium, color = mutedColor)
        sources.distinctBy { it.id }.forEach { source ->
            TextButton(onClick = { runCatching { uri.openUri(source.url) } }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(source.title, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

internal fun visionErrorText(error: VisionError, copy: VisionCopy): String {
    return when (error) {
        VisionError.ENROLLMENT_REQUIRED, VisionError.AUTHENTICATION -> copy.connectNote
        VisionError.CONSENT_REQUIRED -> copy.off
        VisionError.CAMERA_UNAVAILABLE, VisionError.FRAME_STALE -> copy.freshFrame
        VisionError.EMPTY_QUESTION -> copy.question
        VisionError.SCENE_REQUIRED -> copy.identify
        VisionError.RATE_LIMIT -> copy.rateLimited
        VisionError.NETWORK -> copy.networkError
        VisionError.SERVICE_BUSY -> copy.serviceBusy
        VisionError.TIMEOUT -> copy.serviceTimeout
        VisionError.UPLOAD_TIMEOUT -> copy.uploadTimeout
        VisionError.SERVICE_UNAVAILABLE -> copy.serviceUnavailable
        VisionError.SERVICE_CONFIGURATION, VisionError.SERVICE_AUTHENTICATION, VisionError.MODEL_UNAVAILABLE -> copy.serviceConfiguration
        VisionError.RESPONSE_BLOCKED -> copy.responseBlocked
        VisionError.INVALID_RESPONSE -> copy.invalidResponse
        VisionError.IMAGE_ENCODING -> copy.imageEncodingError
    }
}
