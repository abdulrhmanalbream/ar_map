package com.sarab.vision.glasses.camera

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.sarab.vision.diagnostics.DiagnosticLog
import com.sarab.vision.glasses.camera.vendor.GlassesCommands
import com.sarab.vision.glasses.camera.vendor.GlassesFrame
import com.sarab.vision.glasses.camera.vendor.GlassesTransport
import com.sarab.vision.glasses.camera.vendor.HevcDecoder
import com.sarab.vision.glasses.camera.vendor.MjpegDecoder
import com.sarab.vision.glasses.camera.vendor.UsbConfigCodec
import com.sarab.vision.glasses.camera.vendor.UvcCameraHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.Locale
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class EyeCameraState(
    val status: String = "كاميرا النظارة متوقفة",
    val streaming: Boolean = false,
    val connected: Boolean = false,
    val permissionRequired: Boolean = false,
    val error: Boolean = false,
)

/**
 * Direct USB Eye capture: the phone receives the glasses' camera, never Camera2/ARCore.
 *
 * The transport/codec subset is attributed in third_party/xreal-tools. This owner adds
 * Android permission handling, reconnects and cancellation. Published bitmaps are immutable
 * copies: both displays can retain a frame safely, and no caller should recycle them.
 * USB and decoder shutdown run on the worker; start/stop never wait on the UI thread.
 */
class EyeCameraController(context: Context) {
    companion object {
        const val XREAL_VID = 0x3318
        private const val TAG = "EyeCamera"
        private const val FRAME_INTERVAL_MS = 67L // 15 fps presentation; USB still drains at its native rate.
        private const val PERMISSION_TIMEOUT_MS = 45_000L
        private const val MAX_RECOVERIES = 5
        // Activity recreation can create a new controller before the old USB joins
        // complete. Serialise ownership across instances, not only start/stop calls.
        private val worker = ThreadPoolExecutor(
            1, 1, 15, TimeUnit.SECONDS, LinkedBlockingQueue(),
            { runnable -> Thread(runnable, "EyeCamera-Control").apply { isDaemon = true } },
        ).apply { allowCoreThreadTimeOut(true) }
    }

    private val context = context.applicationContext
    private val controllerInstance = Integer.toHexString(System.identityHashCode(this))
    private val usb = this.context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val mutableState = MutableStateFlow(EyeCameraState())
    val state: StateFlow<EyeCameraState> = mutableState.asStateFlow()
    private val mutableFrame = MutableStateFlow<Bitmap?>(null)
    val frame: StateFlow<Bitmap?> = mutableFrame.asStateFlow()

    private val lifecycleLock = Any()
    private val generation = AtomicInteger()
    @Volatile private var running = false
    private var task: Future<*>? = null

    fun start() = synchronized(lifecycleLock) {
        if (running) return@synchronized
        running = true
        val token = generation.incrementAndGet()
        diagnostic("session=$token start Android=${Build.VERSION.SDK_INT}")
        mutableFrame.value = null
        mutableState.value = EyeCameraState(status = "البحث عن كاميرا XREAL Eye…")
        task = worker.submit { runSession(token) }
    }

    fun stop() = synchronized(lifecycleLock) {
        if (running || mutableState.value != EyeCameraState()) diagnostic("session=${generation.get()} stop requested")
        running = false
        generation.incrementAndGet()
        task?.cancel(true)
        task = null
        mutableFrame.value = null
        mutableState.value = EyeCameraState()
    }

    private fun active(token: Int): Boolean = running && generation.get() == token

    private fun update(token: Int, value: EyeCameraState, clearFrame: Boolean = false) =
        synchronized(lifecycleLock) {
            if (active(token)) {
                if (clearFrame) mutableFrame.value = null
                if (mutableState.value != value) {
                    diagnostic("session=$token state connected=${value.connected} streaming=${value.streaming} permission=${value.permissionRequired} error=${value.error}: ${value.status}")
                }
                mutableState.value = value
            }
        }

    private fun runSession(token: Int) {
        val permissionAction = "${context.packageName}.EYE_USB.${UUID.randomUUID()}"
        val permissionPending = AtomicReference<String?>(null)
        val permissionDenied = AtomicBoolean(false)
        val detachCount = AtomicInteger()
        var pipeline: CameraPipeline? = null
        var permissionRegistered = false
        var deviceRegistered = false

        val permissionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!active(token) || intent.action != permissionAction) return
                val device = deviceFrom(intent) ?: return
                if (device.vendorId != XREAL_VID || device.deviceName != permissionPending.get()) return
                permissionPending.set(null)
                permissionDenied.set(!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                diagnostic("session=$token USB permission ${if (permissionDenied.get()) "denied" else "granted"}: ${deviceSummary(device)}")
            }
        }
        val deviceReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!active(token)) return
                val device = deviceFrom(intent) ?: return
                if (device.vendorId != XREAL_VID) return
                diagnostic("session=$token USB ${if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) "detached" else "attached"}: ${deviceSummary(device)}")
                if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                    detachCount.incrementAndGet()
                    update(token, EyeCameraState("إعادة اتصال كاميرا النظارة…", connected = false), clearFrame = true)
                }
            }
        }

        try {
            ContextCompat.registerReceiver(context, permissionReceiver, IntentFilter(permissionAction), ContextCompat.RECEIVER_NOT_EXPORTED)
            permissionRegistered = true
            ContextCompat.registerReceiver(
                context, deviceReceiver, IntentFilter().apply {
                    addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                    addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                }, ContextCompat.RECEIVER_EXPORTED,
            )
            deviceRegistered = true
            val helper = UvcCameraHelper(context)
            var failures = 0
            var previousInventory: String? = null
            while (active(token)) {
                val inventory = usb.deviceList.values.map(::deviceSummary).sorted().joinToString(" | ").ifEmpty { "none" }
                if (inventory != previousInventory) {
                    previousInventory = inventory
                    diagnostic("session=$token USB inventory: $inventory")
                }
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    update(token, EyeCameraState("اسمح للتطبيق بالكاميرا لتشغيل XREAL Eye", permissionRequired = true))
                    Thread.sleep(350)
                    continue
                }
                val device = findGlasses()
                if (device == null) {
                    update(token, EyeCameraState("وصّل النظارة وثبّت كاميرا XREAL Eye", connected = false), clearFrame = true)
                    failures = 0
                    Thread.sleep(500)
                    continue
                }
                if (permissionDenied.get()) {
                    update(token, EyeCameraState("لم يُسمح باتصال USB؛ اضغط إعادة المحاولة واسمح بطلبَي الاتصال", connected = true, permissionRequired = true, error = true))
                    return
                }
                try {
                    ensurePermission(token, device, permissionAction, permissionPending, permissionDenied)
                    if (!active(token)) break
                    if (!hasVideo(device)) {
                        update(token, EyeCameraState("تشغيل كاميرا النظارة… قد يظهر طلب USB ثانٍ", connected = true))
                        activateCamera(token, device)
                        // SetUsbConfigAll resets the composite device (including its network).
                        // Rediscover it and obtain its new Android permission before opening video.
                        Thread.sleep(3000)
                        diagnostic("session=$token waiting for UVC after USB re-enumeration")
                        val deadline = SystemClock.elapsedRealtime() + 15_000
                        while (active(token) && SystemClock.elapsedRealtime() < deadline && findGlasses()?.let(::hasVideo) != true) {
                            Thread.sleep(300)
                        }
                        if (findGlasses()?.let(::hasVideo) != true) throw IllegalStateException("لم تظهر واجهة كاميرا Eye؛ تأكد من تركيبها وأعد توصيل النظارة")
                        diagnostic("session=$token UVC interface appeared after re-enumeration")
                        continue
                    }
                    val streamDetachCount = detachCount.get()
                    pipeline = CameraPipeline(token, helper) { streamDetachCount == detachCount.get() }
                    pipeline.start(device)
                    val startedAt = SystemClock.elapsedRealtime()
                    while (active(token)) {
                        val now = SystemClock.elapsedRealtime()
                        pipeline.recordStats(now)
                        if (streamDetachCount != detachCount.get() || usb.deviceList[device.deviceName] == null) break
                        pipeline.failure.get()?.let { throw IllegalStateException(it) }
                        if (pipeline.lastFrameMs > startedAt && now - startedAt > 15_000) failures = 0
                        // A frozen frame must never look like a live road view. Retain the
                        // USB session briefly for recovery, but remove video within one second.
                        if (pipeline.lastFrameMs > 0 && now - pipeline.lastFrameMs > 750 && mutableState.value.streaming) {
                            diagnostic("session=$token stale video ageMs=${now - pipeline.lastFrameMs}; clearing visible frame")
                            update(token, EyeCameraState("انقطع بث الكاميرا؛ جارٍ استعادة الصورة…", connected = true), clearFrame = true)
                        }
                        if (now - maxOf(startedAt, pipeline.lastFrameMs) > 12_000) {
                            diagnostic("session=$token no decoded video for 12000ms; demoting format")
                            helper.demoteActiveFormat("no decoded video")
                            throw IllegalStateException("لم تصل صورة من الكاميرا؛ إعادة تهيئة الاتصال")
                        }
                        Thread.sleep(200)
                    }
                    pipeline.close()
                    pipeline = null
                    update(token, EyeCameraState("إعادة اتصال كاميرا النظارة…", connected = false), clearFrame = true)
                } catch (e: PermissionDeniedException) {
                    diagnostic("session=$token permission blocked: ${e.message}")
                    update(token, EyeCameraState(e.message.orEmpty(), connected = true, permissionRequired = true, error = true), clearFrame = true)
                    return
                } catch (e: InterruptedException) {
                    throw e
                } catch (_: DeviceReenumeratedException) {
                    update(token, EyeCameraState("إعادة اتصال كاميرا النظارة…"), clearFrame = true)
                    pipeline?.close()
                    pipeline = null
                    failures = 0
                    diagnostic("session=$token USB changed while waiting for permission; rediscovering")
                    Thread.sleep(500)
                } catch (e: Exception) {
                    // Hide the old image before USB/decoder joins, which can take seconds.
                    update(token, EyeCameraState("جارٍ استعادة بث كاميرا Eye…", connected = findGlasses() != null), clearFrame = true)
                    pipeline?.close()
                    pipeline = null
                    if (!active(token)) break
                    failures++
                    Log.w(TAG, "Camera recovery $failures", e)
                    diagnostic("session=$token recovery=$failures/$MAX_RECOVERIES\n${e.stackTraceToString()}")
                    if (failures > MAX_RECOVERIES) {
                        update(token, EyeCameraState("تعذّر تشغيل XREAL Eye. أعد توصيل النظارة ثم اضغط إعادة المحاولة", connected = findGlasses() != null, error = true), clearFrame = true)
                        return
                    }
                    // SetUsbConfigAll also disconnects the working IMU network. A video
                    // transfer failure does not mean UVC disappeared: reopen only video.
                    // Firmware activation is reserved for a genuinely missing interface.
                    update(token, EyeCameraState("إعادة اتصال كاميرا Eye ($failures/$MAX_RECOVERIES)…", connected = findGlasses() != null), clearFrame = true)
                    Thread.sleep((failures * 750L).coerceAtMost(3000))
                }
            }
        } catch (_: InterruptedException) {
            // stop() invalidates the token before interrupting this worker.
        } catch (e: Exception) {
            Log.e(TAG, "Eye capture failed", e)
            diagnostic("session=$token failed\n${e.stackTraceToString()}")
            update(token, EyeCameraState("تعذّر اتصال كاميرا النظارة؛ أعد المحاولة", error = true), clearFrame = true)
        } finally {
            Thread.interrupted() // Cleanup must be able to join its USB/decoder threads.
            pipeline?.close()
            if (permissionRegistered) runCatching { context.unregisterReceiver(permissionReceiver) }
            if (deviceRegistered) runCatching { context.unregisterReceiver(deviceReceiver) }
            synchronized(lifecycleLock) { if (generation.get() == token) running = false }
            diagnostic("session=$token cleanup complete")
        }
    }

    private fun findGlasses(): UsbDevice? = usb.deviceList.values.firstOrNull {
        // The supported firmware identifies the One Pro composite device as Gina (0436).
        // A video-class interface also admits a firmware PID change after re-enumeration.
        it.vendorId == XREAL_VID && (it.productId == 0x0436 || hasVideo(it))
    }

    private fun hasVideo(device: UsbDevice): Boolean = (0 until device.interfaceCount).any {
        device.getInterface(it).let { iface -> iface.interfaceClass == 14 && iface.interfaceSubclass == 2 }
    }

    private fun ensurePermission(token: Int, device: UsbDevice, action: String, pending: AtomicReference<String?>, denied: AtomicBoolean) {
        if (usb.hasPermission(device)) {
            diagnostic("session=$token USB permission already granted: ${deviceSummary(device)}")
            return
        }
        diagnostic("session=$token requesting USB permission: ${deviceSummary(device)}")
        update(token, EyeCameraState("اسمح باتصال USB بالنظارة؛ قد يُطلب الإذن مرتين لتشغيل الكاميرا", connected = true, permissionRequired = true))
        pending.set(device.deviceName)
        denied.set(false)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val permission = PendingIntent.getBroadcast(context, token, Intent(action).setPackage(context.packageName), flags)
        try {
            usb.requestPermission(device, permission)
            val deadline = SystemClock.elapsedRealtime() + PERMISSION_TIMEOUT_MS
            while (active(token) && !usb.hasPermission(device)) {
                if (denied.get()) throw PermissionDeniedException("رفض إذن USB. اضغط إعادة المحاولة واسمح بالنظارة والكاميرا")
                if (SystemClock.elapsedRealtime() > deadline) {
                    diagnostic("session=$token USB permission timed out after ${PERMISSION_TIMEOUT_MS}ms")
                    throw PermissionDeniedException("انتهت مهلة إذن USB؛ اضغط إعادة المحاولة واسمح بالاتصال")
                }
                if (usb.deviceList[device.deviceName] == null) throw DeviceReenumeratedException()
                Thread.sleep(150)
            }
            if (active(token)) diagnostic("session=$token USB permission confirmed")
        } finally {
            pending.compareAndSet(device.deviceName, null)
            permission.cancel()
        }
    }

    private fun activateCamera(token: Int, device: UsbDevice) {
        diagnostic("session=$token activation begin: ${deviceSummary(device)}")
        val endpoints = GlassesTransport.find(device) ?: error("واجهة تحكم XREAL غير متاحة")
        val connection = usb.openDevice(device) ?: error("تعذّر فتح اتصال النظارة")
        val transport = GlassesTransport(connection, endpoints.first, endpoints.second, endpoints.third)
        try {
            check(transport.claim()) { "واجهة تحكم النظارة مشغولة" }
            diagnostic("session=$token activation: control interface claimed; WaitPilotReady")
            val deadline = SystemClock.elapsedRealtime() + 10_000
            var ready = false
            while (active(token) && SystemClock.elapsedRealtime() < deadline) {
                val response = operation(token, transport, GlassesCommands.waitPilotReadyMessages())
                if (response.payload.toString(Charsets.US_ASCII).contains(GlassesCommands.PILOT_READY_MARKER)) {
                    ready = true
                    break
                }
                Thread.sleep(200)
            }
            check(ready) { "لم تجهز النظارة؛ أعد توصيلها" }
            diagnostic("session=$token activation: pilot ready; GetUsbConfigAll")
            operation(token, transport, GlassesCommands.getUsbConfigMessages())
            // Observed working profile keeps NCM/ECM + HID and enables the Eye UVC channel.
            // The packed config schema is not proven for arbitrary profiles: retain the
            // upstream captured bytes instead of inventing changes to individual flags.
            diagnostic("session=$token activation: SetUsbConfigAll Eye UVC profile")
            val response = operation(token, transport, GlassesCommands.setUsbConfigMessages(UsbConfigCodec.SET_UVC0_PAYLOAD))
            check(response.payload.firstOrNull()?.toInt() == 0) { "لم تقبل النظارة تشغيل Eye" }
            diagnostic("session=$token activation accepted; composite USB will re-enumerate")
        } finally {
            transport.release()
            connection.close()
            diagnostic("session=$token activation: control interface released")
        }
    }

    private fun operation(token: Int, transport: GlassesTransport, messages: List<ByteArray>): GlassesFrame.Parsed {
        var result: GlassesFrame.Parsed? = null
        for (message in messages) {
            if (!active(token) || Thread.currentThread().isInterrupted) throw InterruptedException()
            result = transport.request(message)
            check(result.cmd == (message[15].toInt() and 0xff)) { "استجابة USB لا تطابق طلب الكاميرا" }
        }
        return checkNotNull(result)
    }

    private inner class CameraPipeline(
        private val token: Int,
        private val helper: UvcCameraHelper,
        private val deviceCurrent: () -> Boolean,
    ) : UvcCameraHelper.Listener {
        val failure = AtomicReference<String?>(null)
        @Volatile var lastFrameMs = 0L
            private set
        private val closed = AtomicBoolean(false)
        private val decoderLock = Any()
        private var mjpeg: MjpegDecoder? = null
        private var hevc: HevcDecoder? = null
        private var lastDecodedMs = 0L
        private var lastDecodeErrorLogMs = 0L
        private var lastDiscontinuityLogMs = 0L
        private val converter = YuvBitmapConverter()
        private val receivedBytes = AtomicLong()
        private val receivedUnits = AtomicLong()
        private val decodedFrames = AtomicLong()
        private val publishedFrames = AtomicLong()
        private val discardedTransfers = AtomicLong()
        private var lastStatsMs = SystemClock.elapsedRealtime()
        private var previousBytes = 0L
        private var previousDecoded = 0L
        private var previousPublished = 0L

        fun start(device: UsbDevice) {
            helper.listener = this
            diagnostic("session=$token stream opening: ${deviceSummary(device)}")
            update(token, EyeCameraState("بانتظار أول صورة من XREAL Eye…", connected = true), clearFrame = true)
            helper.startStream(device)
        }

        override fun onStreamStarted() = synchronized(decoderLock) {
            if (closed.get() || !active(token)) return@synchronized
            diagnostic("session=$token stream negotiated codec=${if (helper.activeFormatSubtype == 0x06) "MJPEG" else "HEVC"} source=${helper.activeFrameWidth}x${helper.activeFrameHeight} subtype=${helper.activeFormatSubtype} presentationLimitFps=15")
            if (helper.activeFormatSubtype == 0x06) {
                mjpeg = MjpegDecoder().apply {
                    frameGate = object : MjpegDecoder.FrameGate {
                        override fun shouldDecode(nowMs: Long): Boolean {
                            if (closed.get() || !active(token) || nowMs - lastDecodedMs < FRAME_INTERVAL_MS) return false
                            lastDecodedMs = nowMs
                            return true
                        }
                    }
                    frameListener = object : MjpegDecoder.FrameListener {
                        override fun onFrame(bitmap: Bitmap, timestampMs: Long) {
                            decodedFrames.incrementAndGet()
                            if (!closed.get() && active(token)) publish(bitmap.copy(Bitmap.Config.ARGB_8888, false))
                        }
                    }
                    errorListener = object : MjpegDecoder.ErrorListener {
                        override fun onError(message: String, fatal: Boolean) { decodeIssue(message, fatal) }
                    }
                    start()
                }
            } else {
                hevc = HevcDecoder(helper.activeFrameWidth, helper.activeFrameHeight).apply {
                    frameListener = object : HevcDecoder.FrameListener {
                        override fun onFrame(image: android.media.Image) {
                            decodedFrames.incrementAndGet()
                            val now = SystemClock.uptimeMillis()
                            if (closed.get() || !active(token) || now - lastDecodedMs < FRAME_INTERVAL_MS) return
                            lastDecodedMs = now
                            publish(converter.convert(image))
                        }
                    }
                    errorListener = object : HevcDecoder.ErrorListener {
                        override fun onError(message: String, fatal: Boolean) { decodeIssue(message, fatal) }
                    }
                    start()
                }
            }
        }

        private fun decodeIssue(message: String, fatal: Boolean) {
            if (closed.get()) return
            val now = SystemClock.elapsedRealtime()
            if (fatal || now - lastDecodeErrorLogMs >= 5000 || lastDecodeErrorLogMs == 0L) {
                lastDecodeErrorLogMs = now
                diagnostic("session=$token decoder issue fatal=$fatal: $message")
            }
            if (fatal) {
                helper.demoteActiveFormat(message)
                failure.compareAndSet(null, "تعذّر فك صورة الكاميرا")
            }
        }

        private fun publish(bitmap: Bitmap?) = synchronized(lifecycleLock) {
            if (bitmap == null || closed.get() || failure.get() != null || !active(token) || !deviceCurrent()) return@synchronized
            lastFrameMs = SystemClock.elapsedRealtime()
            publishedFrames.incrementAndGet()
            mutableFrame.value = bitmap
            if (!mutableState.value.streaming) {
                diagnostic("session=$token live frame available output=${bitmap.width}x${bitmap.height}")
                mutableState.value = EyeCameraState("كاميرا XREAL Eye متصلة", streaming = true, connected = true)
            }
        }

        override fun onFrameReceived(data: ByteArray) {
            if (closed.get() || !active(token) || !deviceCurrent()) return
            receivedBytes.addAndGet(data.size.toLong())
            receivedUnits.incrementAndGet()
            mjpeg?.onAccessUnit(data)
            hevc?.onAccessUnit(data)
        }
        override fun onStreamDiscontinuity() {
            if (closed.get() || !active(token)) return
            mjpeg?.discardPartialFrame()
            val count = discardedTransfers.incrementAndGet()
            val now = SystemClock.elapsedRealtime()
            if (count == 1L || now - lastDiscontinuityLogMs >= 5000) {
                lastDiscontinuityLogMs = now
                diagnostic("session=$token damaged USB image data discarded count=$count")
            }
        }
        override fun onStreamStalled() {
            if (!closed.get() && failure.compareAndSet(null, "توقف بث كاميرا النظارة")) {
                update(token, EyeCameraState("انقطع بث الكاميرا؛ جارٍ استعادة الصورة…", connected = true), clearFrame = true)
                diagnostic("session=$token UVC stream stalled")
            }
        }
        override fun onError(message: String) {
            if (!closed.get() && failure.compareAndSet(null, message)) diagnostic("session=$token UVC error: $message")
        }
        override fun onCameraFound(description: String) = Unit
        override fun onStreamStopped() = Unit
        override fun onLog(message: String) {
            // Byte-count stats are recorded on a time budget below. Vendor messages here
            // describe endpoint/probe/format choices and stalls; never image payloads.
            if (message.startsWith("Stats:")) return
            diagnostic("session=$token UVC: $message")
        }

        fun recordStats(nowMs: Long) {
            val elapsed = nowMs - lastStatsMs
            if (elapsed < 5000) return
            val bytes = receivedBytes.get()
            val decoded = decodedFrames.get()
            val published = publishedFrames.get()
            diagnostic(String.format(
                Locale.US,
                "session=%d stream stats periodMs=%d receivedBytes=%d unitsTotal=%d decodedFps=%.1f publishedFps=%.1f lastPublishedFrameAgeMs=%d damagedTransfersTotal=%d",
                token, elapsed, bytes - previousBytes, receivedUnits.get(),
                (decoded - previousDecoded) * 1000.0 / elapsed,
                (published - previousPublished) * 1000.0 / elapsed,
                if (lastFrameMs == 0L) -1 else nowMs - lastFrameMs,
                discardedTransfers.get(),
            ))
            previousBytes = bytes
            previousDecoded = decoded
            previousPublished = published
            lastStatsMs = nowMs
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            // closed blocks late decoder publications before clearing under the
            // lifecycle lock; no stale frame can reappear while cleanup joins.
            update(token, EyeCameraState("جارٍ استعادة بث كاميرا Eye…", connected = findGlasses() != null), clearFrame = true)
            diagnostic("session=$token stream closing bytes=${receivedBytes.get()} decoded=${decodedFrames.get()} published=${publishedFrames.get()}")
            runCatching { helper.stopStream() }.onFailure {
                Log.w(TAG, "USB cleanup", it)
                diagnostic("session=$token USB cleanup error\n${it.stackTraceToString()}")
            }
            synchronized(decoderLock) {
                mjpeg?.stop()
                hevc?.stop()
                mjpeg = null
                hevc = null
            }
            helper.listener = null
        }
    }

    @Suppress("DEPRECATION")
    private fun deviceFrom(intent: Intent): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    /** Public descriptor numbers only: no Android path, serial number or device strings. */
    private fun deviceSummary(device: UsbDevice): String = buildString {
        append("VID=%04x PID=%04x class=%d/%d interfaces=".format(Locale.US, device.vendorId, device.productId, device.deviceClass, device.deviceSubclass))
        append((0 until device.interfaceCount).joinToString(",") { index ->
            val iface = device.getInterface(index)
            val endpoints = (0 until iface.endpointCount).joinToString("/") { endpointIndex ->
                val endpoint = iface.getEndpoint(endpointIndex)
                "%02x:t%d:p%d".format(Locale.US, endpoint.address, endpoint.type, endpoint.maxPacketSize)
            }
            "${iface.id}:${iface.interfaceClass}/${iface.interfaceSubclass}[$endpoints]"
        })
    }

    private fun diagnostic(message: String) {
        val safe = message.replace(Regex("/dev/bus/usb/\\d+/\\d+"), "<usb-device>")
            .replace(Regex("(?i)(serial(?:Number)?\\s*[=:]\\s*)[^\\s,}\\]]+")) { "${it.groupValues[1]}<redacted>" }
        DiagnosticLog.record(TAG, "controller=$controllerInstance $safe")
    }

    private class PermissionDeniedException(message: String) : Exception(message)
    private class DeviceReenumeratedException : Exception()
}
