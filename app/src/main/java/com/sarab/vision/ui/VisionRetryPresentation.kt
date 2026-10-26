package com.sarab.vision.ui

/** Ceiling seconds keeps controls disabled for the final partial second without overflow. */
internal fun visionRetrySeconds(retryAtElapsedMs: Long, nowElapsedMs: Long): Long {
    if (retryAtElapsedMs <= 0 || nowElapsedMs < 0 || retryAtElapsedMs <= nowElapsedMs) return 0
    val remaining = retryAtElapsedMs - nowElapsedMs
    return remaining / 1_000 + if (remaining % 1_000 > 0) 1 else 0
}
