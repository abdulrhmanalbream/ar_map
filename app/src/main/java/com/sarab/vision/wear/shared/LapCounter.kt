package com.sarab.vision.wear.shared

import java.util.UUID

/** A tally of user-confirmed laps, never a religious completion decision. */
data class LapCounter(
    val mode: String,
    val count: Int = 0,
    val sessionId: String = UUID.randomUUID().toString(),
    val revision: Long = 0,
    val startedAt: Long = System.currentTimeMillis(),
) {
    init {
        require(mode == "tawaf" || mode == "sai")
        require(count in 0..7 && revision >= 0 && startedAt >= 0)
        require(sessionId.isNotBlank())
    }

    fun increment(): LapCounter = if (count == 7) this else copy(count = count + 1, revision = revision + 1)
    fun undo(): LapCounter = if (count == 0) this else copy(count = count - 1, revision = revision + 1)
    fun reset(newSessionId: String = UUID.randomUUID().toString(), now: Long = System.currentTimeMillis()) =
        LapCounter(mode, sessionId = newSessionId, startedAt = now)
}

/** A delayed packet must not resurrect an old count after undo or a new session. */
fun isNewerLap(incoming: LapCounter, current: LapCounter?): Boolean =
    current == null || (incoming.mode == current.mode &&
        if (incoming.sessionId == current.sessionId) incoming.revision > current.revision
        else incoming.startedAt > current.startedAt)
