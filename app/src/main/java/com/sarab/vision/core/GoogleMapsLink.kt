package com.sarab.vision.core

/**
 * Extracts coordinates from a pasted Google Maps link.
 *
 * Ported from the reference web project's `google-maps-link.ts`, which is
 * where the admin there sets coordinates. Same idea here: pasting a link is
 * far more reliable than typing latitude and longitude by hand, and it lets
 * places be added from a laptop without standing at the door.
 *
 * ## The ordering trap
 *
 * Google writes coordinates as **(lat, lng)** while GeoJSON, MapLibre and the
 * web project's storage all use **[lng, lat]**. Reversing them silently puts
 * a Saudi campus in the Indian Ocean, which is why the parser returns a typed
 * [LatLng] rather than a bare pair.
 *
 * ## Short links
 *
 * `maps.app.goo.gl` links carry no coordinates at all -- they must be
 * expanded by the server first. That needs the network, so it is reported
 * honestly via [isShortLink] instead of failing with a confusing message.
 */

/** Result of parsing a pasted link. */
sealed interface MapsLinkResult {
    data class Found(val position: LatLng) : MapsLinkResult

    /** A short link that must be expanded online before it can be parsed. */
    data object NeedsNetwork : MapsLinkResult

    data class NotFound(val reason: String) : MapsLinkResult
}

/** True for links that redirect and therefore carry no coordinates inline. */
fun isShortLink(raw: String): Boolean {
    val s = raw.lowercase()
    return "maps.app.goo.gl" in s || "goo.gl/maps" in s || "g.co/kgs" in s
}

/**
 * Tries every coordinate format Google Maps produces, most precise first.
 *
 * The `!3d<lat>!4d<lng>` form is preferred because it is the actual pin,
 * whereas `@lat,lng` is only where the camera happened to be pointing and can
 * be tens of metres off.
 */
fun parseGoogleMapsLink(raw: String): MapsLinkResult {
    val input = raw.trim()
    if (input.isEmpty()) return MapsLinkResult.NotFound("الرابط فارغ")

    // 1. The exact dropped pin. Most accurate; try it first.
    Regex("""!3d(-?\d+\.?\d*)!4d(-?\d+\.?\d*)""")
        .find(input)
        ?.let { m ->
            toResult(m.groupValues[1], m.groupValues[2])?.let { return it }
        }

    // 2. Query parameters that carry an explicit coordinate.
    for (key in listOf("q", "query", "ll", "destination", "center", "daddr", "saddr")) {
        Regex("""[?&]$key=(?:loc:)?(-?\d+\.?\d*),(-?\d+\.?\d*)""", RegexOption.IGNORE_CASE)
            .find(input)
            ?.let { m ->
                toResult(m.groupValues[1], m.groupValues[2])?.let { return it }
            }
    }

    // 3. The camera position. Less precise: this is the map centre, not the
    //    pin, so it is only used when nothing better is present.
    Regex("""@(-?\d+\.?\d*),(-?\d+\.?\d*)""")
        .find(input)
        ?.let { m ->
            toResult(m.groupValues[1], m.groupValues[2])?.let { return it }
        }

    // 4. Coordinates embedded in the path, e.g. /place/24.46,39.61
    Regex("""/(?:place|search|dir)/(-?\d+\.?\d*),(-?\d+\.?\d*)""")
        .find(input)
        ?.let { m ->
            toResult(m.groupValues[1], m.groupValues[2])?.let { return it }
        }

    // 5. A bare pasted pair, which is what people copy out of the app itself.
    Regex("""^\s*(-?\d+\.?\d*)\s*,\s*(-?\d+\.?\d*)\s*$""")
        .find(input)
        ?.let { m ->
            toResult(m.groupValues[1], m.groupValues[2])?.let { return it }
        }

    // Only now report the short-link case: a short link that happened to
    // include coordinates should still be parsed rather than rejected.
    if (isShortLink(input)) return MapsLinkResult.NeedsNetwork

    return MapsLinkResult.NotFound("لم يتم العثور على إحداثيات في الرابط")
}

/**
 * Validates and packages a parsed pair.
 *
 * Range-checked, so a mistyped or mismatched pair is rejected rather than
 * quietly placing a landmark somewhere impossible.
 */
private fun toResult(latText: String, lngText: String): MapsLinkResult? {
    val lat = latText.toDoubleOrNull() ?: return null
    val lng = lngText.toDoubleOrNull() ?: return null

    val position = LatLng(lat, lng)
    return if (position.isValid) MapsLinkResult.Found(position) else null
}

/**
 * Builds a Google Maps link for a position, for sharing a place onward.
 */
fun googleMapsLinkFor(position: LatLng): String =
    "https://www.google.com/maps/search/?api=1&query=" +
        "${position.latitude},${position.longitude}"
