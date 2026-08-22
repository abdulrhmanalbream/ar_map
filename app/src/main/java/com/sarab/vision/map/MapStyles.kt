package com.sarab.vision.map

/**
 * Map style definitions.
 *
 * Built as raw style JSON rather than pulling a hosted style, so the map has
 * exactly one network dependency (the tiles themselves) and nothing else to
 * fail. The reference web map fetches its style document from OpenFreeMap on
 * every load; here the style is compiled in and only tiles travel the wire,
 * which is what makes offline caching tractable.
 *
 * ## Why satellite is layered rather than a single image
 *
 * The first version showed bare aerial photography with nothing on top, and
 * that is the whole reason the map did not feel like Google Maps. Google never
 * shows raw imagery: it always draws road casings and place names over it. An
 * unlabelled photo is pretty and useless -- you cannot tell a road from a path
 * or name a single building on it.
 *
 * So satellite here is three stacked raster layers: imagery, then roads, then
 * labels. All three come from the same keyless Esri service the imagery
 * already used, so this costs nothing in dependencies.
 */
object MapStyles {

    /** Esri World Imagery — keyless, and the same source the web map uses. */
    private const val SATELLITE_TILES =
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"

    /** Transparent road casings, drawn over imagery. */
    private const val ROADS_OVERLAY =
        "https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}"

    /** Transparent place and street labels, drawn over everything. */
    private const val LABELS_OVERLAY =
        "https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}"

    /**
     * Street map from OpenStreetMap.
     *
     * Already carries its own labels and road classification, so it needs no
     * overlays -- and it is the mode to switch to when imagery is too dark to
     * read, which on this campus happens in the late afternoon.
     */
    private const val STREET_TILES = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"

    const val SOURCE_BASE = "base-map"
    const val LAYER_BASE = "base-map-layer"

    private const val SOURCE_ROADS = "overlay-roads"
    private const val SOURCE_LABELS = "overlay-labels"

    /** The topmost basemap layer, so data layers can be inserted above it. */
    const val LAYER_TOP = "base-map-top"

    /** Satellite imagery with roads and names over it. The default. */
    fun satellite(): String = """
        {
          "version": 8,
          "name": "Sarab Campus Hybrid",
          "sources": {
            "$SOURCE_BASE": {
              "type": "raster",
              "tiles": ["$SATELLITE_TILES"],
              "tileSize": 256,
              "maxzoom": 19,
              "attribution": "© Esri, Maxar, Earthstar Geographics"
            },
            "$SOURCE_ROADS": {
              "type": "raster",
              "tiles": ["$ROADS_OVERLAY"],
              "tileSize": 256,
              "maxzoom": 19
            },
            "$SOURCE_LABELS": {
              "type": "raster",
              "tiles": ["$LABELS_OVERLAY"],
              "tileSize": 256,
              "maxzoom": 19
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
            },
            {
              "id": "overlay-roads-layer",
              "type": "raster",
              "source": "$SOURCE_ROADS",
              "paint": { "raster-opacity": 0.85 }
            },
            {
              "id": "$LAYER_TOP",
              "type": "raster",
              "source": "$SOURCE_LABELS",
              "paint": { "raster-opacity": 1.0 }
            }
          ]
        }
    """.trimIndent()

    fun street(): String = """
        {
          "version": 8,
          "name": "Sarab Campus Street",
          "sources": {
            "$SOURCE_BASE": {
              "type": "raster",
              "tiles": ["$STREET_TILES"],
              "tileSize": 256,
              "maxzoom": 19,
              "attribution": "© OpenStreetMap contributors"
            }
          },
          "layers": [
            {
              "id": "background",
              "type": "background",
              "paint": { "background-color": "#0B1520" }
            },
            {
              "id": "$LAYER_TOP",
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
