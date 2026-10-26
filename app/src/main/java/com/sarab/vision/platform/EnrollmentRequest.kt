package com.sarab.vision.platform

import org.json.JSONObject

internal data class EnrollmentRequest(val path: String, val body: JSONObject, val name: String, val language: String)

/** A public join has no invitation or consent fields; opting into sharing happens separately. */
internal fun enrollmentRequest(name: String, language: String, code: String?): EnrollmentRequest {
    val displayName = name.trim()
    require(displayName.isNotEmpty() && displayName.length <= 80) { "أدخل اسماً من حرف إلى ٨٠ حرفاً" }
    val body = JSONObject().put("name", displayName).put("language", language)
    val path = if (code == null) "/devices/enroll-default" else {
        require(code.isNotBlank()) { "أدخل رمز المجموعة الخاصة" }
        body.put("code", code.trim())
        "/devices/enroll"
    }
    return EnrollmentRequest(path, body, displayName, language)
}
