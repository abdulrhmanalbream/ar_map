package com.sarab.vision.phonecamera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.MainThread
import com.sarab.vision.vision.VisionPose
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Relative phone rotation for conservative scene invalidation, never position or floor sensing.
 * The activity must stop this controller whenever its phone-camera view leaves the foreground.
 * Device axes deliberately stay fixed across display rotation: these values only compare motion
 * within one sensor session, and do not position AR markers or describe a compass heading.
 */
@MainThread
class PhoneMotionController(context: Context) : AutoCloseable {
    private val manager = context.applicationContext.getSystemService(SensorManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val mutablePose = MutableStateFlow<VisionPose?>(null)
    val pose: StateFlow<VisionPose?> = mutablePose.asStateFlow()

    private val matrix = FloatArray(9)
    private var listener: SensorEventListener? = null
    private var selectedSensor: Sensor? = null
    private var running = false
    private var closed = false
    private var generation = 0L
    private var sessionId = 0L
    private var lastSampleTimestampNs = 0L

    private val freshnessCheck = object : Runnable {
        override fun run() {
            if (!running || closed) return
            val now = SystemClock.elapsedRealtimeNanos()
            if (lastSampleTimestampNs != 0L &&
                (now < lastSampleTimestampNs || now - lastSampleTimestampNs > MAX_SAMPLE_AGE_NS)) {
                invalidatePose()
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    fun start() {
        requireMainThread()
        if (closed || running) return
        val sensorManager = manager ?: return
        mutablePose.value = null
        lastSampleTimestampNs = 0L
        sessionId = nextSessionId()
        val runGeneration = ++generation
        val runListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!isCurrent(runGeneration) || event.sensor != selectedSensor) return
                acceptSample(event)
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                // Game rotation has no magnetic heading/heading-accuracy estimate.
                if (isCurrent(runGeneration) && sensor == selectedSensor &&
                    sensor.type == Sensor.TYPE_ROTATION_VECTOR &&
                    accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
                    invalidatePose()
                }
            }
        }
        listener = runListener
        running = true
        // The game vector avoids magnetic-field disturbances; the standard vector is a fallback.
        for (type in intArrayOf(Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR)) {
            val sensor = sensorManager.getDefaultSensor(type) ?: continue
            selectedSensor = sensor
            val registered = runCatching {
                sensorManager.registerListener(runListener, sensor, SensorManager.SENSOR_DELAY_GAME, 0, handler)
            }.getOrDefault(false)
            if (registered) {
                handler.postDelayed(freshnessCheck, WATCHDOG_INTERVAL_MS)
                return
            }
            runCatching { sensorManager.unregisterListener(runListener) }
        }
        // A phone without a usable rotation sensor retains manual, captured-scene review.
        running = false
        listener = null
        selectedSensor = null
    }

    fun stop() {
        requireMainThread()
        generation++
        running = false
        handler.removeCallbacks(freshnessCheck)
        listener?.let { runCatching { manager?.unregisterListener(it) } }
        listener = null
        selectedSensor = null
        lastSampleTimestampNs = 0L
        mutablePose.value = null
    }

    override fun close() {
        requireMainThread()
        if (closed) return
        stop()
        closed = true
    }

    private fun acceptSample(event: SensorEvent) {
        val now = SystemClock.elapsedRealtimeNanos()
        val timestamp = event.timestamp
        if (timestamp <= 0L || timestamp > now || now - timestamp > MAX_SAMPLE_AGE_NS) {
            invalidatePose()
            return
        }
        // A delayed callback must not replace a more recent orientation.
        if (lastSampleTimestampNs != 0L && timestamp <= lastSampleTimestampNs) return
        if (lastSampleTimestampNs != 0L && timestamp - lastSampleTimestampNs > MAX_SAMPLE_AGE_NS) {
            invalidatePose()
        }
        if (event.values.size < 3 || event.values.take(minOf(4, event.values.size)).any { !it.isFinite() } ||
            (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR &&
                event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE)) {
            invalidatePose()
            return
        }
        // SensorEvent and its array are pooled by Android: consume synchronously, never retain them.
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        val nextPose = phoneCameraPose(matrix, sessionId)
        if (nextPose == null) {
            invalidatePose()
            return
        }
        lastSampleTimestampNs = timestamp
        mutablePose.value = nextPose
    }

    private fun invalidatePose() {
        // After a gap, even a similar angle must not resurrect a scene from the old sensor session.
        if (lastSampleTimestampNs != 0L) sessionId = nextSessionId()
        lastSampleTimestampNs = 0L
        mutablePose.value = null
    }

    private fun isCurrent(runGeneration: Long) = running && !closed && generation == runGeneration

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Phone motion must run on the main thread" }
    }

    private companion object {
        const val WATCHDOG_INTERVAL_MS = 250L
        const val MAX_SAMPLE_AGE_NS = 750_000_000L
        // A separate negative range also distinguishes phone sessions from positive Eye sessions.
        val sessions = AtomicLong(0L)
        fun nextSessionId(): Long = sessions.decrementAndGet()
    }
}
