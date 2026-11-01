package com.sarab.vision.platform

import android.content.Context
import com.sarab.vision.CampusApp
import com.sarab.vision.core.distanceMeters
import com.sarab.vision.wear.PhoneWearBridge
import org.json.JSONArray
import org.json.JSONObject

/** The server rejects larger bodies; trimming destinations is cheaper than failing. */
private const val MAX_ASSISTANT_BODY_BYTES = 60_000

/** One /assistant turn as typed in the companion screen or spoken hands-free. */
data class AssistantTurn(val role: String, val text: String)

/**
 * Builds the text-assistant request shared by the companion chat and the
 * glasses hands-free mode, so both send the same destination catalogue and the
 * server can only ever propose real landmarks.
 */
fun assistantRequest(context: Context, message: String, language: String, history: List<AssistantTurn>): JSONObject {
    val campus = CampusApp.state(context)
    val destinations = JSONArray(campus.landmarks.take(150).map { target ->
        JSONObject().put("id", target.id.take(120)).put("name", target.name.take(160))
            .put("aliases", JSONArray(target.signTexts.take(10).map { it.take(120) })).apply {
                if (!campus.demoActive) campus.fix?.let { put("distanceMeters", distanceMeters(it.position, target.approachPoint(it.position))) }
            }
    })
    val previous = JSONArray(history.takeLast(8).map { JSONObject().put("role", it.role).put("content", it.text.take(1500)) })
    val request = JSONObject().put("message", message).put("language", language)
        .put("context", JSONObject().put("destinationId", campus.target?.id ?: JSONObject.NULL)
            .put("destinationName", campus.target?.name ?: JSONObject.NULL)
            .put("lap", platformLap(PhoneWearBridge.get(context).snapshot.value.lap) ?: JSONObject.NULL))
        .put("destinations", destinations).put("history", previous)
    while (request.toString().toByteArray(Charsets.UTF_8).size > MAX_ASSISTANT_BODY_BYTES && destinations.length() > 0)
        destinations.remove(destinations.length() - 1)
    return request
}
