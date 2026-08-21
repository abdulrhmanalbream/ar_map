package com.sarab.vision.map

import android.content.Context
import android.graphics.Color as AndroidColor
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sarab.vision.core.GeoBounds
import com.sarab.vision.core.LatLng
import com.sarab.vision.core.Landmark
import com.sarab.vision.core.PathNetwork
import com.sarab.vision.core.Route
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.android.geometry.LatLng as MapLatLng

private const val TAG = "SarabMap"

private const val SRC_LANDMARKS = "landmarks"
private const val SRC_ROUTE = "route"
private const val SRC_PATHS = "paths"
private const val SRC_USER = "user"

private const val LYR_PATHS = "paths-line"
private const val LYR_ROUTE = "route-line"
private const val LYR_LANDMARKS = "landmarks-circle"
private const val LYR_USER = "user-dot"

/**
 * Where to point the camera when there is nothing else to frame.
 *
 * Without a fallback MapLibre starts at 0,0 zoom 0 and shows the entire
 * planet, which reads as a broken map. Medina is a sane default for this
 * campus and is immediately replaced by the real position once GPS arrives.
 */
private val DEFAULT_CENTRE = LatLng(24.4672, 39.6111)

/**
 * The campus map, rendered by MapLibre.
 *
 * Replaces the hand-drawn Canvas map, which could never look like a real map
 * because it was not one. MapLibre is the same engine the reference web map
 * uses, so this reaches the same visual bar, and unlike that map it can cache
 * tiles for offline use.
 *
 * MapLibre's [MapView] owns a GL surface and demands the full Android
 * lifecycle be forwarded to it; skipping a callback leaks the surface or
 * crashes on resume, so the lifecycle plumbing here is deliberate rather
 * than boilerplate.
 */
@Composable
fun CampusMapView(
    landmarks: List<Landmark>,
    route: Route?,
    pathNetwork: PathNetwork,
    userPosition: LatLng?,
    styleKind: MapStyles.Kind,
    focusOn: LatLng?,
    modifier: Modifier = Modifier,
    /**
     * Bump this to re-apply [focusOn] even when the coordinate is unchanged.
     *
     * "Recentre on me" is pressed precisely when the map has been dragged away
     * from a position it was already given, so comparing coordinates alone
     * would make the button do nothing the second time.
     */
    focusNonce: Int = 0,
    onMapTap: ((LatLng) -> Unit)? = null,
    /**
     * Reports the map centre once the camera settles.
     *
     * Fired on idle rather than on every frame of a pan: this drives Compose
     * state, and a per-frame update would rebuild every GeoJSON source on the
     * map sixty times a second.
     */
    onCentreChanged: ((LatLng) -> Unit)? = null,
    /**
     * Reports the area currently on screen, once the camera settles.
     *
     * The OSM import needs a bounding box, and "what the admin is looking at"
     * is the only definition of the campus the app has.
     */
    onVisibleBoundsChanged: ((GeoBounds) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // MapLibre must be initialised before any MapView is constructed.
    remember { MapLibre.getInstance(context) }

    val mapView = remember { MapView(context) }
    val mapRef = remember { arrayOfNulls<MapLibreMap>(1) }

    // AndroidView's `factory` runs ONCE. A tap handler registered there would
    // capture the very first value of every state it closes over and keep it
    // forever -- which made the path editor silently useless: every tap saw
    // an empty network, so nothing ever accumulated. rememberUpdatedState
    // keeps the handler pointing at the current lambda.
    val currentOnMapTap by rememberUpdatedState(onMapTap)
    val currentOnCentreChanged by rememberUpdatedState(onCentreChanged)
    val currentOnBoundsChanged by rememberUpdatedState(onVisibleBoundsChanged)
    val currentStyleKind by rememberUpdatedState(styleKind)

    /** Which basemap the loaded style represents, so switches are detected. */
    val loadedStyle = remember { arrayOfNulls<MapStyles.Kind>(1) }

    /** Whether the camera has been auto-framed; it must only happen once. */
    val framed = remember { booleanArrayOf(false) }

    /**
     * The focus request already honoured.
     *
     * Without this the update block re-animated to [focusOn] on EVERY
     * recomposition, so the map snapped back the instant anything else
     * changed -- which made dragging under the crosshair impossible.
     */
    val lastFocus = remember { arrayOfNulls<LatLng>(1) }
    val lastFocusNonce = remember { intArrayOf(-1) }

    /** Guards against calling MapView.onCreate twice. */
    val created = remember { booleanArrayOf(false) }

    DisposableEffect(lifecycleOwner) {
        // The activity is usually already CREATED by the time this composes,
        // so ON_CREATE may never fire; create eagerly and guard against a
        // second call.
        if (!created[0]) {
            mapView.onCreate(null)
            created[0] = true
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> Unit
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = {
            mapView.also { view ->
                // onCreate is driven by the lifecycle observer above; calling
                // it again here would initialise the GL surface twice.
                view.getMapAsync { map ->
                    mapRef[0] = map
                    map.uiSettings.isRotateGesturesEnabled = true
                    map.uiSettings.isTiltGesturesEnabled = false
                    map.uiSettings.isAttributionEnabled = true
                    map.uiSettings.isLogoEnabled = false

                    map.setStyle(
                        Style.Builder().fromJson(MapStyles.styleFor(currentStyleKind))
                    ) { style ->
                        installLayers(style)
                        loadedStyle[0] = currentStyleKind
                    }

                    // Reads through rememberUpdatedState, so every tap sees
                    // the CURRENT handler rather than the one that existed
                    // when the view was first created.
                    map.addOnMapClickListener { point ->
                        currentOnMapTap?.invoke(LatLng(point.latitude, point.longitude))
                        true
                    }

                    map.addOnCameraIdleListener {
                        map.cameraPosition.target?.let { target ->
                            currentOnCentreChanged
                                ?.invoke(LatLng(target.latitude, target.longitude))
                        }
                        currentOnBoundsChanged?.let { callback ->
                            val region = map.projection.visibleRegion.latLngBounds
                            callback(
                                GeoBounds(
                                    minLat = region.latitudeSouth,
                                    maxLat = region.latitudeNorth,
                                    minLon = region.longitudeWest,
                                    maxLon = region.longitudeEast
                                )
                            )
                        }
                    }
                }
            }
        },
        update = {
            val map = mapRef[0] ?: return@AndroidView

            // Switching basemap needs a full style reload, and the layers
            // must be reinstalled afterwards because a new style starts empty.
            if (loadedStyle[0] != styleKind) {
                map.setStyle(Style.Builder().fromJson(MapStyles.styleFor(styleKind))) { style ->
                    installLayers(style)
                    loadedStyle[0] = styleKind
                    refresh(map, style, landmarks, route, pathNetwork, userPosition)
                }
                return@AndroidView
            }

            val style = map.style ?: return@AndroidView
            refresh(map, style, landmarks, route, pathNetwork, userPosition)

            if (!framed[0]) {
                frame(map, landmarks, userPosition, focusOn)
                lastFocus[0] = focusOn
                lastFocusNonce[0] = focusNonce
                // Only auto-frame once: re-framing on every update would fight
                // the user every time they panned.
                if (landmarks.isNotEmpty() || userPosition != null) framed[0] = true
            } else if (
                focusOn != null &&
                focusOn.isValid &&
                (focusOn != lastFocus[0] || focusNonce != lastFocusNonce[0])
            ) {
                // Only on a NEW request. Re-animating on every recomposition
                // would drag the camera back under the user's finger.
                lastFocus[0] = focusOn
                lastFocusNonce[0] = focusNonce
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        MapLatLng(focusOn.latitude, focusOn.longitude), 17.0
                    )
                )
            }
        },
        modifier = modifier
    )
}

/**
 * Creates the empty sources and layers once.
 *
 * Order matters: paths sit under the route, which sits under the landmarks,
 * which sit under the user dot. Adding them in the wrong order buries the
 * thing the user most needs to see.
 */
private fun installLayers(style: Style) {
    try {
        style.addSource(GeoJsonSource(SRC_PATHS))
        style.addSource(GeoJsonSource(SRC_ROUTE))
        style.addSource(GeoJsonSource(SRC_LANDMARKS))
        style.addSource(GeoJsonSource(SRC_USER))

        // The drawn network: thin and muted, it is context rather than content.
        style.addLayer(
            LineLayer(LYR_PATHS, SRC_PATHS).withProperties(
                PropertyFactory.lineColor(AndroidColor.parseColor("#5A7A96")),
                PropertyFactory.lineWidth(2.5f),
                PropertyFactory.lineOpacity(0.55f)
            )
        )

        // The active route: the brightest line on the map.
        style.addLayer(
            LineLayer(LYR_ROUTE, SRC_ROUTE).withProperties(
                PropertyFactory.lineColor(AndroidColor.parseColor("#FFB300")),
                PropertyFactory.lineWidth(6f),
                PropertyFactory.lineOpacity(0.95f),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round")
            )
        )

        style.addLayer(
            CircleLayer(LYR_LANDMARKS, SRC_LANDMARKS).withProperties(
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleColor(AndroidColor.parseColor("#4FC3F7")),
                PropertyFactory.circleStrokeWidth(2.5f),
                PropertyFactory.circleStrokeColor(AndroidColor.parseColor("#0B1520"))
            )
        )

        style.addLayer(
            CircleLayer(LYR_USER, SRC_USER).withProperties(
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleColor(AndroidColor.parseColor("#FFFFFF")),
                PropertyFactory.circleStrokeWidth(3f),
                PropertyFactory.circleStrokeColor(AndroidColor.parseColor("#4FC3F7"))
            )
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to install map layers", e)
    }
}

/** Pushes the current data into the existing sources. */
private fun refresh(
    map: MapLibreMap,
    style: Style,
    landmarks: List<Landmark>,
    route: Route?,
    pathNetwork: PathNetwork,
    userPosition: LatLng?
) {
    try {
        (style.getSource(SRC_LANDMARKS) as? GeoJsonSource)?.setGeoJson(
            FeatureCollection.fromFeatures(
                landmarks.map {
                    Feature.fromGeometry(
                        Point.fromLngLat(it.position.longitude, it.position.latitude)
                    ).apply { addStringProperty("name", it.name) }
                }
            )
        )

        // Always hand over a FeatureCollection: mixing Feature and
        // FeatureCollection through an elvis yields a supertype that matches
        // none of setGeoJson's overloads.
        val routeFeatures = route?.takeIf { it.points.size >= 2 }?.let { r ->
            listOf(
                Feature.fromGeometry(
                    LineString.fromLngLats(
                        r.points.map { Point.fromLngLat(it.longitude, it.latitude) }
                    )
                )
            )
        } ?: emptyList()
        (style.getSource(SRC_ROUTE) as? GeoJsonSource)
            ?.setGeoJson(FeatureCollection.fromFeatures(routeFeatures))

        (style.getSource(SRC_PATHS) as? GeoJsonSource)?.setGeoJson(
            FeatureCollection.fromFeatures(
                pathNetwork.edges.mapNotNull { edge ->
                    val a = pathNetwork.node(edge.fromNodeId) ?: return@mapNotNull null
                    val b = pathNetwork.node(edge.toNodeId) ?: return@mapNotNull null
                    Feature.fromGeometry(
                        LineString.fromLngLats(
                            listOf(
                                Point.fromLngLat(a.position.longitude, a.position.latitude),
                                Point.fromLngLat(b.position.longitude, b.position.latitude)
                            )
                        )
                    )
                }
            )
        )

        val userFeatures = userPosition?.takeIf { it.isValid }?.let {
            listOf(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)))
        } ?: emptyList()
        (style.getSource(SRC_USER) as? GeoJsonSource)
            ?.setGeoJson(FeatureCollection.fromFeatures(userFeatures))
    } catch (e: Exception) {
        Log.e(TAG, "Failed to refresh map data", e)
    }
}

/** Frames the camera so everything relevant is on screen at first load. */
private fun frame(
    map: MapLibreMap,
    landmarks: List<Landmark>,
    userPosition: LatLng?,
    focusOn: LatLng?
) {
    try {
        if (focusOn != null && focusOn.isValid) {
            map.cameraPosition = CameraPosition.Builder()
                .target(MapLatLng(focusOn.latitude, focusOn.longitude))
                .zoom(17.0)
                .build()
            return
        }

        val points = buildList {
            addAll(landmarks.map { it.position })
            userPosition?.takeIf { it.isValid }?.let { add(it) }
        }.filter { it.isValid }

        when {
            // Nothing to frame: the camera would otherwise sit at 0,0 zoom 0
            // and show the whole planet, which is useless and looks broken.
            // Fall back to the user's position, or the campus region.
            points.isEmpty() -> {
                val fallback = userPosition?.takeIf { it.isValid } ?: DEFAULT_CENTRE
                map.cameraPosition = CameraPosition.Builder()
                    .target(MapLatLng(fallback.latitude, fallback.longitude))
                    .zoom(16.0)
                    .build()
            }
            points.size == 1 -> {
                map.cameraPosition = CameraPosition.Builder()
                    .target(MapLatLng(points[0].latitude, points[0].longitude))
                    .zoom(17.0)
                    .build()
            }
            else -> {
                val builder = LatLngBounds.Builder()
                points.forEach { builder.include(MapLatLng(it.latitude, it.longitude)) }
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 120))
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not frame the map", e)
    }
}
