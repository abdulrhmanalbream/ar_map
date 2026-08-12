package com.sarab.vision.core

/**
 * A campus landmark anchored to real-world coordinates.
 *
 * Unlike the V2 [Destination], which held a route in metres relative to a
 * printed marker, a landmark is defined by its GPS position. That is what
 * makes campus-scale navigation possible: there is no accumulated drift, and
 * the user can start from anywhere rather than from one specific board.
 */
data class Landmark(
    val id: String,
    val name: String,
    val category: LandmarkCategory,
    val position: LatLng,
    /** Free text shown on the detail card. */
    val detail: String = "",
    /** Opening hours, if relevant. */
    val hours: String = "",
    /** Facility chips (step-free, wifi, ...). */
    val amenities: List<String> = emptyList(),
    /**
     * Photos of this landmark, stored in the app's photo directory.
     *
     * Several shots from different angles and distances are expected: they
     * help the user recognise the building on arrival, and give the optional
     * visual-recognition layer more chances to match. The first entry is
     * treated as the primary (entrance) shot.
     */
    val photos: List<LandmarkPhoto> = emptyList(),
    /** Accuracy of the fix this landmark was captured with, in metres. */
    val capturedAccuracyM: Float = 0f
) {
    /** Primary photo, used for list thumbnails and the detail card header. */
    val primaryPhoto: LandmarkPhoto?
        get() = photos.firstOrNull { it.viewpoint == Viewpoint.ENTRANCE } ?: photos.firstOrNull()
}

/**
 * One photo of a landmark, tagged with where it was taken from.
 *
 * The viewpoint is not decoration: on arrival the app can show the shot that
 * matches the direction the user is approaching from, which is far more
 * useful for recognising a building than a single canonical photo.
 */
data class LandmarkPhoto(
    val file: String,
    val viewpoint: Viewpoint,
    /** Where the photographer stood, if known. Enables angle matching. */
    val takenFrom: LatLng? = null,
    /** Compass bearing the camera faced, in degrees from true north. */
    val bearingDegrees: Double? = null,
    /** Roughly how far the photographer was, in metres. */
    val distanceMeters: Double? = null
)

/** Where a landmark photo was taken from. */
enum class Viewpoint(val label: String) {
    /** Close up at the door -- the shot that confirms "this is the way in". */
    ENTRANCE("Entrance"),

    /** From one side, showing the building's shape. */
    SIDE("Side view"),

    /** From a distance, showing the building in its surroundings. */
    FAR("From afar"),

    OTHER("Other")
}

/**
 * Landmark type. Drives the marker colour and icon letter, and lets the user
 * filter the list.
 */
enum class LandmarkCategory(
    val label: String,
    val labelAr: String,
    val letter: String
) {
    HOUSING("Housing", "سكن", "H"),
    SPORTS("Sports", "ملاعب", "S"),
    FACULTY("Faculty", "كلية", "F"),
    SERVICES("Services", "خدمات", "V"),
    GATE("Gate", "بوابة", "G"),
    OTHER("Other", "أخرى", "O");

    companion object {
        fun fromName(name: String): LandmarkCategory =
            entries.firstOrNull { it.name == name } ?: OTHER
    }
}

/**
 * A landmark paired with its live distance and bearing from the user.
 *
 * Computed fresh on each GPS update rather than stored, so the UI can sort
 * by "nearest" without the list going stale as the user walks.
 */
data class LandmarkFix(
    val landmark: Landmark,
    val distanceMeters: Double,
    val bearingDegrees: Double
)

/**
 * Sorts landmarks by distance from [from], attaching distance and bearing.
 */
fun rankByDistance(landmarks: List<Landmark>, from: LatLng): List<LandmarkFix> {
    if (!from.isValid) {
        // No fix yet: still return everything so the list is browsable,
        // just without meaningful distances.
        return landmarks.map { LandmarkFix(it, Double.NaN, Double.NaN) }
    }
    return landmarks
        .map {
            LandmarkFix(
                landmark = it,
                distanceMeters = distanceMeters(from, it.position),
                bearingDegrees = bearingDegrees(from, it.position)
            )
        }
        .sortedBy { it.distanceMeters }
}

/**
 * How close the user must be for the app to switch from GPS guidance to
 * close-range AR, in metres.
 *
 * Below this, GPS error (5-10m) is comparable to the remaining distance, so
 * an arrow becomes useless; ARCore's local precision takes over instead.
 */
const val AR_HANDOFF_DISTANCE_M = 30.0

/** Distance at which the user is considered to have arrived. */
const val ARRIVAL_DISTANCE_M = 8.0

/**
 * Normalises Arabic text so search behaves the way users expect.
 *
 * Handles the three things that otherwise make Arabic search feel broken:
 *  - Arabic-Indic digits (١٣) and Persian digits are folded to ASCII, so
 *    typing "13" finds "وحدة ١٣" and vice versa
 *  - alef variants (أ إ آ) fold to ا, and ة folds to ه, since users rarely
 *    type the exact form
 *  - diacritics (tashkeel) are stripped
 */
fun normaliseForSearch(input: String): String {
    val sb = StringBuilder(input.length)
    for (ch in input.lowercase()) {
        val mapped = when (ch) {
            // Arabic-Indic digits
            in '٠'..'٩' -> ('0' + (ch - '٠'))
            // Extended Arabic-Indic (Persian) digits
            in '۰'..'۹' -> ('0' + (ch - '۰'))
            'أ', 'إ', 'آ', 'ٱ' -> 'ا'
            'ة' -> 'ه'
            'ى' -> 'ي'
            'ؤ' -> 'و'
            'ئ' -> 'ي'
            // Strip tashkeel and tatweel entirely.
            in 'ً'..'ٟ', 'ـ', 'ٰ' -> continue
            else -> ch
        }
        sb.append(mapped)
    }
    return sb.toString().trim()
}

/**
 * Matches a landmark against a search query.
 *
 * Numbers are compared as whole tokens, so searching "13" matches
 * "وحدة 13" but NOT "وحدة 1" or "وحدة 3" -- exactly the confusion a numbered
 * housing block would otherwise cause.
 */
fun matchesQuery(landmark: Landmark, query: String): Boolean {
    val q = normaliseForSearch(query)
    if (q.isBlank()) return true

    val haystack = normaliseForSearch(
        "${landmark.name} ${landmark.category.label} ${landmark.category.labelAr} ${landmark.detail}"
    )

    // A purely numeric query must match a standalone number, not a substring.
    if (q.all { it.isDigit() }) {
        return Regex("(?<!\\d)$q(?!\\d)").containsMatchIn(haystack)
    }
    return haystack.contains(q)
}
