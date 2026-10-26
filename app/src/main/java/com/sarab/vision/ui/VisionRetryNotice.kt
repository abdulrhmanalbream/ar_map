package com.sarab.vision.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** No timer runs while paused/backgrounded, or when the cooldown is absent or has expired. */
@Composable
internal fun rememberVisionRetrySeconds(retryAtElapsedMs: Long): Long {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var seconds by remember(retryAtElapsedMs) {
        mutableLongStateOf(visionRetrySeconds(retryAtElapsedMs, SystemClock.elapsedRealtime()))
    }
    LaunchedEffect(retryAtElapsedMs, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                seconds = visionRetrySeconds(retryAtElapsedMs, now)
                if (seconds == 0L) break
                delay(minOf(1_000L, retryAtElapsedMs - now))
            }
        }
    }
    return seconds
}
