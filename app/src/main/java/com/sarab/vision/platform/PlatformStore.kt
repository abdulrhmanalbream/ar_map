package com.sarab.vision.platform

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

data class PlatformSnapshot(
    val enrolled: Boolean = false, val groupName: String = "", val name: String = "",
    val language: String = "ar", val sharing: Boolean = false, val running: Boolean = false,
    val message: String = "المجموعة غير مرتبطة", val lastSync: Long = 0,
    val alerts: List<String> = emptyList(), val helpPending: Boolean = false,
)

/** Private app storage; backup is disabled. Server secrets never enter this process. */
class PlatformStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sarab_platform", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(PlatformSnapshot(
        enrolled = token.isNotEmpty(), groupName = prefs.getString("group", "")!!,
        name = prefs.getString("name", "")!!, language = prefs.getString("language", "ar")!!,
        sharing = prefs.getBoolean("sharing", false), helpPending = prefs.getBoolean("help", false),
        message = if (token.isNotEmpty()) "ابدأ المزامنة لاستقبال تنبيهات المجموعة" else "المجموعة غير مرتبطة",
        alerts = readArray("alerts").let { a -> List(a.length()) { a.getJSONObject(it).toString() } },
    ))
    val state = mutable.asStateFlow()
    val snapshot get() = mutable.value
    val token: String get() = prefs.getString("token", "")!!
    val server: String get() = prefs.getString("server", DEFAULT_SERVER)!!
    @Volatile var cameraConnected = false
    @Volatile var imuTracking = false
    @Volatile var destinationName: String? = null

    fun enroll(url: String, name: String, language: String, response: JSONObject) {
        val group = response.getJSONObject("group").getString("name")
        prefs.edit().putString("server", validatedServer(url)).putString("name", name)
            .putString("language", language).putString("token", response.getString("token"))
            .putString("group", group).putBoolean("sharing", false).apply()
        mutable.value = snapshot.copy(enrolled = true, name = name, groupName = group, language = language,
            sharing = false, message = "تم الربط. مشاركة الموقع متوقفة حتى تفعّلها.")
    }
    fun language(value: String) { prefs.edit().putString("language", value).apply(); mutable.value = snapshot.copy(language = value) }
    fun sharing(value: Boolean) {
        prefs.edit().putBoolean("sharing", value).putBoolean("privacy_pending", !value).commit()
        mutable.value = snapshot.copy(sharing = value)
    }
    val privacyPending get() = prefs.getBoolean("privacy_pending", false)
    fun privacySynced() { prefs.edit().putBoolean("privacy_pending", false).apply() }
    fun status(message: String, running: Boolean = snapshot.running, synced: Boolean = false) {
        mutable.value = snapshot.copy(message = message, running = running,
            lastSync = if (synced) System.currentTimeMillis() else snapshot.lastSync)
    }
    @Synchronized fun queue(kind: String, payload: JSONObject): String {
        val id = UUID.randomUUID().toString()
        val queue = readArray("outbox")
        require(queue.length() < 100) { "التنبيهات المعلقة ممتلئة؛ أعد الاتصال أولاً" }
        queue.put(JSONObject().put("id", id).put("kind", kind).put("payload", payload))
        prefs.edit().putString("outbox", queue.toString()).commit()
        if (kind == "help") { prefs.edit().putBoolean("help", true).apply(); mutable.value = snapshot.copy(helpPending = true) }
        return id
    }
    @Synchronized fun pending(): List<JSONObject> = readArray("outbox").let { a -> List(a.length()) { a.getJSONObject(it) } }
    @Synchronized fun complete(id: String) {
        val kept = pending().filter { it.getString("id") != id }
        prefs.edit().putString("outbox", JSONArray(kept).toString()).commit()
    }
    fun clearHelp() { prefs.edit().putBoolean("help", false).apply(); mutable.value = snapshot.copy(helpPending = false) }
    @Synchronized fun receive(alert: JSONObject): Boolean {
        val id = alert.getString("id")
        val seen = prefs.getStringSet("seen", emptySet())!!.toMutableSet()
        if (id in seen) return false
        seen.add(id)
        val entries = (snapshot.alerts + alert.toString()).takeLast(30)
        prefs.edit().putStringSet("seen", seen.toList().takeLast(300).toSet())
            .putString("alerts", JSONArray(entries.map(::JSONObject)).toString()).commit()
        mutable.value = snapshot.copy(alerts = entries)
        return true
    }
    @Synchronized fun acknowledge(id: String) {
        if (pending().none { it.optString("kind") == "ack" && it.getJSONObject("payload").optString("alertId") == id }) {
            queue("ack", JSONObject().put("alertId", id))
        }
        val entries = snapshot.alerts.map { JSONObject(it) }.map { if (it.optString("id") == id) it.put("ackPending", true) else it }
        prefs.edit().putString("alerts", JSONArray(entries).toString()).commit()
        mutable.value = snapshot.copy(alerts = entries.map { it.toString() })
    }
    private fun readArray(key: String) = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrDefault(JSONArray())
    companion object {
        const val DEFAULT_SERVER = "https://adf-hackathon.com:9443"
        @Volatile private var instance: PlatformStore? = null
        fun get(context: Context): PlatformStore = instance ?: synchronized(this) { instance ?: PlatformStore(context).also { instance = it } }
        fun validatedServer(input: String): String {
            val uri = URI(input.trim().trimEnd('/'))
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty().isEmpty()) { "أدخل عنوان HTTPS فقط، دون مسار أو بيانات دخول" }
            return uri.toASCIIString()
        }
    }
}
