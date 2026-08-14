package com.sarab.vision.map

/**
 * Map style definitions.
 *
 * Built as raw style JSON rather than pulling a hosted style, so the map has
 * exactly one network dependency (the tiles themselves) and nothing else to
 * fail. The reference web map fetches its style document from OpenFreeMap on
 * every load; here the style is compiled in and only tiles travel the wire,
 * which is what makes offline caching tractable.
 */
object MapStyles {

    /** Esri World Imagery — keyless, and the same source the web map uses. */
    private const val SATELLITE_TILES =
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"

    /**
     * Free vector-ish street raster from OpenStreetMap, as a plain fallback
     * for when satellite imagery is too dark to read.
     */
    private const val STREET_TILES = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"

    const val SOURCE_BASE = "base-map"
    const val LAYER_BASE = "base-map-layer"

    /** Satellite imagery. The default: a campus reads best from above. */
    fun satellite(): String = rasterStyle(
        tiles = SATELLITE_TILES,
        maxZoom = 19,
        attribution = "© Esri, Maxar, Earthstar Geographics"
    )

    fun street(): String = rasterStyle(
        tiles = STREET_TILES,
        maxZoom = 19,
        attribution = "© OpenStreetMap contributors"
    )

    private fun rasterStyle(tiles: String, maxZoom: Int, attribution: String): String = """
        {
          "version": 8,
          "name": "Sarab Campus",
          "sources": {
            "$SOURCE_BASE": {
              "type": "raster",
              "tiles": ["$tiles"],
              "tileSize": 256,
              "maxzoom": $maxZoom,
              "attribution": "$attribution"
            }
          },
          "layers": [
            {
              "id": "background",
              "type": "background",
              "paint": { "background-color": "#0B1520" }
            },
            {
              "id": "$LAYER_BASE",
              "type": "raster",
              "source": "$SOURCE_BASE",
              "paint": { "raster-opacity": 1.0 }
            }
          ]
        }
    """.trimIndent()

    /** Which basemap is showing. */
    enum class Kind(val labelAr: String) {
        SATELLITE("قمر صناعي"),
        STREET("خريطة")
    }

    fun styleFor(kind: Kind): String = when (kind) {
        Kind.SATELLITE -> satellite()
        Kind.STREET -> street()
    }
}
