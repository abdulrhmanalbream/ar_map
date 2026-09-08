package com.sarab.vision.wear

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.sarab.vision.wear.shared.WatchAlert
import com.sarab.vision.wear.shared.WatchEvent
import com.sarab.vision.wear.shared.WearProtocol
import com.sarab.vision.wear.shared.isNewerLap
import com.sarab.vision.wear.shared.isGroupScopedWatchEvent
import com.sarab.vision.wear.shared.allowedAfterCloudBindingChange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class PhoneWearSnapshot(
    val connected: Boolean = false,
    val batteryPercent: Int? = null,
    val lap: JSONObject? = null,
    val lastSeenAt: Long = 0,
    val pendingEventCount: Int = 0,
)

/** No backend credentials cross the Data Layer. Root's platform service owns cloud I/O. */
class PhoneWearBridge private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("sarab_phone_wear", Context.MODE_PRIVATE)
    private val data = Wearable.getDataClient(context)
    private val messages = Wearable.getMessageClient(context)
    private val capabilities = Wearable.getCapabilityClient(context)
    private val mutableState = MutableStateFlow(readSnapshot())
    val state: StateFlow<PhoneWearSnapshot> = mutableState.asStateFlow()
    val snapshot: StateFlow<PhoneWearSnapshot> = state
    @Volatile var onEventsAvailable: (() -> Unit)? = null
    @Volatile private var nodeIds: Set<String> = emptySet()
    @Volatile private var refreshing = false

    init { refreshConnection() }

    /** Non-destructive: acknowledge each entry only after successful server submission. */
    @Synchronized fun drainPendingEvents(): List<WatchEvent> = objects("events").mapNotNull {
        WearProtocol.event(it.toString().toByteArray(Charsets.UTF_8))
    }

    @Synchronized fun completeEvent(id: String) {
        if (!WearProtocol.validId(id)) return
        saveObjects("events", objects("events").filter { it.optString("id") != id })
        publish()
    }

    /** A revoked login or fresh enrollment must never carry old group commands forward. */
    @Synchronized fun clearCloudBinding() {
        val cutoff = maxOf(System.currentTimeMillis(), prefs.getLong("bindingCutoffMillis", 0) + 1)
        val pending = objects("events")
        val discarded = pending.filter { isGroupScopedWatchEvent(it.optString("type"), it.has("deliveredAlertId")) }
        val discardedIds = discarded.map { it.getString("id") }
        check(prefs.edit().putLong("bindingCutoffMillis", cutoff)
            .putString("events", JSONArray(pending - discarded.toSet()).toString())
            .putString("alerts", "[]")
            .putString("bindingRejected", JSONArray((ids("bindingRejected") + discardedIds).distinct().takeLast(256)).toString())
            .commit()) { "Unable to persist watch binding boundary" }
        // IDs and lap sessions stay intact. Only old cloud work is discarded.
        discardedIds.forEach { receipt(it, "") }
        data.deleteDataItems(Uri.parse("wear://*${WearProtocol.ALERTS}"), DataClient.FILTER_PREFIX)
        sendState("", false, WearProtocol.utcNow())
        publish()
    }

    /** Safe on the UI thread. Connection means a reachable Sarab watch app, not merely Bluetooth. */
    fun refreshConnection() {
        if (refreshing) return
        refreshing = true
        GoogleApiAvailability.getInstance().checkApiAvailability(data, messages, capabilities)
            .addOnSuccessListener {
                capabilities.addLocalCapability("sarab_phone")
                capabilities.getCapability("sarab_watch", CapabilityClient.FILTER_REACHABLE)
                    .addOnSuccessListener { capability ->
                        nodeIds = capability.nodes.map { it.id }.toSet()
                        synchronized(this) { mutableState.value = readSnapshot().copy(connected = nodeIds.isNotEmpty()) }
                        flushAlerts()
                        replayInbox()
                    }.addOnFailureListener { setDisconnected() }
                    .addOnCompleteListener { refreshing = false }
            }.addOnFailureListener { refreshing = false; setDisconnected() }
    }

    /** Returns false only for invalid input or a full local queue. Accepted is not watch delivery. */
    @Synchronized fun sendAlert(id: String, kind: String, message: String, sourceName: String, createdAt: String): Boolean {
        val alert = WatchAlert(id, kind, message, sourceName, createdAt)
        val json = WearProtocol.alertJson(alert)
        if (WearProtocol.alert(json.toString().toByteArray(Charsets.UTF_8)) == null) return false
        if (ids("delivered").contains(id)) return true
        val outgoing = objects("alerts")
        if (outgoing.none { it.optString("id") == id }) {
            if (outgoing.size >= WearProtocol.MAX_PENDING) return false
            if (!saveObjects("alerts", outgoing + json)) return false
        }
        sendAlertData(json)
        return true
    }

    fun sendState(groupName: String, connected: Boolean, lastSyncedAt: String) {
        val json = JSONObject().put("groupName", groupName.take(160)).put("connected", connected)
            .put("lastSyncedAt", lastSyncedAt.take(64))
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        data.putDataItem(PutDataRequest.create(WearProtocol.STATE).setData(bytes))
        nodeIds.forEach { messages.sendMessage(it, WearProtocol.STATE, bytes) }
    }

    @Synchronized internal fun receiveEvent(bytes: ByteArray?, sourceNode: String) {
        val event = WearProtocol.event(bytes) ?: return
        val known = ids("received")
        if (!allowedAfterCloudBindingChange(event.type, event.payload.has("deliveredAlertId"),
                WearProtocol.createdAtMillis(event.payload), prefs.getLong("bindingCutoffMillis", 0))) {
            if (prefs.edit().putString("received", JSONArray((known + event.id).distinct().takeLast(256)).toString())
                    .putString("bindingRejected", JSONArray((ids("bindingRejected") + event.id).distinct().takeLast(256)).toString())
                    .commit()) receipt(event.id, sourceNode)
            return
        }
        if (event.id in known) { receipt(event.id, sourceNode); return }
        val incomingLap = if (event.type == "lap") WearProtocol.lap(event.payload) else null
        if (incomingLap != null) {
            val current = prefs.getString("lap_${incomingLap.mode}", null)?.let {
                runCatching { WearProtocol.lap(JSONObject(it)) }.getOrNull()
            }
            if (!isNewerLap(incomingLap, current)) {
                // Reject before telemetry coalescing: a delayed packet must not
                // replace the newer pending snapshot even if its ID is unseen.
                if (prefs.edit().putString("received", JSONArray((known + event.id).takeLast(256)).toString())
                        .putLong("lastSeenAt", System.currentTimeMillis()).commit()) {
                    receipt(event.id, sourceNode)
                    publish()
                }
                return
            }
        }
        var pending = objects("events")
        // Latest lap/status supersedes older telemetry, while help/ack are never evicted.
        if (event.type == "lap") pending = pending.filterNot {
            it.optString("type") == "lap" && it.optString("mode") == event.payload.optString("mode")
        }
        if (event.type == "status" && !event.payload.has("deliveredAlertId")) pending = pending.filterNot {
            it.optString("type") == "status" && !it.has("deliveredAlertId")
        }
        if (pending.size >= WearProtocol.MAX_PENDING) return
        val edit = prefs.edit().putString("events", JSONArray(pending + event.payload).toString())
            .putString("received", JSONArray((known + event.id).takeLast(256)).toString())
            .putLong("lastSeenAt", System.currentTimeMillis())
        if (event.type == "lap") {
            val incoming = incomingLap ?: return
            edit.putString("lap_${incoming.mode}", event.payload.toString()).putString("lap", event.payload.toString())
        }
        if (event.type == "status" && event.payload.has("batteryPercent")) edit.putInt("battery", event.payload.getInt("batteryPercent"))
        if (!edit.commit()) return
        if (event.type == "status" && event.payload.has("deliveredAlertId")) {
            val alertId = event.payload.getString("deliveredAlertId")
            saveObjects("alerts", objects("alerts").filterNot { it.optString("id") == alertId })
            prefs.edit().putString("delivered", JSONArray((ids("delivered") + alertId).distinct().takeLast(256)).toString()).commit()
            deleteData(WearProtocol.ALERTS + alertId)
        }
        // Receipt means durable phone storage; it does not mean server acceptance or user acknowledgement.
        receipt(event.id, sourceNode)
        publish()
        onEventsAvailable?.invoke()
        context.sendBroadcast(Intent(ACTION_EVENT_AVAILABLE).setPackage(context.packageName))
    }

    private fun receipt(id: String, node: String) {
        val rejected = synchronized(this) { id in ids("bindingRejected") }
        val bytes = JSONObject().put("id", id).put("disposition",
            if (rejected) "discarded_binding_changed" else "stored_on_phone").toString().toByteArray(Charsets.UTF_8)
        (if (node.isNotBlank()) setOf(node) else nodeIds).forEach { messages.sendMessage(it, WearProtocol.RECEIVED, bytes) }
        data.putDataItem(PutDataRequest.create(WearProtocol.RECEIPTS + id).setData(bytes).setUrgent())
        // Receipts are tiny and bounded; deleting an old receipt cannot delete the original event.
        synchronized(this) {
            val old = ids("receipts")
            val next = (old + id).distinct().takeLast(128)
            prefs.edit().putString("receipts", JSONArray(next).toString()).apply()
            (old - next.toSet()).forEach { deleteData(WearProtocol.RECEIPTS + it) }
        }
    }

    private fun replayInbox() {
        data.getDataItems(Uri.parse("wear://*${WearProtocol.OUTBOX}"), DataClient.FILTER_PREFIX)
            .addOnSuccessListener { buffer ->
                try { for (item in buffer) receiveEvent(item.data, item.uri.host.orEmpty()) }
                finally { buffer.release() }
            }
    }

    @Synchronized private fun flushAlerts() = objects("alerts").forEach(::sendAlertData)
    private fun sendAlertData(json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        data.putDataItem(PutDataRequest.create(WearProtocol.ALERTS + json.getString("id")).setData(bytes).setUrgent())
        nodeIds.forEach { messages.sendMessage(it, WearProtocol.ALERT, bytes) }
    }
    private fun deleteData(path: String) { data.deleteDataItems(Uri.parse("wear://*$path")) }
    @Synchronized private fun setDisconnected() { nodeIds = emptySet(); mutableState.value = readSnapshot() }
    @Synchronized private fun publish() { mutableState.value = readSnapshot().copy(connected = nodeIds.isNotEmpty()) }
    private fun readSnapshot() = PhoneWearSnapshot(
        connected = false,
        batteryPercent = prefs.getInt("battery", -1).takeIf { it in 0..100 },
        lap = prefs.getString("lap", null)?.let { runCatching { JSONObject(it) }.getOrNull() },
        lastSeenAt = prefs.getLong("lastSeenAt", 0), pendingEventCount = objects("events").size,
    )
    private fun objects(key: String): List<JSONObject> = array(key).let { value ->
        (0 until value.length()).mapNotNull { value.optJSONObject(it) }
    }
    private fun ids(key: String): List<String> = array(key).let { value ->
        (0 until value.length()).map { value.optString(it) }.filter(WearProtocol::validId)
    }
    private fun array(key: String) = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrDefault(JSONArray())
    private fun saveObjects(key: String, values: List<JSONObject>) = prefs.edit().putString(key, JSONArray(values).toString()).commit()

    companion object {
        const val ACTION_EVENT_AVAILABLE = "com.sarab.vision.wear.EVENT_AVAILABLE"
        @Volatile private var instance: PhoneWearBridge? = null
        fun get(context: Context): PhoneWearBridge = instance ?: synchronized(this) {
            instance ?: PhoneWearBridge(context.applicationContext).also { instance = it }
        }
    }
}
