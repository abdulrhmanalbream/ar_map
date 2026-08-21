package com.sarab.vision.data

import android.util.Log
import com.sarab.vision.core.LatLng
import com.sarab.vision.core.OsmWay
import com.sarab.vision.core.OsmWayTags
import com.sarab.vision.core.PathNetwork
import com.sarab.vision.core.buildNetworkFromWays
import com.sarab.vision.core.overpassQuery
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "SarabOverpass"

/**
 * Mirrors, tried in order.
 *
 * The main instance is frequently rate-limited or busy -- it serves the whole
 * world for free -- and a single-endpoint import that fails half the time
 * would be worse than no feature at all.
 */
private val ENDPOINTS = listOf(
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
)

/** Hard cap on the response, so a mis-typed bounding box cannot exhaust RAM. */
private const val MAX_RESPONSE_BYTES = 12 * 1024 * 1024

/** The result of an import attempt, in terms the UI can show a user. */
sealed interface OverpassResult {
    data class Success(val network: PathNetwork, val wayCount: Int) : OverpassResult
    data class Empty(val message: String) : OverpassResult
    data class Failed(val message: String) : OverpassResult
}

/**
 * Downloads the real road and footpath network for an area from OpenStreetMap.
 *
 * Blocking: call it off the main thread. Deliberately plain HttpURLConnection
 * rather than adding a networking library -- this is one request, made rarely,
 * by an admin, and the app's whole selling point is that it needs no network
 * at runtime.
 */
object OverpassClient {

    fun fetch(south: Double, west: Double, north: Double, east: Double): OverpassResult {
        // Overpass will happily accept a continent-sized box and then time out.
        // Refuse early with something the user can act on.
        val heightDeg = north - south
        val widthDeg = east - west
        if (heightDeg <= 0 || widthDeg <= 0) {
            return OverpassResult.Failed("منطقة غير صالحة")
        }
        if (heightDeg > 0.25 || widthDeg > 0.25) {
            return OverpassResult.Failed("المنطقة كبيرة جداً — قرّب الخريطة على الحرم")
        }

        val query = overpassQuery(south, west, north, east)
        var lastError = "تعذّر الاتصال"

        for (endpoint in ENDPOINTS) {
            try {
                val body = post(endpoint, query) ?: continue
                val ways = parseWays(body)
                if (ways.isEmpty()) {
                    return OverpassResult.Empty(
                        "لا توجد طرق مرسومة في هذه المنطقة على OpenStreetMap. " +
                            "ارسم الشبكة يدوياً."
                    )
                }
                val network = buildNetworkFromWays(ways)
                if (network.edges.isEmpty()) {
                    return OverpassResult.Empty("الطرق الموجودة كلها مغلقة أو غير قابلة للاستخدام.")
                }
                Log.i(
                    TAG,
                    "Imported ${ways.size} ways -> ${network.nodes.size} nodes, " +
                        "${network.edges.size} edges from $endpoint"
                )
                return OverpassResult.Success(network, ways.size)
            } catch (e: Exception) {
                Log.w(TAG, "Overpass mirror failed: $endpoint", e)
                lastError = e.message ?: lastError
            }
        }
        return OverpassResult.Failed("تعذّر جلب الطرق: $lastError")
    }

    private fun post(endpoint: String, query: String): String? {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 90_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            // Overpass blocks clients that do not identify themselves.
            setRequestProperty("User-Agent", "SarabVision/1.0 (campus navigation)")
        }

        return try {
            connection.outputStream.use { it.write("data=$query".toByteArray()) }

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                // 429 and 504 are the normal "come back later" responses from
                // the free mirrors, so move on to the next one.
                Log.w(TAG, "$endpoint returned HTTP $code")
                return null
            }

            connection.inputStream.bufferedReader().use { reader -> readCapped(reader) }
        } finally {
            connection.disconnect()
        }
    }

    private fun readCapped(reader: BufferedReader): String {
        val builder = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            builder.append(buffer, 0, read)
            if (builder.length > MAX_RESPONSE_BYTES) {
                throw IllegalStateException("الرد كبير جداً — قرّب الخريطة أكثر")
            }
        }
        return builder.toString()
    }

    private fun parseWays(body: String): List<OsmWay> {
        val elements = JSONObject(body).optJSONArray("elements") ?: return emptyList()
        val ways = mutableListOf<OsmWay>()

        for (i in 0 until elements.length()) {
            val element = elements.optJSONObject(i) ?: continue
            if (element.optString("type") != "way") continue

            val tagsJson = element.optJSONObject("tags") ?: continue
            val highway = tagsJson.optString("highway")
            if (highway.isBlank()) continue

            val geometryJson = element.optJSONArray("geometry") ?: continue
            val geometry = mutableListOf<LatLng>()
            for (j in 0 until geometryJson.length()) {
                val point = geometryJson.optJSONObject(j) ?: continue
                val lat = point.optDouble("lat", Double.NaN)
                val lon = point.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
                geometry.add(LatLng(lat, lon))
            }
            if (geometry.size < 2) continue

            ways.add(
                OsmWay(
                    id = element.optLong("id"),
                    tags = OsmWayTags(
                        highway = highway,
                        foot = tagsJson.optString("foot").takeIf { it.isNotBlank() },
                        bicycle = tagsJson.optString("bicycle").takeIf { it.isNotBlank() },
                        // motor_vehicle is the specific tag; motorcar is the
                        // older one still common on campus mapping.
                        motorVehicle = tagsJson.optString("motor_vehicle")
                            .takeIf { it.isNotBlank() }
                            ?: tagsJson.optString("motorcar").takeIf { it.isNotBlank() },
                        access = tagsJson.optString("access").takeIf { it.isNotBlank() },
                        name = tagsJson.optString("name", "")
                    ),
                    geometry = geometry
                )
            )
        }
        return ways
    }
}
