package com.sarab.vision.data

import android.content.Context
import android.util.Log
import com.sarab.vision.core.LatLng
import com.sarab.vision.core.PathEdge
import com.sarab.vision.core.PathNetwork
import com.sarab.vision.core.PathNode
import com.sarab.vision.core.mergeNetworks
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "SarabPaths"
private const val FILE_NAME = "paths.json"

/** The OpenStreetMap campus network shipped in assets (see licenses/OpenStreetMap-ODbL.txt). */
private const val SEED_ASSET = "campus_paths.json"

/**
 * Bump when [SEED_ASSET] changes, or existing installs never receive the new
 * paths. Version 1 is the first bundled OSM network (2026-11-01).
 */
const val CURRENT_PATH_SEED_VERSION = 1

private const val PREFS = "sarab_paths"
private const val KEY_SEED_VERSION = "seed_version"

/**
 * Offline storage for the drawn path network.
 *
 * Same reasoning as [LandmarkStore]: plain JSON in app-private storage, no
 * database, no network. The network is hundreds of nodes at most, and JSON
 * has the practical advantage that a finished survey can be exported, checked
 * by eye, and committed into assets/ to ship with the app.
 */
class PathNetworkStore(private val context: Context) {

    private val file: File
        get() = File(context.filesDir, FILE_NAME)

    fun load(): PathNetwork {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!file.exists()) {
            val seeded = loadSeedFromAssets()
            if (!seeded.isEmpty) {
                Log.i(TAG, "Seeded ${seeded.edges.size} path edges from assets")
                save(seeded)
                prefs.edit().putInt(KEY_SEED_VERSION, CURRENT_PATH_SEED_VERSION).apply()
            }
            return seeded
        }
        val stored = try {
            parse(file.readText())
        } catch (e: Exception) {
            // A corrupt file must not brick the app; routing simply falls
            // back to straight lines until it is redrawn.
            Log.e(TAG, "Could not read the path network; starting empty", e)
            return PathNetwork()
        }
        if (prefs.getInt(KEY_SEED_VERSION, 0) >= CURRENT_PATH_SEED_VERSION) return stored
        // An install from before the bundled network, or an older bundle.
        // Hand-drawn and imported paths already on the device win every
        // conflict (mergeNetworks keeps existing edges and snaps onto them),
        // so a survey is never overwritten by the shipped data.
        val seed = loadSeedFromAssets()
        val merged = if (seed.isEmpty) stored else mergeNetworks(stored, seed)
        if (merged !== stored) {
            Log.i(TAG, "Merged bundled paths v$CURRENT_PATH_SEED_VERSION: ${stored.edges.size} -> ${merged.edges.size} edges")
            save(merged)
        }
        prefs.edit().putInt(KEY_SEED_VERSION, CURRENT_PATH_SEED_VERSION).apply()
        return merged
    }

    fun save(network: PathNetwork) {
        try {
            file.writeText(serialise(network))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save the path network", e)
        }
    }

    fun exportJson(): String = serialise(load())

    private fun loadSeedFromAssets(): PathNetwork = try {
        context.assets.open(SEED_ASSET).use { parse(it.bufferedReader().readText()) }
    } catch (e: Exception) {
        // No bundled network: entirely normal before the first survey.
        PathNetwork()
    }

    private fun parse(json: String): PathNetwork {
        val root = JSONObject(json)

        val nodes = mutableListOf<PathNode>()
        root.optJSONArray("nodes")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val lat = o.optDouble("lat", Double.NaN)
                val lon = o.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
                val position = LatLng(lat, lon)
                if (!position.isValid) continue
                nodes.add(
                    PathNode(
                        id = o.optString("id").ifBlank { "n-$i" },
                        position = position
                    )
                )
            }
        }

        val edges = mutableListOf<PathEdge>()
        root.optJSONArray("edges")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val from = o.optString("from")
                val to = o.optString("to")
                if (from.isBlank() || to.isBlank()) continue
                edges.add(
                    PathEdge(
                        id = o.optString("id").ifBlank { "e-$i" },
                        fromNodeId = from,
                        toNodeId = to,
                        allowsFoot = o.optBoolean("foot", true),
                        allowsBike = o.optBoolean("bike", true),
                        allowsCar = o.optBoolean("car", false),
                        hasStairs = o.optBoolean("stairs", false),
                        name = o.optString("name", "")
                    )
                )
            }
        }

        // Drop edges whose endpoints are missing; a dangling reference would
        // make routing silently skip them with no clue why.
        val ids = nodes.map { it.id }.toSet()
        val valid = edges.filter { it.fromNodeId in ids && it.toNodeId in ids }
        if (valid.size != edges.size) {
            Log.w(TAG, "Dropped ${edges.size - valid.size} edges with missing nodes")
        }

        return PathNetwork(nodes, valid)
    }

    private fun serialise(network: PathNetwork): String {
        val nodes = JSONArray()
        network.nodes.forEach { n ->
            nodes.put(
                JSONObject().apply {
                    put("id", n.id)
                    put("lat", n.position.latitude)
                    put("lon", n.position.longitude)
                }
            )
        }

        val edges = JSONArray()
        network.edges.forEach { e ->
            edges.put(
                JSONObject().apply {
                    put("id", e.id)
                    put("from", e.fromNodeId)
                    put("to", e.toNodeId)
                    put("foot", e.allowsFoot)
                    put("bike", e.allowsBike)
                    put("car", e.allowsCar)
                    put("stairs", e.hasStairs)
                    if (e.name.isNotBlank()) put("name", e.name)
                }
            )
        }

        return JSONObject().apply {
            put("nodes", nodes)
            put("edges", edges)
        }.toString(2)
    }
}
