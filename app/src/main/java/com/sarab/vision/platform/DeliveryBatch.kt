package com.sarab.vision.platform

import kotlinx.coroutines.CancellationException

/** Persist each success before another network call, so partial batches never replay successes. */
suspend fun <T> deliverBatch(
    events: List<T>, send: suspend (T) -> Unit, complete: (T) -> Unit,
    discard: (T) -> Unit, retryLater: (Exception) -> Unit,
) {
    for (event in events) {
        try { send(event); complete(event) }
        catch (failure: Exception) {
            if (failure is CancellationException || failure is PlatformException && failure.status == 401) throw failure
            if (failure is PlatformException && failure.status in listOf(400, 404, 409, 410, 422)) {
                discard(event)
            } else {
                retryLater(failure)
                break
            }
        }
    }
}
