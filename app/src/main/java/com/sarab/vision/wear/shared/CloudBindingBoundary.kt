package com.sarab.vision.wear.shared

fun isGroupScopedWatchEvent(type: String, hasDeliveredAlert: Boolean): Boolean =
    type == "help" || type == "ack" || (type == "status" && hasDeliveredAlert)

/** Legacy events remain readable, but cannot cross a later account/group boundary. */
fun allowedAfterCloudBindingChange(
    type: String,
    hasDeliveredAlert: Boolean,
    createdAtMillis: Long?,
    cutoffMillis: Long,
): Boolean {
    if (!isGroupScopedWatchEvent(type, hasDeliveredAlert) || cutoffMillis <= 0) return true
    return createdAtMillis != null && createdAtMillis >= cutoffMillis
}
