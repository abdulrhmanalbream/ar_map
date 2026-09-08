package com.sarab.vision.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class PlatformException(val status: Int, message: String) : Exception(message)

class PlatformClient(private val store: PlatformStore) {
    suspend fun request(path: String, body: JSONObject? = null, url: String = store.server, authenticated: Boolean = true): JSONObject = withContext(Dispatchers.IO) {
        require(path.startsWith("/") && !path.contains(".."))
        val connection = URL(PlatformStore.validatedServer(url) + "/api/v1" + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = if (body == null) "GET" else "POST"
            connection.setRequestProperty("Accept", "application/json")
            if (authenticated) {
                check(store.token.isNotEmpty()) { "اربط المجموعة أولاً" }
                connection.setRequestProperty("Authorization", "Bearer ${store.token}")
            }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytesLimited(1_048_576) } ?: byteArrayOf()
            val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrDefault(JSONObject())
            if (code !in 200..299) throw PlatformException(code, when (code) {
                401, 403 -> "تعذّر التحقق من الربط أو رمز المجموعة"
                429 -> "طلبات كثيرة؛ حاول بعد قليل"
                else -> "تعذّر الاتصال بالخدمة ($code)"
            })
            json
        } finally { connection.disconnect() }
    }
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { "استجابة الخدمة أكبر من الحد المسموح" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
