package com.sarab.vision.platform

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sarab.vision.wear.PhoneWearBridge
import kotlinx.coroutines.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/** A user-started trip session, with a visible stop control. Never starts on boot. */
class CompanionSyncService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: PlatformStore
    private lateinit var client: PlatformClient
    private lateinit var wear: PhoneWearBridge
    private lateinit var locations: LocationManager
    private var loop: Job? = null
    private var location: Location? = null
    private var listening = false
    private var stopping = false
    private var nextOutboxAttempt = 0L

    override fun onCreate() {
        super.onCreate()
        store = PlatformStore.get(this)
        client = PlatformClient(store)
        wear = PhoneWearBridge.get(this)
        locations = getSystemService(LocationManager::class.java)
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel("sarab_sync", "اتصال مجموعة المطوف الذكي", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel("sarab_alerts", "تنبيهات المجموعة", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSession(); return START_NOT_STICKY }
        if (store.token.isEmpty()) { stopSelf(); return START_NOT_STICKY }
        val locationType = if (store.snapshot.sharing && hasLocation()) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or locationType else 0
        try { ServiceCompat.startForeground(this, 9100, foregroundNotification(), type) }
        catch (_: Exception) { store.status("افتح التطبيق وامنح أذونات الجلسة ثم حاول مجدداً", false); stopSelf(); return START_NOT_STICKY }
        refreshLocation()
        if (loop?.isActive != true) loop = scope.launch {
            var failures = 0
            store.status("جارٍ الاتصال بالمجموعة…", true)
            while (isActive && !stopping) {
                try {
                    sync()
                    failures = 0
                    store.status("المجموعة متصلة", true, synced = true)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    failures++
                    wear.sendState(store.snapshot.groupName, false, isoTime(store.snapshot.lastSync))
                    store.status(if (e is PlatformException && e.status == 401) "انتهى الربط؛ أعد ربط المجموعة" else "الاتصال منقطع؛ الطلبات محفوظة للمحاولة التالية", true)
                    if (e is PlatformException && e.status == 401) { stopSelf(); break }
                }
                delay(if (failures == 0) 5_000L else (5_000L * (1L shl failures.coerceAtMost(4))).coerceAtMost(60_000L))
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun sync() {
        wear.refreshConnection()
        refreshLocation()
        if (store.privacyPending) {
            client.request("/device/privacy", JSONObject().put("sharingEnabled", false))
            store.privacySynced()
        }
        val events = wear.drainPendingEvents()
        val retry: (Exception) -> Unit = { failure ->
            nextOutboxAttempt = SystemClock.elapsedRealtime() + if (failure is PlatformException && failure.status == 429) 60_000L else 15_000L
        }
        if (SystemClock.elapsedRealtime() >= nextOutboxAttempt) deliverBatch(store.pending().take(6), send = { event ->
            val kind = event.getString("kind")
            val payload = event.getJSONObject("payload")
            if (kind == "ack") {
                val id = payload.getString("alertId")
                client.request("/device/alerts/$id/ack", JSONObject())
                store.ackSynced(id)
                getSystemService(NotificationManager::class.java).cancel(id.hashCode())
            } else {
                client.request("/device/alerts", JSONObject().put("kind", kind)
                    .put("message", payload.optString("message", "أحتاج مساعدة من المجموعة"))
                    .put("clientId", event.getString("id")))
            }
        }, complete = { store.complete(it.getString("id")) }, discard = {
            store.complete(it.getString("id")); store.discardNotice()
        }, retryLater = retry)
        val commands = events.filter { it.type == "help" || it.type == "ack" || it.payload.has("deliveredAlertId") }
        if (SystemClock.elapsedRealtime() >= nextOutboxAttempt) deliverBatch(commands.take(6), send = { event ->
            when (event.type) {
                "help" -> {
                    store.markHelp()
                    client.request("/device/alerts", JSONObject().put("kind", "help")
                        .put("message", "طلب مساعدة من الساعة").put("clientId", event.id))
                }
                "ack" -> {
                    val id = event.payload.getString("alertId")
                    client.request("/device/alerts/$id/ack", JSONObject())
                    store.ackSynced(id)
                    getSystemService(NotificationManager::class.java).cancel(id.hashCode())
                }
                "status" -> event.payload.optString("deliveredAlertId").takeIf { it.isNotEmpty() }?.let {
                    client.request("/device/alerts/$it/delivered", JSONObject())
                }
            }
        }, complete = { wear.completeEvent(it.id) }, discard = {
            wear.completeEvent(it.id); store.discardNotice()
        }, retryLater = retry)
        val snapshot = wear.snapshot.value
        val battery = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val fresh = location?.takeIf { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos in 0..60_000_000_000L }
        val shared = if (store.snapshot.sharing && fresh != null) JSONObject()
            .put("lat", fresh.latitude).put("lng", fresh.longitude).put("accuracyM", fresh.accuracy)
            .put("recordedAt", isoTime(fresh.time)) else JSONObject.NULL
        client.request("/device/telemetry", JSONObject().put("sharingEnabled", store.snapshot.sharing)
            .put("location", shared).put("batteryPercent", battery.takeIf { it in 0..100 } ?: JSONObject.NULL)
            .put("cameraConnected", store.cameraConnected).put("imuTracking", store.imuTracking)
            .put("watchConnected", snapshot.connected).put("destinationName", store.destinationName ?: JSONObject.NULL)
            .put("lap", platformLap(snapshot.lap) ?: JSONObject.NULL)
            .put("status", if (store.snapshot.helpPending || events.any { it.type == "help" }) "needs_help" else "active"))
        events.filterNot { it in commands }.forEach { wear.completeEvent(it.id) }
        val incoming = client.request("/device/alerts").getJSONArray("alerts")
        for (i in 0 until incoming.length()) {
            val alert = incoming.getJSONObject(i)
            val id = alert.getString("id")
            if (store.receive(alert)) showAlert(alert)
            // Watch keeps its own persistent deduplication; resend until its explicit receipt.
            wear.sendAlert(id, alert.getString("kind"), alert.getString("message"), alert.optString("sourceName"), alert.getString("createdAt"))
            if (!store.deliveryConfirmed(id)) {
                client.request("/device/alerts/$id/delivered", JSONObject())
                store.confirmDelivery(id)
            }
        }
        wear.sendState(store.snapshot.groupName, true, isoTime(System.currentTimeMillis()))
    }

    private fun hasLocation() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    private fun refreshLocation() {
        val should = store.snapshot.sharing && hasLocation()
        if (should == listening) return
        locations.removeUpdates(this)
        listening = false
        location = null
        if (should) {
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                // Stationary users still need fresh fixes; a distance gate made them vanish after 60s.
                runCatching { if (locations.isProviderEnabled(provider)) { locations.requestLocationUpdates(provider, 5_000L, 0f, this, Looper.getMainLooper()); listening = true } }
            }
        }
    }
    override fun onLocationChanged(value: Location) { if (value.hasAccuracy() && value.accuracy > 0) location = value }
    @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    private fun foregroundNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, CompanionActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, CompanionSyncService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "sarab_sync").setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("المطوف الذكي · ${store.snapshot.groupName}")
            .setContentText(if (store.snapshot.sharing) "مشاركة الموقع وتنبيهات المجموعة تعمل" else "تنبيهات المجموعة تعمل · الموقع غير مشارك")
            .setOngoing(true).setContentIntent(open).addAction(0, "إنهاء الجلسة", stop).build()
    }
    private fun showAlert(alert: JSONObject) {
        val id = alert.getString("id")
        val open = PendingIntent.getActivity(this, id.hashCode(), Intent(this, CompanionActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val ack = PendingIntent.getBroadcast(this, id.hashCode(), Intent(this, AlertAckReceiver::class.java).putExtra("alertId", id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "sarab_alerts").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (alert.optString("kind") == "help") "طلب مساعدة · ${alert.optString("sourceName")}" else "تنبيه المجموعة")
            .setContentText(alert.getString("message")).setStyle(NotificationCompat.BigTextStyle().bigText(alert.getString("message")))
            .setContentIntent(open).addAction(0, "تم الاستلام", ack).setAutoCancel(true).build()
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            getSystemService(NotificationManager::class.java).notify(id.hashCode(), notification)
        }
    }
    private fun stopSession() {
        if (stopping) return
        stopping = true
        loop?.cancel()
        store.sharing(false)
        scope.launch {
            runCatching { withTimeout(5_000) { client.request("/device/privacy", JSONObject().put("sharingEnabled", false)); store.privacySynced() } }
            stopSelf()
        }
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        store.sharing(false)
        store.status("انتهت جلسة المزامنة؛ افتح التطبيق لتشغيل جلسة جديدة", false)
        stopSelf()
    }
    override fun onDestroy() {
        locations.removeUpdates(this)
        scope.cancel()
        wear.sendState(store.snapshot.groupName, false, isoTime(System.currentTimeMillis()))
        store.status(if (store.privacyPending) "المزامنة متوقفة؛ إلغاء الموقع بانتظار الاتصال" else "المزامنة متوقفة", false)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val STOP = "com.sarab.vision.STOP_SYNC"
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, CompanionSyncService::class.java))
        fun isoTime(time: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(time))
    }
}

class AlertAckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("alertId") ?: return
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) return
        PlatformStore.get(context).acknowledge(id)
        context.getSystemService(NotificationManager::class.java).cancel(id.hashCode())
        runCatching { CompanionSyncService.start(context) }
    }
}
