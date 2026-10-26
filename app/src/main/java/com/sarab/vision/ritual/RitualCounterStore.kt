package com.sarab.vision.ritual

import android.content.Context
import com.sarab.vision.wear.PhoneWearSnapshot
import com.sarab.vision.wear.shared.WearProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Local phone tally only; this store never sends watch events or modifies group telemetry. */
class RitualCounterStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("smart_mutawwif_phone_ritual", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(decodeRitualCounter(prefs.getString("state", null)))
    val state: StateFlow<RitualCounterState> = mutableState.asStateFlow()

    fun selectMode(mode: String) = update { it.selectMode(mode) }
    fun selectSource(source: RitualCounterSource) = update { it.copy(source = source) }
    fun increment() = update { it.increment() }
    fun undo() = update { it.undo() }
    /** The caller presents confirmation before invoking this method. */
    fun reset() = update { it.reset(UUID.randomUUID().toString(), System.currentTimeMillis()) }

    @Synchronized private fun update(change: (RitualCounterState) -> RitualCounterState): Boolean {
        val previous = mutableState.value
        val next = change(previous)
        if (next == previous) return false
        if (!prefs.edit().putString("state", encodeRitualCounter(next)).commit()) {
            mutableState.value = previous.copy(storageError = "لم يُحفظ التغيير. حاول مرة أخرى قبل مغادرة الشاشة.")
            return false
        }
        mutableState.value = next.copy(storageError = null)
        return true
    }

    companion object {
        @Volatile private var instance: RitualCounterStore? = null
        fun get(context: Context): RitualCounterStore = instance ?: synchronized(this) {
            instance ?: RitualCounterStore(context.applicationContext).also { instance = it }
        }
    }
}

/** lastSeenAt is deliberately not used as lap freshness: status and replay events also advance it. */
fun ritualWatchState(snapshot: PhoneWearSnapshot) = RitualWatchState(
    lap = snapshot.lap?.let { WearProtocol.lap(it) }, connected = snapshot.connected,
)
