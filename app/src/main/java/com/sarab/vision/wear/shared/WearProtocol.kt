package com.sarab.vision.wear.shared

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class WatchEvent(val id: String, val type: String, val payload: JSONObject)
data class WatchAlert(val id: String, val kind: String, val message: String,
    val sourceName: String, val createdAt: String)

object WearProtocol {
    const val EVENT = "/sarab/watch/event"
    const val OUTBOX = "/sarab/watch/outbox/"
    const val ALERT = "/sarab/watch/alert"
    const val ALERTS = "/sarab/watch/alerts/"
    const val STATE = "/sarab/watch/state"
    const val RECEIVED = "/sarab/watch/received"
    const val RECEIPTS = "/sarab/watch/receipts/"
    const val MAX_BYTES = 16_384
    const val MAX_PENDING = 64

    fun validId(value: String) = value.matches(Regex("[A-Za-z0-9._:-]{1,128}"))
    fun parse(bytes: ByteArray?): JSONObject? = if (bytes == null || bytes.isEmpty() || bytes.size > MAX_BYTES) null
        else runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()

    fun event(bytes: ByteArray?): WatchEvent? {
        val json = parse(bytes) ?: return null
        val id = json.optString("id")
        val type = json.optString("type")
        if (!validId(id)) return null
        when (type) {
            "lap" -> if (lap(json) == null || json.optString("confidence") !in listOf("manual", "estimated") ||
                strictLong(json, "target") != 7L) return null
            "help" -> if (json.optString("message").length !in 1..500) return null
            "ack" -> if (!validId(json.optString("alertId"))) return null
            "status" -> {
                val battery = strictLong(json, "batteryPercent")
                if (json.has("batteryPercent") && (battery == null || battery !in 0..100)) return null
                if (battery == null && !json.has("deliveredAlertId")) return null
                if (json.has("deliveredAlertId") && !validId(json.optString("deliveredAlertId"))) return null
            }
            else -> return null
        }
        return WatchEvent(id, type, json)
    }

    fun lap(json: JSONObject): LapCounter? {
        val mode = json.optString("mode")
        val session = json.optString("sessionId")
        val count = strictLong(json, "count") ?: return null
        val revision = strictLong(json, "revision") ?: return null
        val started = strictLong(json, "startedAt") ?: return null
        if (mode !in listOf("tawaf", "sai") || !validId(session) || count !in 0..7 || revision < 0 || started < 0) return null
        return LapCounter(mode, count.toInt(), session, revision, started)
    }

    fun lapJson(lap: LapCounter) = JSONObject().put("mode", lap.mode).put("count", lap.count)
        .put("target", 7).put("confidence", "manual").put("sessionId", lap.sessionId)
        .put("revision", lap.revision).put("startedAt", lap.startedAt)

    fun newEvent(type: String, fields: JSONObject = JSONObject()): JSONObject = fields
        .put("id", UUID.randomUUID().toString()).put("type", type)

    fun alert(bytes: ByteArray?): WatchAlert? {
        val json = parse(bytes) ?: return null
        val id = json.optString("id")
        val kind = json.optString("kind")
        val message = json.optString("message")
        val source = json.optString("sourceName")
        val created = json.optString("createdAt")
        if (!validId(id) || kind !in listOf("help", "regroup", "message") || message.length !in 1..1000 ||
            source.length > 160 || created.length !in 1..64) return null
        return WatchAlert(id, kind, message, source, created)
    }

    fun alertJson(alert: WatchAlert) = JSONObject().put("id", alert.id).put("kind", alert.kind)
        .put("message", alert.message).put("sourceName", alert.sourceName).put("createdAt", alert.createdAt)

    fun utcNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(java.util.Date())

    private fun strictLong(json: JSONObject, key: String): Long? = when (val value = json.opt(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }
}
