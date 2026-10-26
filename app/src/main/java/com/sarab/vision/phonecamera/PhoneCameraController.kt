package com.sarab.vision.phonecamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureFailure
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.MainThread
import com.sarab.vision.diagnostics.DiagnosticLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

data class PhoneCameraState(val streaming: Boolean = false, val status: String = "كاميرا الجوال متوقفة")

internal data class PhonePreviewGeometry(val width: Int, val height: Int, val sensorDegrees: Int, val displayDegrees: Int)
internal class PhonePreviewTarget(val texture: SurfaceTexture, @Volatile var onGeometry: ((PhonePreviewGeometry) -> Unit)?) {
    @Volatile var retired = false
    @Volatile var destroyed = false
    // Worker-owned, including the fallback after that worker has terminated.
    var textureReleased = false
}

/**
 * Back-camera preview goes directly to TextureView at the device's supported preview rate.
 * Only separate 4fps tracking/analysis captures enter ImageReader and the off-main YUV converter.
 * Main-thread epochs reject late frames; immutable analysis bitmaps stay owned by consumers.
 */
class PhoneCameraController(context: Context) {
    private val appContext = context.applicationContext
    private val cameras = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val displays = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    @Suppress("DEPRECATION")
    private val displayId = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.displayId
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("PhoneCamera-Frames").apply { start() }
    private val worker = Handler(thread.looper)
    private val converter = PhoneYuvConverter()
    private val publicationPending = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(PhoneCameraState())
    private val mutableFrame = MutableStateFlow<Bitmap?>(null)
    val state: StateFlow<PhoneCameraState> = mutableState.asStateFlow()
    val frame: StateFlow<Bitmap?> = mutableFrame.asStateFlow()

    @Volatile private var epoch = 0L
    @Volatile private var wanted = false
    @Volatile private var closed = false
    @Volatile private var previewTarget: PhonePreviewTarget? = null
    // The worker owns everything below; an opening device remains owned until onClosed.
    @Volatile private var current: CameraRun? = null
    private var failedEpoch = -1L
    private var displayListening = false
    private val stoppedCallbacks = mutableListOf<() -> Unit>()

    private class CameraRun(val epoch: Long, val id: String, val characteristics: CameraCharacteristics,
                            val reader: ImageReader, val target: PhonePreviewTarget, val previewSurface: Surface,
                            val previewSize: Size, val startedAt: Long) {
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var opening = true
        @Volatile var closing = false
        var closeSent = false
        var lastConversion = -500L
        var lastFrame = 0L
        @Volatile var lastPreview = 0L
        @Volatile var streaming = false
        @Volatile var previewFrames = 0L
        var analysisRequests = 0L
        var analysisFrames = 0L
        var conversionTotalMs = 0L
        var maxConversionMs = 0L
        var metricsAt = startedAt
        var metricsPreview = 0L
        var metricsAnalysis = 0L
        var metricsRequests = 0L
        var metricsConversionMs = 0L
        var analysisPending = false
        var analysisTicker: Runnable? = null
        var closingAt = 0L
        var sensorRotation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        var displayRotation = 0
        @Volatile var orientationRevision = 0L
        var watchdog: Runnable? = null
    }

    @MainThread internal fun attachPreview(texture: SurfaceTexture, onGeometry: (PhonePreviewGeometry) -> Unit): PhonePreviewTarget {
        requireMainThread()
        val target = PhonePreviewTarget(texture, onGeometry)
        if (closed) { target.retired = true; target.onGeometry = null; return target }
        previewTarget?.let { it.retired = true; it.onGeometry = null }
        previewTarget = target
        epoch++
        mutableFrame.value = null
        diagnostic("preview attached epoch=$epoch")
        worker.post(::reconcile)
        return target
    }

    @MainThread internal fun detachPreview(target: PhonePreviewTarget, destroyed: Boolean) {
        requireMainThread()
        target.retired = true
        target.destroyed = target.destroyed || destroyed
        target.onGeometry = null
        if (previewTarget === target) {
            previewTarget = null
            epoch++
            mutableFrame.value = null
            if (wanted) mutableState.value = PhoneCameraState(status = "بانتظار شاشة الكاميرا…")
        }
        if (!worker.post { reconcile(); releaseTextureIfUnused(target) }) releaseTextureIfUnused(target)
    }

    /** TextureView's update callback counts displayed buffers without a per-frame state emission. */
    @MainThread internal fun previewUpdated(target: PhonePreviewTarget) {
        val run = current ?: return
        if (!isCurrent(run) || run.target !== target) return
        run.lastPreview = SystemClock.elapsedRealtime()
        run.previewFrames++
        if (!run.streaming) {
            run.streaming = true
            mutableState.value = PhoneCameraState(true, "كاميرا الجوال متصلة")
            diagnostic("preview streaming epoch=${run.epoch}")
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(changedId: Int) {
            if (changedId == displayId) current?.takeIf { !it.closing }?.let(::updateRotation)
        }
    }

    @MainThread fun start() {
        requireMainThread()
        if (closed || wanted) return
        epoch++
        mutableFrame.value = null
        if (appContext.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            mutableState.value = PhoneCameraState(status = "اسمح بالوصول إلى الكاميرا لتجربة كاميرا الجوال")
            diagnostic("start refused code=PERMISSION")
            return
        }
        wanted = true
        mutableState.value = PhoneCameraState(status = if (previewTarget == null) "بانتظار شاشة الكاميرا…" else "جارٍ فتح كاميرا الجوال…")
        diagnostic("start requested epoch=$epoch")
        worker.post(::reconcile)
    }

    /** onStopped runs on main after CameraDevice.onClosed, including a stop while opening. */
    @MainThread fun stop(onStopped: (() -> Unit)? = null) {
        requireMainThread()
        wanted = false
        epoch++
        mutableFrame.value = null
        mutableState.value = PhoneCameraState()
        val requestedAt = SystemClock.elapsedRealtime()
        diagnostic("stop requested epoch=$epoch monoMs=$requestedAt")
        val posted = worker.post {
            diagnostic("stop worker queueMs=${SystemClock.elapsedRealtime() - requestedAt}")
            if (onStopped != null) stoppedCallbacks.add(onStopped)
            reconcile()
        }
        if (!posted) onStopped?.invoke() // The worker only exits after its last device closes.
    }

    @MainThread fun close() {
        requireMainThread()
        if (closed) return
        closed = true
        stop()
    }

    private fun isCurrent(run: CameraRun) = current === run && !closed && wanted && epoch == run.epoch &&
        !run.closing && previewTarget === run.target && !run.target.retired

    private fun reconcile() {
        val active = current
        if (active != null) {
            if (!isCurrent(active)) closeRun(active)
            return
        }
        flushStopped()
        if (closed) {
            if (displayListening) { displays.unregisterDisplayListener(displayListener); displayListening = false }
            thread.quitSafely()
        } else if (wanted && previewTarget != null && failedEpoch != epoch) openRun(epoch)
    }

    @SuppressLint("MissingPermission")
    private fun openRun(token: Long) {
        if (!wanted || closed || token != epoch) return
        val target = previewTarget?.takeUnless { it.retired } ?: return
        try {
            val selected = cameras.cameraIdList.asSequence().map { id -> id to cameras.getCameraCharacteristics(id) }
                .firstOrNull { (_, characteristics) ->
                    characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                            ?.getOutputSizes(ImageFormat.YUV_420_888)?.isNotEmpty() == true
                }
            if (selected == null) { failBeforeOpen(token, "NO_BACK_CAMERA", "لا تتوفر كاميرا خلفية متوافقة على هذا الجهاز"); return }
            val (id, characteristics) = selected
            val outputs = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val previewSizes = outputs.getOutputSizes(SurfaceTexture::class.java).orEmpty()
                .filter { it.width in 1..1920 && it.height in 1..1920 && it.width.toLong() * it.height <= 1920L * 1080 }
            val sizes = outputs.getOutputSizes(ImageFormat.YUV_420_888)
                .filter { it.width in 1..8192 && it.height in 1..8192 }
                .sortedWith(compareByDescending<Size> { maxOf(it.width, it.height) <= 960 }.thenByDescending { 
                    if (maxOf(it.width, it.height) <= 960) it.width.toLong() * it.height else -it.width.toLong() * it.height
                })
            val pair = sizes.firstNotNullOfOrNull { analysis ->
                previewSizes.filter { preview -> sameCameraAspect(CameraFrameSize(analysis.width, analysis.height),
                    CameraFrameSize(preview.width, preview.height)) }
                    .maxByOrNull { it.width.toLong() * it.height }?.let { preview -> analysis to preview }
            }
            if (pair == null) { failBeforeOpen(token, "NO_OUTPUT", "صيغة صور الكاميرا غير مدعومة"); return }
            val (size, previewSize) = pair
            target.texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val previewSurface = Surface(target.texture)
            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
            val run = CameraRun(token, id, characteristics, reader, target, previewSurface, previewSize, SystemClock.elapsedRealtime())
            current = run
            updateRotation(run)
            if (!displayListening) { displays.registerDisplayListener(displayListener, worker); displayListening = true }
            reader.setOnImageAvailableListener({ receiveFrame(run) }, worker)
            startWatchdog(run)
            diagnostic("opening epoch=$token preview=${previewSize.width}x${previewSize.height} analysis=${size.width}x${size.height}")
            cameras.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    run.opening = false
                    run.device = camera
                    if (!isCurrent(run)) { closeRun(run); return }
                    configure(run, camera, size)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    run.opening = false; run.device = camera
                    fail(run, "DISCONNECTED", "انقطع اتصال كاميرا الجوال؛ أعد تشغيلها")
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    run.opening = false; run.device = camera
                    fail(run, "DEVICE_$error", when (error) {
                        ERROR_CAMERA_IN_USE, ERROR_MAX_CAMERAS_IN_USE -> "الكاميرا مستخدمة الآن؛ أغلق التطبيق الآخر وأعد المحاولة"
                        ERROR_CAMERA_DISABLED -> "الكاميرا معطّلة؛ راجع إعدادات الخصوصية في الجوال"
                        else -> "تعذر تشغيل كاميرا الجوال؛ أعد المحاولة"
                    })
                }
                override fun onClosed(camera: CameraDevice) {
                    run.opening = false; run.device = null
                    diagnostic("device closed epoch=${run.epoch} elapsedMs=${SystemClock.elapsedRealtime() - run.closingAt}")
                    finishRun(run)
                }
            }, worker)
        } catch (failure: Exception) {
            current?.takeIf { it.epoch == token }?.let { run ->
                run.opening = false
                fail(run, failureCode(failure), failureStatus(failure))
            } ?: failBeforeOpen(token, failureCode(failure), failureStatus(failure))
        }
    }

    @Suppress("DEPRECATION")
    private fun configure(run: CameraRun, camera: CameraDevice, size: Size) {
        try {
            camera.createCaptureSession(listOf(run.previewSurface, run.reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (!isCurrent(run)) { session.close(); closeRun(run); return }
                    run.session = session
                    try {
                        val ranges = run.characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
                        // Prefer a fixed 30fps cadence, then a range containing 30, then the
                        // closest slower rate. The previous path selected the slowest range.
                        val rate = ranges.filter { it.lower <= 30 && it.upper >= 30 }
                            .minWithOrNull(compareBy({ it.upper - 30 }, { -it.lower }))
                            ?: ranges.filter { it.upper <= 30 }.maxWithOrNull(compareBy({ it.upper }, { it.lower }))
                        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(run.previewSurface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            val focusModes = run.characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                            if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in focusModes)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            if (rate != null) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, rate)
                            // Both requests must have identical framing. Disable optional
                            // stabilization crops; the HUD uses the analysis stream's crop.
                            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                            if (run.characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                                    ?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF) == true)
                                set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                            // This disables foldable compatibility cropping, not the sensor
                            // orientation transform that TextureView already receives.
                            if (Build.VERSION.SDK_INT >= 31 && run.characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES)
                                    ?.contains(CaptureRequest.SCALER_ROTATE_AND_CROP_NONE) == true)
                                set(CaptureRequest.SCALER_ROTATE_AND_CROP, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE)
                        }
                        session.setRepeatingRequest(builder.build(), null, worker)
                        builder.addTarget(run.reader.surface)
                        startAnalysis(run, session, builder.build())
                        diagnostic("configured epoch=${run.epoch} pipeline=nativeTexture preview=${run.previewSize.width}x${run.previewSize.height}" +
                            " analysis=${size.width}x${size.height} fps=${rate?.lower ?: 0}..${rate?.upper ?: 0} analysisIntervalMs=250")
                    } catch (failure: Exception) { fail(run, failureCode(failure), failureStatus(failure)) }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    fail(run, "CONFIGURATION", "تعذر تجهيز صورة الكاميرا؛ أعد المحاولة")
                }
            }, worker)
        } catch (failure: Exception) { fail(run, failureCode(failure), failureStatus(failure)) }
    }

    private fun startAnalysis(run: CameraRun, session: CameraCaptureSession, request: CaptureRequest) {
        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                run.analysisPending = false
                if (isCurrent(run)) diagnostic("analysis capture failed epoch=${run.epoch} reason=${failure.reason}")
            }
            override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                run.analysisPending = false
            }
            override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
                if (target === run.reader.surface) run.analysisPending = false
            }
        }
        val tick = object : Runnable {
            override fun run() {
                if (!isCurrent(run)) return
                if (!run.analysisPending && !publicationPending.get()) {
                    try {
                        run.analysisPending = true
                        session.capture(request, callback, worker)
                        run.analysisRequests++
                    } catch (failure: Exception) {
                        run.analysisPending = false
                        fail(run, failureCode(failure), failureStatus(failure))
                        return
                    }
                }
                worker.postDelayed(this, 250)
            }
        }
        run.analysisTicker = tick
        worker.post(tick)
    }

    private fun receiveFrame(run: CameraRun) {
        try {
            val image = run.reader.acquireLatestImage() ?: return
            image.use {
                if (!isCurrent(run)) return
                val capturedAt = SystemClock.elapsedRealtime()
                // The hardware preview never waits for this 4fps local tracking conversion.
                // A busy UI may retain one pending analysis frame, never a bitmap queue.
                if (publicationPending.get()) return
                run.lastConversion = capturedAt
                val crop = image.cropRect
                val planes = image.planes
                require(planes.size == 3)
                fun plane(index: Int) = CameraPlane(planes[index].buffer, planes[index].rowStride, planes[index].pixelStride)
                val rotation = backCameraRotation(run.sensorRotation, run.displayRotation)
                val capturedDisplayRotation = run.displayRotation
                val orientationRevision = run.orientationRevision
                val converted = converter.convert(image.width, image.height, CameraCrop(crop.left, crop.top, crop.width(), crop.height()),
                    plane(0), plane(1), plane(2), rotation)
                // This overload copies the scratch pixels into an immutable Bitmap.
                val bitmap = Bitmap.createBitmap(converted.pixels, converted.width, converted.height, Bitmap.Config.ARGB_8888)
                run.lastFrame = capturedAt
                val conversionMs = SystemClock.elapsedRealtime() - capturedAt
                run.analysisFrames++
                run.conversionTotalMs += conversionMs
                run.maxConversionMs = maxOf(run.maxConversionMs, conversionMs)
                if (run.analysisFrames == 1L) diagnostic("analysis ready epoch=${run.epoch} output=${bitmap.width}x${bitmap.height} rotation=$rotation conversionMs=$conversionMs")
                publicationPending.set(true)
                if (!main.post {
                    try {
                        if (isCurrent(run) && run.orientationRevision == orientationRevision &&
                            displayRotationDegrees() == capturedDisplayRotation && SystemClock.elapsedRealtime() - capturedAt <= 750) {
                            mutableFrame.value = bitmap
                        }
                    } finally {
                        publicationPending.set(false)
                    }
                }) publicationPending.set(false)
            }
        } catch (failure: Exception) {
            if (isCurrent(run)) fail(run, "FRAME_${failure.javaClass.simpleName}", "تعذر قراءة صورة الكاميرا؛ أعد المحاولة")
        } finally {
            run.analysisPending = false
        }
    }

    private fun updateRotation(run: CameraRun) {
        val previousDisplay = run.displayRotation
        val previousSensor = run.sensorRotation
        run.displayRotation = displayRotationDegrees()
        // Foldables may report a new sensor orientation without recreating this activity.
        run.sensorRotation = runCatching { cameras.getCameraCharacteristics(run.id).get(CameraCharacteristics.SENSOR_ORIENTATION) }
            .getOrNull() ?: run.sensorRotation
        if (previousDisplay != run.displayRotation || previousSensor != run.sensorRotation) {
            val revision = ++run.orientationRevision
            run.streaming = false
            diagnostic("rotation changed epoch=${run.epoch} display=${run.displayRotation} sensor=${run.sensorRotation}")
            main.post { if (isCurrent(run) && run.orientationRevision == revision) {
                mutableFrame.value = null
                mutableState.value = PhoneCameraState(status = "جارٍ ضبط اتجاه صورة الكاميرا…")
            } }
        }
        val geometry = PhonePreviewGeometry(run.previewSize.width, run.previewSize.height, run.sensorRotation, run.displayRotation)
        main.post { if (isCurrent(run)) run.target.onGeometry?.invoke(geometry) }
    }

    private fun displayRotationDegrees(): Int = when (displays.getDisplay(displayId)?.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun startWatchdog(run: CameraRun) {
        val check = object : Runnable {
            override fun run() {
                if (!isCurrent(run)) return
                val now = SystemClock.elapsedRealtime()
                if ((run.lastPreview == 0L || run.lastFrame == 0L) && now - run.startedAt > 10_000) {
                    fail(run, "START_TIMEOUT", "لم تصل صورة من الكاميرا؛ أعد المحاولة")
                    return
                }
                if (run.streaming && now - run.lastPreview > 1_500) {
                    run.streaming = false
                    diagnostic("frames stalled epoch=${run.epoch}")
                    main.post { if (isCurrent(run)) {
                        mutableFrame.value = null
                        mutableState.value = PhoneCameraState(status = "بانتظار صورة جديدة من كاميرا الجوال")
                    } }
                }
                if (now - run.metricsAt >= 10_000) recordMetrics(run, now)
                worker.postDelayed(this, 500)
            }
        }
        run.watchdog = check
        worker.postDelayed(check, 500)
    }

    private fun recordMetrics(run: CameraRun, now: Long) {
        diagnostic("performance epoch=${run.epoch} windowMs=${now - run.metricsAt}" +
            " previewFrames=${run.previewFrames - run.metricsPreview} analysisRequests=${run.analysisRequests - run.metricsRequests}" +
            " analysisFrames=${run.analysisFrames - run.metricsAnalysis} conversionTotalMs=${run.conversionTotalMs - run.metricsConversionMs}" +
            " maxConversionMs=${run.maxConversionMs}")
        run.metricsAt = now
        run.metricsPreview = run.previewFrames
        run.metricsAnalysis = run.analysisFrames
        run.metricsRequests = run.analysisRequests
        run.metricsConversionMs = run.conversionTotalMs
    }

    private fun failBeforeOpen(token: Long, code: String, status: String) {
        failedEpoch = token
        diagnostic("failed epoch=$token code=$code")
        main.post { if (epoch == token && !closed) {
            wanted = false; mutableFrame.value = null; mutableState.value = PhoneCameraState(status = status)
        } }
        reconcile()
    }

    private fun fail(run: CameraRun, code: String, status: String) {
        if (current !== run) return
        failedEpoch = run.epoch
        if (!run.closing) {
            diagnostic("failed epoch=${run.epoch} code=$code")
            main.post { if (epoch == run.epoch && !closed) {
                wanted = false; mutableFrame.value = null; mutableState.value = PhoneCameraState(status = status)
            } }
        }
        closeRun(run)
    }

    private fun closeRun(run: CameraRun) {
        if (run.closingAt == 0L) {
            run.closingAt = SystemClock.elapsedRealtime()
            diagnostic("closing epoch=${run.epoch} opening=${run.opening}")
        }
        run.closing = true
        run.watchdog?.let(worker::removeCallbacks)
        run.analysisTicker?.let(worker::removeCallbacks)
        runCatching { run.reader.setOnImageAvailableListener(null, null) }
        runCatching { run.session?.close() }
        run.session = null
        if (run.device != null && !run.closeSent) {
            run.closeSent = true
            run.device?.close()
        } else if (run.device == null && !run.opening) finishRun(run)
    }

    private fun finishRun(run: CameraRun) {
        run.watchdog?.let(worker::removeCallbacks)
        run.analysisTicker?.let(worker::removeCallbacks)
        val readerCloseAt = SystemClock.elapsedRealtime()
        runCatching { run.reader.close() }
        runCatching { run.previewSurface.release() }
        if (current !== run) return
        current = null
        releaseTextureIfUnused(run.target)
        diagnostic("released epoch=${run.epoch} closeMs=${SystemClock.elapsedRealtime() - run.closingAt}" +
            " readerCloseMs=${SystemClock.elapsedRealtime() - readerCloseAt} previewFrames=${run.previewFrames}" +
            " analysisFrames=${run.analysisFrames} lifetimeMs=${SystemClock.elapsedRealtime() - run.startedAt}")
        reconcile()
    }

    private fun releaseTextureIfUnused(target: PhonePreviewTarget) {
        if (target.destroyed && !target.textureReleased && current?.target !== target) {
            target.textureReleased = true
            runCatching { target.texture.release() }
        }
    }

    private fun flushStopped() {
        val callbacks = stoppedCallbacks.toList()
        stoppedCallbacks.clear()
        callbacks.forEach { main.post(it) }
    }

    private fun failureCode(error: Exception) = when (error) {
        is SecurityException -> "PERMISSION"
        is CameraAccessException -> "ACCESS_${error.reason}"
        else -> error.javaClass.simpleName
    }
    private fun failureStatus(error: Exception) = when (error) {
        is SecurityException -> "إذن الكاميرا غير متاح؛ راجع أذونات التطبيق"
        is CameraAccessException -> "الكاميرا غير متاحة الآن؛ أغلق التطبيق الآخر وأعد المحاولة"
        else -> "تعذر تشغيل كاميرا الجوال؛ أعد المحاولة"
    }
    private fun diagnostic(message: String) = DiagnosticLog.record("PhoneCamera", message)
    private fun requireMainThread() = check(Looper.myLooper() == Looper.getMainLooper()) { "Camera lifecycle must run on main" }
}
