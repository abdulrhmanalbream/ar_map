package com.sarab.vision.platform

import com.sarab.vision.wear.shared.WearProtocol
import org.json.JSONObject

/** The watch event envelope is local transport metadata, not part of cloud lap telemetry. */
fun platformLap(raw: JSONObject?): JSONObject? {
    if (raw == null || raw.optInt("target") != 7 || raw.optString("confidence") !in listOf("manual", "estimated")) return null
    val counter = WearProtocol.lap(raw) ?: return null
    return WearProtocol.lapJson(counter).put("confidence", raw.getString("confidence"))
}
