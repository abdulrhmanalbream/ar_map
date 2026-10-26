package com.sarab.vision.vision

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Treat model output as untrusted display data; malformed boxes never reach the overlay. */
object VisionPayloads {
    fun analysis(json: JSONObject): VisionAnalysis {
        val sources = sources(json.optJSONArray("sources"))
        val sourceIds = sources.map { it.id }.toSet()
        val seen = mutableSetOf<String>()
        val entries = json.optJSONArray("items") ?: JSONArray()
        require(entries.length() <= 40)
        val items = (0 until entries.length()).mapNotNull { index ->
            runCatching {
                val entry = entries.getJSONObject(index)
                val id = required(entry, "id", 120)
                val kind = required(entry, "kind", 20)
                require(kind in setOf("landmark", "text", "object"))
                val coordinates = entry.getJSONArray("box")
                require(coordinates.length() == 4)
                val v = List(4) { coordinates.getDouble(it) }
                require(v.all { it.isFinite() && it in 0.0..1000.0 } && v[0] < v[2] && v[1] < v[3])
                val refs = entry.optJSONArray("sourceIds") ?: JSONArray()
                val refsList = (0 until minOf(refs.length(), 12)).map { refs.optString(it) }
                    .filter { it in sourceIds }.distinct()
                val landmarkId = entry.optString("landmarkId").takeIf {
                    kind == "landmark" && it in setOf("kaaba", "maqam_ibrahim", "black_stone", "safa_marwa")
                }
                val devotionals = if (landmarkId == null) emptyList() else devotionals(entry.optJSONArray("devotionals"), sourceIds)
                VisionItem(id, kind, required(entry, "label", 200), optional(entry, "description", 1600),
                    VisionBox(v[0], v[1], v[2], v[3]), refsList, landmarkId, devotionals)
            }.getOrNull()?.takeIf { seen.add(it.id) }
        }.take(20)
        return VisionAnalysis(required(json, "sceneId", 120), required(json, "summary", 4000),
            items, sources, required(json, "provider", 40))
    }

    fun answer(json: JSONObject) = VisionAnswer(required(json, "reply", 6000),
        sources(json.optJSONArray("sources")), required(json, "provider", 40))

    fun image(jpegBase64: String) = JSONObject().put("mimeType", "image/jpeg").put("data", jpegBase64)

    fun selected(item: VisionItem) = JSONObject().put("id", item.id).put("label", item.label.take(160))
        .put("description", item.description.take(600)).put("kind", item.kind).apply {
            item.landmarkId?.let { put("landmarkId", it) }
        }

    private fun devotionals(array: JSONArray?, sourceIds: Set<String>): List<VisionDevotional> {
        if (array == null || array.length() > 2) return emptyList()
        return (0 until array.length()).mapNotNull { index -> runCatching {
            val entry = array.getJSONObject(index)
            val refs = entry.getJSONArray("sourceIds")
            require(refs.length() in 1..3)
            val known = (0 until refs.length()).map { refs.getString(it) }.distinct()
            require(known.all { it in sourceIds })
            VisionDevotional(required(entry, "id", 80), required(entry, "title", 120),
                required(entry, "arabicText", 600), required(entry, "translation", 900),
                required(entry, "note", 600), known)
        }.getOrNull() }.distinctBy { it.id }
    }

    fun history(entries: List<VisionHistoryEntry>) = JSONArray(entries.takeLast(6).map {
        JSONObject().put("role", it.role).put("text", it.text.take(1000))
    })

    private fun sources(array: JSONArray?): List<VisionSource> {
        if (array == null) return emptyList()
        val seen = mutableSetOf<String>()
        return (0 until minOf(array.length(), 16)).mapNotNull { index ->
            runCatching {
                val value = array.getJSONObject(index)
                val id = required(value, "id", 120)
                val url = required(value, "url", 2048)
                val uri = URI(url)
                require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null)
                VisionSource(id, required(value, "title", 240), uri.toASCIIString())
            }.getOrNull()?.takeIf { seen.add(it.id) }
        }
    }

    private fun required(json: JSONObject, key: String, maximum: Int): String {
        val raw = json.get(key)
        require(raw is String && raw.isNotBlank() && raw.length <= maximum)
        return raw.trim()
    }
    private fun optional(json: JSONObject, key: String, maximum: Int): String {
        val raw = json.opt(key) ?: return ""
        require(raw is String && raw.length <= maximum)
        return raw.trim()
    }
}
