package com.sarab.vision.ritual

import com.sarab.vision.wear.shared.LapCounter
import com.sarab.vision.wear.shared.WearProtocol
import org.json.JSONObject

enum class RitualCounterSource { PHONE, WATCH }

/** Two independent manual sessions. A watch selection is a view, never a write destination. */
data class RitualCounterState(
    val selectedMode: String = "tawaf",
    val source: RitualCounterSource = RitualCounterSource.PHONE,
    val tawaf: LapCounter = LapCounter("tawaf"),
    val sai: LapCounter = LapCounter("sai"),
    val storageError: String? = null,
) {
    init {
        require(selectedMode == "tawaf" || selectedMode == "sai")
        require(tawaf.mode == "tawaf" && sai.mode == "sai")
    }

    val localCounter: LapCounter get() = if (selectedMode == "tawaf") tawaf else sai
    fun selectMode(mode: String) = if (mode == "tawaf" || mode == "sai") copy(selectedMode = mode) else this
    fun increment() = updateLocal { it.increment() }
    fun undo() = updateLocal { it.undo() }
    fun reset(sessionId: String, now: Long) = updateLocal { it.reset(sessionId, now) }

    private fun updateLocal(change: (LapCounter) -> LapCounter): RitualCounterState {
        if (source != RitualCounterSource.PHONE) return this
        return if (selectedMode == "tawaf") copy(tawaf = change(tawaf)) else copy(sai = change(sai))
    }
}

data class RitualWatchState(
    val lap: LapCounter? = null,
    val connected: Boolean = false,
)

data class RitualCounterDisplay(
    val mode: String,
    val count: Int?,
    val source: RitualCounterSource,
    val note: String,
) {
    val editable: Boolean get() = source == RitualCounterSource.PHONE
}

/** Missing, disconnected, or differently selected watch data never substitutes the phone count. */
fun ritualCounterDisplay(state: RitualCounterState, watch: RitualWatchState): RitualCounterDisplay {
    if (state.source == RitualCounterSource.PHONE) return RitualCounterDisplay(
        state.selectedMode, state.localCounter.count, state.source, "عدّ يدوي محفوظ على هذا الجوال",
    )
    val lap = watch.lap?.takeIf { it.mode == state.selectedMode }
    return RitualCounterDisplay(state.selectedMode, lap?.count, state.source, when {
        lap == null -> "لم يصل عدّ ${ritualName(state.selectedMode)} من الساعة. افتح هذا العداد على الساعة."
        !watch.connected -> "الساعة غير متصلة · آخر عدّ محفوظ، للعرض فقط"
        else -> "آخر عدّ وصل من الساعة · قد يتأخر التحديث · للعرض فقط"
    })
}

fun ritualName(mode: String) = if (mode == "sai") "السعي" else "الطواف"
fun ritualArabicNumber(number: Int) = number.toString().map {
    if (it in '0'..'9') ('٠'.code + (it - '0')).toChar() else it
}.joinToString("")

internal fun encodeRitualCounter(state: RitualCounterState): String = JSONObject()
    .put("selectedMode", state.selectedMode).put("source", state.source.name)
    .put("tawaf", WearProtocol.lapJson(state.tawaf)).put("sai", WearProtocol.lapJson(state.sai)).toString()

internal fun decodeRitualCounter(raw: String?): RitualCounterState {
    if (raw == null) return RitualCounterState()
    val json = runCatching { JSONObject(raw) }.getOrNull()
        ?: return RitualCounterState(storageError = "تعذّر قراءة العدّ المحفوظ؛ راجع عدد أشواطك قبل المتابعة.")
    fun lap(mode: String) = json.optJSONObject(mode)?.let { WearProtocol.lap(it) }?.takeIf { it.mode == mode }
    val tawaf = lap("tawaf")
    val sai = lap("sai")
    return RitualCounterState(
        selectedMode = json.optString("selectedMode").takeIf { it == "tawaf" || it == "sai" } ?: "tawaf",
        source = runCatching { RitualCounterSource.valueOf(json.optString("source")) }.getOrDefault(RitualCounterSource.PHONE),
        tawaf = tawaf ?: LapCounter("tawaf"), sai = sai ?: LapCounter("sai"),
        storageError = if (tawaf == null || sai == null) "تعذّر قراءة أحد العدّادين؛ راجع عدد أشواطك قبل المتابعة." else null,
    )
}
