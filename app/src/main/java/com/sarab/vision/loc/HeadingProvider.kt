package com.sarab.vision.loc

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import kotlin.math.abs

private const val TAG = "SarabHeading"

/**
 * Device compass heading, in degrees clockwise from TRUE north.
 *
 * Two details matter here and are easy to get wrong:
 *
 *  1. **True vs magnetic north.** GPS bearings are relative to true north,
 *     but the magnetometer measures magnetic north. The difference
 *     (declination) is several degrees in much of the world -- enough to aim
 *     the user at the wrong building across a campus. The caller supplies the
 *     declination from its GPS fix and we correct for it.
 *
 *  2. **Screen rotation.** The sensor frame is fixed to the device, not the
 *     display. Without remapping, the heading is 90 degrees out in landscape.
 */
class HeadingProvider(private val context: Context) {

    /** Smoothed heading in degrees from true north, or null if unavailable. */
    var headingDegrees: Double? = null
        private set

    /** Set from the GPS fix so we can convert magnetic -> true north. */
    var magneticDeclination: Float = 0f

    var onHeading: ((Double) -> Unit)? = null

    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null
    private var listening = false

    private val rotationMatrix = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    /** Exponential smoothing state; raw compass output is very jittery. */
    private var smoothedSin = 0.0
    private var smoothedCos = 0.0
    private var haveSmoothed = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            handleRotationVector(event.values)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
                Log.w(TAG, "Compass reported unreliable - device may need a figure-8 calibration")
            }
        }
    }

    /** True when this device can report a heading at all. */
    fun isAvailable(): Boolean {
        val sm = sensorManager
            ?: context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        return sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null
    }

    fun start(): Boolean {
        if (listening) return true

        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        if (sm == null) {
            Log.e(TAG, "No SensorManager")
            return false
        }
        sensorManager = sm

        // ROTATION_VECTOR fuses accelerometer + magnetometer (+ gyroscope when
        // present), which is far steadier than reading the magnetometer alone.
        rotationSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationSensor == null) {
            Log.e(TAG, "No rotation vector sensor on this device")
            return false
        }

        sm.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        listening = true
        return true
    }

    fun stop() {
        if (!listening) return
        sensorManager?.unregisterListener(listener)
        listening = false
    }

    private fun handleRotationVector(values: FloatArray) {
        try {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
        } catch (e: IllegalArgumentException) {
            // Some devices hand back a longer vector than the API expects.
            val trimmed = FloatArray(4)
            System.arraycopy(values, 0, trimmed, 0, minOf(4, values.size))
            SensorManager.getRotationMatrixFromVector(rotationMatrix, trimmed)
        }

        // Remap for the current display rotation, otherwise the heading is
        // wrong by 90 degrees whenever the phone is held in landscape.
        val (axisX, axisY) = when (displayRotation()) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remapped)
        SensorManager.getOrientation(remapped, orientation)

        val magneticDeg = Math.toDegrees(orientation[0].toDouble())
        val trueDeg = (magneticDeg + magneticDeclination + 360.0) % 360.0

        // Smooth on the unit circle, not on the raw angle: averaging degrees
        // directly makes the needle swing wildly through the 359 -> 0 wrap.
        val rad = Math.toRadians(trueDeg)
        val s = kotlin.math.sin(rad)
        val c = kotlin.math.cos(rad)

        if (!haveSmoothed) {
            smoothedSin = s
            smoothedCos = c
            haveSmoothed = true
        } else {
            val alpha = 0.15
            smoothedSin = smoothedSin * (1 - alpha) + s * alpha
            smoothedCos = smoothedCos * (1 - alpha) + c * alpha
        }

        val result = (Math.toDegrees(kotlin.math.atan2(smoothedSin, smoothedCos)) + 360.0) % 360.0

        // Only notify on a meaningful change, to avoid recomposing the UI at
        // sensor rate on a device we already know runs hot.
        val previous = headingDegrees
        headingDegrees = result
        if (previous == null || abs(shortestDelta(previous, result)) > 0.5) {
            onHeading?.invoke(result)
        }
    }

    private fun shortestDelta(a: Double, b: Double): Double {
        var d = (b - a) % 360.0
        if (d > 180) d -= 360.0
        if (d <= -180) d += 360.0
        return d
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            context.display?.rotation ?: Surface.ROTATION_0
        } else {
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }
    } catch (e: Exception) {
        Surface.ROTATION_0
    }
}
