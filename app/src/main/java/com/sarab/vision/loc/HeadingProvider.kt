package com.sarab.vision.loc

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.sarab.vision.core.CircularSmoother
import com.sarab.vision.core.HeadingSource
import com.sarab.vision.core.computeHeading
import com.sarab.vision.core.relativeBearing
import kotlin.math.abs

private const val TAG = "SarabHeading"

/**
 * Device compass heading, in degrees clockwise from TRUE north.
 *
 * The angular maths lives in `core/HeadingMath.kt` so it can be unit-tested
 * against hand-built rotation matrices; this class only bridges the sensor.
 *
 * Three things this gets right that a naive implementation does not:
 *
 *  1. **However the phone is held.** Held upright for the camera the heading
 *     comes from where the camera looks; laid flat like a map it comes from
 *     the top of the screen. Reading `getOrientation()[0]` alone breaks in
 *     the upright case, which is the main way this app is used.
 *  2. **True vs magnetic north.** GPS bearings are true-north; the
 *     magnetometer is not. The gap is several degrees, enough to aim someone
 *     at the wrong building across a campus.
 *  3. **Smoothing across the wrap.** Averaging raw degrees puts the mean of
 *     359 and 1 at 180, so the needle flips every time it crosses north.
 */
class HeadingProvider(private val context: Context) {

    /** Smoothed heading in degrees from true north, or null if unavailable. */
    var headingDegrees: Double? = null
        private set

    /** Which reference the current heading came from; useful for diagnostics. */
    var source: HeadingSource = HeadingSource.CAMERA
        private set

    /** True when the compass has reported itself unreliable. */
    var needsCalibration: Boolean = false
        private set

    /** Camera tilt from level: 0 = horizon, 1 = straight up or down. */
    var cameraTilt: Float = 1f
        private set

    /** Set from the GPS fix so we can convert magnetic -> true north. */
    var magneticDeclination: Float = 0f

    var onHeading: ((Double) -> Unit)? = null

    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null
    private var listening = false

    private val rotationMatrix = FloatArray(9)
    private val smoother = CircularSmoother(alpha = 0.18)
    private var wasFlat = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            handleRotationVector(event.values)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            val unreliable = accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
                accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW
            if (unreliable != needsCalibration) {
                needsCalibration = unreliable
                Log.w(TAG, "Compass accuracy changed: $accuracy (needsCalibration=$unreliable)")
            }
        }
    }

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

        // ROTATION_VECTOR fuses accelerometer, magnetometer and gyroscope,
        // which is far steadier than the magnetometer alone.
        rotationSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationSensor == null) {
            Log.e(TAG, "No rotation vector sensor on this device")
            return false
        }

        smoother.reset()
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

        val result = computeHeading(rotationMatrix, displayRotationDegrees(), wasFlat)
        wasFlat = result.source == HeadingSource.SCREEN_UP
        source = result.source
        cameraTilt = result.tilt

        val magnetic = result.degrees ?: return
        val trueNorth = (magnetic + magneticDeclination + 360.0) % 360.0
        val smoothed = smoother.next(trueNorth)

        // Only notify on a meaningful change, so the UI is not recomposed at
        // sensor rate on a device that already runs hot.
        val previous = headingDegrees
        headingDegrees = smoothed
        if (previous == null || abs(relativeBearing(previous, smoothed)) > 0.5) {
            onHeading?.invoke(smoothed)
        }
    }

    @Suppress("DEPRECATION")
    private fun displayRotationDegrees(): Int {
        val rotation = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                context.display?.rotation ?: Surface.ROTATION_0
            } else {
                (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                    ?.defaultDisplay?.rotation ?: Surface.ROTATION_0
            }
        } catch (e: Exception) {
            Surface.ROTATION_0
        }
        return when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }
}
