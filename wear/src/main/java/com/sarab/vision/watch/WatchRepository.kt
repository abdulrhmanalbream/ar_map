package com.sarab.vision.watch

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.sarab.vision.wear.shared.LapCounter
import com.sarab.vision.wear.shared.WatchAlert
import com.sarab.vision.wear.shared.WearProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class WatchRepository private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("sarab_watch", Context.MODE_PRIVATE)
    private val data = Wearable.getDataClient(context)
    private val messages = Wearable.getMessageClient(context)
    private val capabilities = Wearable.getCapabilityClient(context)
    @Volatile private var nodes: Set<String> = emptySet()
    @Volatile private var refreshing = false
    val connected: Boolean get() = nodes.isNotEmpty()
    val mode: String get() = prefs.getString("mode", "tawaf").orEmpty().takeIf { it == "sai" } ?: "tawaf"
    val groupName: String get() = prefs.getString("groupName", "").orEmpty()
    val cloudConnected: Boolean get() = prefs.getBoolean("cloudConnected", false) && connected &&
        System.currentTimeMillis() - prefs.getLong("stateReceivedAt", 0) in 0..60_000
    val helpStatus: String get() = prefs.getString("helpStatus", "").orEmpty()
    val pendingCount: Int get() = objects("outbox").size

    @Synchronized fun lap(mode: String = this.mode): LapCounter = prefs.getString("lap_$mode", null)?.let {
        runCatching { WearProtocol.lap(JSONObject(it)) }.getOrNull()
    } ?: LapCounter(mode).also { saveLap(it) }

    @Synchronized fun chooseMode(mode: String) {
        if (mode != "tawaf" && mode != "sai") return
        prefs.edit().putString("mode", mode).apply()
        val value = lap(mode).let { it.copy(revision = it.revision + 1) }
        saveLap(value)
        publishLap(value)
        changed()
    }

    @Synchronized fun increment() = updateLap(lap().increment())
    @Synchronized fun undo() = updateLap(lap().undo())
    @Synchronized fun reset() = updateLap(lap().reset())
    private fun updateLap(value: LapCounter) {
        if (value == lap()) return
        // The local counter keeps working offline even if transport is unavailable.
        saveLap(value)
        publishLap(value)
        changed()
    }
    private fun saveLap(value: LapCounter) = prefs.edit().putString("lap_${value.mode}", WearProtocol.lapJson(value).toString()).commit()
    private fun publishLap(value: LapCounter) {
        val serialized = WearProtocol.lapJson(value).toString()
        if (prefs.getString("queuedLap_${value.mode}", null) == serialized) return
        if (enqueue(WearProtocol.newEvent("lap", JSONObject(serialized)))) {
            prefs.edit().putString("queuedLap_${value.mode}", serialized).apply()
        }
    }

    @Synchronized fun requestHelp(): Boolean {
        val json = WearProtocol.newEvent("help", JSONObject().put("message", "أحتاج مساعدة من مجموعتي. أُرسل الطلب من الساعة."))
        if (!enqueue(json)) return false
        prefs.edit().putString("lastHelpId", json.getString("id"))
            .putString("helpStatus", "طلبك محفوظ · ينتظر استلام الجوال").apply()
        changed()
        return true
    }

    @Synchronized fun alerts(): List<WatchAlert> = objects("alerts").mapNotNull {
        WearProtocol.alert(it.toString().toByteArray(Charsets.UTF_8))
    }

    @Synchronized fun acknowledge(id: String): Boolean {
        if (alerts().none { it.id == id }) return false
        val ack = WearProtocol.newEvent("ack", JSONObject().put("alertId", id)).put("id", stableId("ack", id))
        if (!enqueue(ack)) return false
        saveObjects("alerts", objects("alerts").filterNot { it.optString("id") == id })
        WatchNotifications.cancel(context, id)
        changed()
        return true
    }

    @Synchronized fun receiveAlert(bytes: ByteArray?) {
        val alert = WearProtocol.alert(bytes) ?: return
        val seen = ids("seenAlerts")
        if (alert.id in seen) { sendDelivery(alert.id); return }
        val inbox = objects("alerts")
        if (inbox.size >= WearProtocol.MAX_PENDING) return
        // Commit before the physical vibration. Repeated messages/data sync never re-alert.
        if (!prefs.edit().putString("alerts", JSONArray(inbox + WearProtocol.alertJson(alert)).toString())
                .putString("seenAlerts", JSONArray((seen + alert.id).takeLast(2048)).toString()).commit()) return
        WatchNotifications.show(context, alert)
        sendDelivery(alert.id)
        changed()
    }

    @Synchronized fun receiveReceipt(bytes: ByteArray?) {
        val receipt = WearProtocol.parse(bytes) ?: return
        val id = receipt.opt("id") as? String ?: return
        if (!WearProtocol.validId(id)) return
        val rejected = receipt.optString("disposition") == "discarded_binding_changed"
        if (rejected && id == prefs.getString("lastHelpId", "")) {
            prefs.edit().putString("helpStatus", "أُلغي الطلب القديم لتغيّر ربط المجموعة؛ أرسل طلبًا جديدًا إذا احتجت.").apply()
            changed()
        }
        val existing = objects("outbox")
        if (existing.none { it.optString("id") == id }) return
        if (!saveObjects("outbox", existing.filterNot { it.optString("id") == id })) return
        deleteData(WearProtocol.OUTBOX + id)
        if (!rejected && id == prefs.getString("lastHelpId", "")) prefs.edit()
            .putString("helpStatus", "وصل طلبك للجوال · يرسله للمجموعة عند الاتصال").apply()
        changed()
    }

    @Synchronized fun receiveState(bytes: ByteArray?) {
        val value = WearProtocol.parse(bytes) ?: return
        val name = value.opt("groupName") as? String ?: return
        val synced = value.opt("lastSyncedAt") as? String ?: return
        if (name.length > 160 || synced.length > 64 || value.opt("connected") !is Boolean) return
        if (name == prefs.getString("groupName", "") && synced == prefs.getString("lastSyncedAt", "") &&
            value.getBoolean("connected") == prefs.getBoolean("cloudConnected", false)) return
        // Re-reading a persistent DataItem must not make yesterday's cloud status fresh.
        prefs.edit().putString("groupName", name).putBoolean("cloudConnected", value.getBoolean("connected"))
            .putString("lastSyncedAt", synced).putLong("stateReceivedAt", System.currentTimeMillis()).apply()
        changed()
    }

    /** Durable DataItems survive disconnection; messages provide the low-latency path. */
    @Synchronized private fun enqueue(json: JSONObject): Boolean {
        if (WearProtocol.event(json.toString().toByteArray(Charsets.UTF_8)) == null) return false
        var queue = objects("outbox")
        val obsolete = if (json.optString("type") == "lap") queue.filter {
            it.optString("type") == "lap" && it.optString("mode") == json.optString("mode")
        } else if (json.optString("type") == "status" && !json.has("deliveredAlertId")) queue.filter {
            it.optString("type") == "status" && !it.has("deliveredAlertId")
        } else emptyList()
        queue = queue - obsolete.toSet()
        if (queue.any { it.optString("id") == json.optString("id") }) return true
        if (queue.size >= WearProtocol.MAX_PENDING) return false
        if (!saveObjects("outbox", queue + json)) return false
        obsolete.forEach { deleteData(WearProtocol.OUTBOX + it.getString("id")) }
        send(json)
        return true
    }

    fun refreshConnection() {
        if (refreshing) return
        refreshing = true
        GoogleApiAvailability.getInstance().checkApiAvailability(data, messages, capabilities)
            .addOnSuccessListener {
                capabilities.getCapability("sarab_phone", CapabilityClient.FILTER_REACHABLE)
                    .addOnSuccessListener { capability ->
                        nodes = capability.nodes.map { it.id }.toSet()
                        synchronized(this) {
                            publishLap(lap())
                            objects("outbox").forEach(::send)
                        }
                        readPersistentMessages()
                        changed()
                    }.addOnFailureListener { nodes = emptySet(); changed() }
                    .addOnCompleteListener { refreshing = false }
            }.addOnFailureListener { refreshing = false; nodes = emptySet(); changed() }
    }

    fun sendStatus() {
        val battery = batteryPercent() ?: return
        enqueue(WearProtocol.newEvent("status", JSONObject().put("batteryPercent", battery)))
    }

    private fun sendDelivery(id: String) {
        if (id in ids("deliveryReported")) return
        val fields = WearProtocol.newEvent("status", JSONObject().put("deliveredAlertId", id))
            .put("id", stableId("delivered", id))
        batteryPercent()?.let { fields.put("batteryPercent", it) }
        if (enqueue(fields)) prefs.edit().putString("deliveryReported",
            JSONArray((ids("deliveryReported") + id).takeLast(2048)).toString()).apply()
    }
    private fun batteryPercent(): Int? = context.getSystemService(BatteryManager::class.java)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
    private fun send(json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        data.putDataItem(PutDataRequest.create(WearProtocol.OUTBOX + json.getString("id")).setData(bytes).setUrgent())
        nodes.forEach { messages.sendMessage(it, WearProtocol.EVENT, bytes) }
    }

    private fun readPersistentMessages() {
        data.getDataItems(Uri.parse("wear://*/sarab/watch/"), DataClient.FILTER_PREFIX)
            .addOnSuccessListener { items ->
                try { for (item in items) when {
                    item.uri.path?.startsWith(WearProtocol.RECEIPTS) == true -> receiveReceipt(item.data)
                    item.uri.path?.startsWith(WearProtocol.ALERTS) == true -> receiveAlert(item.data)
                    item.uri.path == WearProtocol.STATE -> receiveState(item.data)
                } } finally { items.release() }
            }
    }
    private fun deleteData(path: String) { data.deleteDataItems(Uri.parse("wear://*$path")) }
    private fun changed() { context.sendBroadcast(Intent(ACTION_CHANGED).setPackage(context.packageName)) }
    private fun stableId(kind: String, id: String) = UUID.nameUUIDFromBytes("$kind:$id".toByteArray(Charsets.UTF_8)).toString()
    private fun array(key: String) = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrDefault(JSONArray())
    private fun objects(key: String): List<JSONObject> = array(key).let { value ->
        (0 until value.length()).mapNotNull { value.optJSONObject(it) }
    }
    private fun ids(key: String) = array(key).let { value -> (0 until value.length()).map { value.optString(it) } }
    private fun saveObjects(key: String, values: List<JSONObject>) = prefs.edit().putString(key, JSONArray(values).toString()).commit()

    companion object {
        const val ACTION_CHANGED = "com.sarab.vision.watch.CHANGED"
        @Volatile private var instance: WatchRepository? = null
        fun get(context: Context): WatchRepository = instance ?: synchronized(this) {
            instance ?: WatchRepository(context.applicationContext).also { instance = it }
        }
    }
}
