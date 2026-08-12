package com.sarab.vision.loc

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.sarab.vision.core.GpsFix
import com.sarab.vision.core.LatLng

private const val TAG = "SarabLocation"

/** Ignore fixes worse than this; they are useless for navigation. */
private const val MAX_USABLE_ACCURACY_M = 40f

/**
 * Offline GPS provider.
 *
 * Deliberately uses the platform [LocationManager] rather than Play Services'
 * FusedLocationProvider:
 *
 *  - it adds no dependency, keeping the APK small for mid-range phones
 *  - it talks to the GNSS hardware directly, so it works with no network,
 *    no Google account and no Play Services update -- which is the whole
 *    point of an offline campus app
 *
 * The trade is a slower first fix without assisted-GPS data. That is a
 * one-time cost outdoors, and the campus is open sky.
 */
class LocationProvider(private val context: Context) {

    /** Latest usable fix, or null before the first one arrives. */
    var lastFix: GpsFix? = null
        private set

    /** Called on the main thread whenever a usable fix arrives. */
    var onFix: ((GpsFix) -> Unit)? = null

    /** Called when GPS is disabled in system settings. */
    var onProviderDisabled: (() -> Unit)? = null

    private var manager: LocationManager? = null
    private var listening = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleLocation(location)
        }

        override fun onProviderDisabled(provider: String) {
            Log.w(TAG, "Provider disabled: $provider")
            if (provider == LocationManager.GPS_PROVIDER) {
                onProviderDisabled?.invoke()
            }
        }

        override fun onProviderEnabled(provider: String) {
            Log.i(TAG, "Provider enabled: $provider")
        }

        // Required on older API levels; deprecated but must still be present.
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    /** True when the system GPS provider is switched on. */
    fun isGpsEnabled(): Boolean {
        val lm = manager ?: context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        return try {
            lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Starts listening. Caller must already hold ACCESS_FINE_LOCATION.
     *
     * @return false if location could not be started at all
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (listening) return true

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            Log.e(TAG, "No LocationManager available")
            return false
        }
        manager = lm

        // Seed with the last known fix so the UI has something immediately
        // instead of showing "no signal" while the first real fix arrives.
        try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?.let { handleLocation(it, fromCache = true) }
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?.let { handleLocation(it, fromCache = true) }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing location permission", e)
            return false
        } catch (e: Exception) {
            Log.w(TAG, "Could not read last known location", e)
        }

        return try {
            // 1s / 1m: campus walking pace. Faster gains nothing and costs
            // battery on a device we already know runs hot.
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                1f,
                listener,
                context.mainLooper
            )

            // Also listen to the network provider where available. Indoors or
            // in a courtyard it can hold a rough fix while GPS re-acquires.
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    5000L,
                    5f,
                    listener,
                    context.mainLooper
                )
            }

            listening = true
            Log.i(TAG, "Location updates started (gpsEnabled=${isGpsEnabled()})")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing location permission", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start location updates", e)
            false
        }
    }

    fun stop() {
        if (!listening) return
        try {
            manager?.removeUpdates(listener)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop location updates", e)
        }
        listening = false
    }

    private fun handleLocation(location: Location, fromCache: Boolean = false) {
        val pos = LatLng(location.latitude, location.longitude)
        if (!pos.isValid) return

        // Some devices report 0 accuracy when they have no real estimate;
        // treat that as unknown rather than perfect.
        val accuracy = if (location.hasAccuracy() && location.accuracy > 0f) {
            location.accuracy
        } else {
            MAX_USABLE_ACCURACY_M
        }

        if (accuracy > MAX_USABLE_ACCURACY_M) {
            // Too vague to navigate with; keep waiting rather than pointing
            // the user in a confidently wrong direction.
            return
        }

        val fix = GpsFix(
            position = pos,
            accuracyMeters = accuracy,
            timestampMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                location.elapsedRealtimeMillis
            } else {
                location.time
            }
        )

        // A cached fix can be very old; accept it as a starting hint but never
        // let it overwrite a fresher live fix.
        if (fromCache && lastFix != null) return

        lastFix = fix
        onFix?.invoke(fix)
    }
}
