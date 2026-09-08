package com.sarab.vision.glasses.motion

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import com.sarab.vision.diagnostics.DiagnosticLog
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One/One Pro IMU over the glasses' USB Ethernet, 169.254.2.1:52998.
 * Does not claim any USB interface, send device commands, use phone sensors,
 * bind the process to a network, or disturb the route used for maps/internet.
 * start/stop are nonblocking and reusable across Activity foreground lifecycles.
 */
class GlassesMotionController(context: Context) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generation = AtomicLong()
    private val sessions = AtomicLong()
    private val lifecycleLock = Any()
    private var task: Job? = null
    private var activeSocket: Socket? = null
    private val mutableState = MutableStateFlow(GlassesMotionState())
    val state: StateFlow<GlassesMotionState> = mutableState.asStateFlow()

    fun start() = synchronized(lifecycleLock) {
        if (task?.isActive == true) return@synchronized
        val run = generation.incrementAndGet()
        DiagnosticLog.record(DIAGNOSTIC_COMPONENT, "start run=$run")
        mutableState.value = GlassesMotionState(status = "جاري البحث عن حساسات النظارة", sessionId = sessions.get())
        task = scope.launch {
            var routeObserved = false
            var previousEthernet: Network? = null
            while (currentCoroutineContext().isActive && generation.get() == run) {
                try {
                    val network = ethernetNetwork()
                    if (!routeObserved || network != previousEthernet) {
                        routeObserved = true
                        previousEthernet = network
                        recordEthernetSelection(network)
                    }
                    if (network == null) {
                        publish(run, GlassesMotionState(
                            status = "لم يظهر اتصال حساسات النظارة عبر USB؛ أعد توصيلها",
                            sessionId = sessions.get(),
                        ))
                    } else {
                        readSession(network, run)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (!currentCoroutineContext().isActive || generation.get() != run) break
                    Log.w(TAG, "Glasses IMU disconnected: ${failure.message}")
                    // Do not export arbitrary socket messages containing local network addresses.
                    val reason = failure.message?.takeIf { it.startsWith("IMU ") }
                        ?: "glasses socket/network error"
                    DiagnosticLog.record(
                        DIAGNOSTIC_COMPONENT,
                        "disconnected run=$run session=${sessions.get()} " +
                            "exception=${failure.javaClass.simpleName} reason=$reason",
                    )
                    publish(run, GlassesMotionState(
                        status = "توقفت حساسات النظارة — جاري إعادة الاتصال",
                        sessionId = sessions.get(),
                    ))
                }
                delay(700)
            }
        }
    }

    fun stop() = synchronized(lifecycleLock) {
        val wasActive = task != null || activeSocket != null
        generation.incrementAndGet()
        task?.cancel()
        task = null
        // Closing releases a blocked socket read immediately; no joining on the UI thread.
        runCatching { activeSocket?.close() }
        activeSocket = null
        mutableState.value = GlassesMotionState(sessionId = sessions.get())
        if (wasActive) {
            DiagnosticLog.record(DIAGNOSTIC_COMPONENT, "stop session=${sessions.get()}")
        }
    }

    private fun ethernetNetwork(): Network? {
        val host = InetAddress.getByName(HOST)
        return connectivity?.allNetworks?.firstOrNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return@firstOrNull false
            val properties = connectivity.getLinkProperties(network) ?: return@firstOrNull false
            // A generic Ethernet/default route is not enough: require the glasses' subnet.
            properties.linkAddresses.any {
                val address = it.address.hostAddress.orEmpty()
                address.startsWith("169.254.")
            } && properties.routes.any { it.matches(host) }
        }
    }

    private suspend fun readSession(network: Network, run: Long) {
        network.socketFactory.createSocket().use { socket ->
            synchronized(lifecycleLock) {
                if (generation.get() != run) return
                activeSocket = socket
            }
            var connectedSession = 0L
            try {
                socket.soTimeout = 150
                socket.tcpNoDelay = true
                DiagnosticLog.record(DIAGNOSTIC_COMPONENT, "TCP connect run=$run glassesPort=$PORT")
                socket.connect(InetSocketAddress(HOST, PORT), 1500)
                val session = sessions.incrementAndGet()
                connectedSession = session
                DiagnosticLog.record(DIAGNOSTIC_COMPONENT, "TCP connected; new session=$session; calibration starting")
                val parser = OneImuFramer()
                val estimator = OneHeadEstimator()
                var lastValid = SystemClock.elapsedRealtimeNanos()
                var lastPublished = 0L
                var lastHealthLog = lastValid
                var receivedReports = 0L
                var acceptedReports = 0L
                var rejectedTimestamps = 0L
                var receivedBytes = 0L
                var calibrationReported = false
                publish(run, GlassesMotionState(
                    status = "ثبّت رأسك وانظر للأمام لمعايرة حساسات النظارة",
                    sessionId = session,
                ))
                val buffer = ByteArray(4096)
                val input = socket.getInputStream()
                while (currentCoroutineContext().isActive && generation.get() == run) {
                    val count = try {
                        input.read(buffer)
                    } catch (_: SocketTimeoutException) {
                        0
                    }
                    if (count < 0) throw IOException("IMU stream closed")
                    val now = SystemClock.elapsedRealtimeNanos()
                    if (count > 0) {
                        receivedBytes += count
                        for (report in parser.append(buffer, count)) {
                            receivedReports++
                            if (!estimator.acceptsTimestamp(report)) {
                                rejectedTimestamps++
                                continue
                            }
                            val rotation = try {
                                estimator.update(report)
                            } catch (failure: IllegalStateException) {
                                DiagnosticLog.record(
                                    DIAGNOSTIC_COMPONENT,
                                    "timestamp discontinuity session=$session; restarting calibration",
                                )
                                throw failure
                            }
                            acceptedReports++
                            lastValid = now
                            if (rotation != null && !calibrationReported) {
                                calibrationReported = true
                                DiagnosticLog.record(
                                    DIAGNOSTIC_COMPONENT,
                                    "calibration ready session=$session acceptedReports=$acceptedReports",
                                )
                            }
                            // Keep the full-rate integration off the UI; publish at most 60 Hz.
                            if (now - lastPublished < 16_000_000L) continue
                            lastPublished = now
                            publish(run, GlassesMotionState(
                                status = if (rotation != null) "حساسات النظارة تعمل — اربط اتجاه النظر بالبوصلة"
                                    else "ثبّت رأسك وانظر للأمام لمعايرة حساسات النظارة",
                                tracking = rotation != null,
                                yawDegrees = rotation?.yaw,
                                pitchDegrees = rotation?.pitch,
                                rollDegrees = rotation?.roll,
                                sessionId = session,
                                lastSampleElapsedRealtimeNanos = now,
                                calibrationProgress = estimator.calibrationProgress,
                            ))
                        }
                    }
                    // Includes incomplete frames, garbage, MAG-only streams and replayed timestamps.
                    if (now - lastValid >= STALE_NANOS) {
                        DiagnosticLog.record(
                            DIAGNOSTIC_COMPONENT,
                            "stale session=$session sampleAgeMs=${(now - lastValid) / 1_000_000L} " +
                                "receivedReports=$receivedReports acceptedReports=$acceptedReports " +
                                "rejectedTimestamps=$rejectedTimestamps bytes=$receivedBytes",
                        )
                        throw IOException("IMU stream stale")
                    }
                    if (now - lastHealthLog >= HEALTH_LOG_NANOS) {
                        DiagnosticLog.record(
                            DIAGNOSTIC_COMPONENT,
                            "health session=$session windowMs=${(now - lastHealthLog) / 1_000_000L} " +
                                "receivedReports=$receivedReports acceptedReports=$acceptedReports " +
                                "rejectedTimestamps=$rejectedTimestamps bytes=$receivedBytes " +
                                "sampleAgeMs=${(now - lastValid) / 1_000_000L} calibrated=${estimator.calibrated}",
                        )
                        lastHealthLog = now
                        receivedReports = 0L
                        acceptedReports = 0L
                        rejectedTimestamps = 0L
                        receivedBytes = 0L
                    }
                }
            } finally {
                synchronized(lifecycleLock) {
                    if (activeSocket === socket) activeSocket = null
                }
                DiagnosticLog.record(
                    DIAGNOSTIC_COMPONENT,
                    "TCP closed run=$run session=$connectedSession stopped=${generation.get() != run}",
                )
            }
        }
    }

    private fun recordEthernetSelection(network: Network?) {
        if (network == null) {
            DiagnosticLog.record(DIAGNOSTIC_COMPONENT, "matching glasses Ethernet not found")
            return
        }
        val properties = connectivity?.getLinkProperties(network)
        val prefixes = properties?.linkAddresses.orEmpty()
            .filter { it.address.hostAddress.orEmpty().startsWith("169.254.") }
            .map { it.prefixLength }
            .distinct()
        // Only the selected glasses interface and subnet prefix lengths, never addresses/SSID/MAC.
        DiagnosticLog.record(
            DIAGNOSTIC_COMPONENT,
            "matching glasses Ethernet found iface=${properties?.interfaceName ?: "unknown"} prefixLengths=$prefixes",
        )
    }

    private fun publish(run: Long, value: GlassesMotionState) = synchronized(lifecycleLock) {
        if (generation.get() == run) mutableState.value = value
    }

    companion object {
        private const val TAG = "SarabGlassesIMU"
        private const val DIAGNOSTIC_COMPONENT = "glasses.motion"
        private const val HOST = "169.254.2.1"
        private const val PORT = 52998
        // 750 ms + a 150 ms read timeout means live state expires before one second.
        private const val STALE_NANOS = 750_000_000L
        private const val HEALTH_LOG_NANOS = 5_000_000_000L
    }
}
