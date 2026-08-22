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
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.expressions.Expression
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
private const val LYR_ROUTE_CASING = "route-casing"
private const val LYR_ROUTE = "route-line"
private const val LYR_LANDMARKS = "landmarks-pin"
private const val LYR_LABELS = "landmarks-label"
private const val LYR_ACCURACY = "user-accuracy"
private const val LYR_USER = "user-dot"

/** Icon ids registered into the style. */
private const val ICON_PIN = "icon-pin"
private const val ICON_PIN_ACTIVE = "icon-pin-active"
private const val ICON_PUCK = "icon-puck"

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
    /** GPS accuracy in metres, drawn as the halo around the location puck. */
    userAccuracyM: Float? = null,
    /** Compass heading, which rotates the puck's cone. Null hides the cone. */
    userHeadingDeg: Double? = null,
    /** Landmark drawn in the accent colour, so it is findable at a glance. */
    selectedId: String? = null,
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
    val density = context.resources.displayMetrics.density

    /** Label bitmaps already registered, so each is drawn only once. */
    val registeredLabels = remember { mutableSetOf<String>() }

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
                        registeredLabels.clear()
                        installLayers(style, density)
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
                    // A new style starts empty, so every image registered
                    // against the old one is gone with it.
                    registeredLabels.clear()
                    installLayers(style, density)
                    loadedStyle[0] = styleKind
                    refresh(
                        map, style, landmarks, route, pathNetwork, userPosition,
                        userAccuracyM, userHeadingDeg, selectedId, density, registeredLabels
                    )
                }
                return@AndroidView
            }

            val style = map.style ?: return@AndroidView
            refresh(
                map, style, landmarks, route, pathNetwork, userPosition,
                userAccuracyM, userHeadingDeg, selectedId, density, registeredLabels
            )

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
 * Creates the empty sources, icons and layers once.
 *
 * Order matters: the drawn network sits under the route, which sits under the
 * pins, which sit under their labels and the user. Adding them in the wrong
 * order buries the thing the user most needs to see.
 *
 * The route is drawn as TWO lines -- a dark casing and a bright fill over it.
 * That is what makes a route legible over satellite imagery, and it is why
 * every mapping app does it: a single flat line disappears against a dark roof
 * or a bright car park.
 */
private fun installLayers(style: Style, density: Float) {
    try {
        style.addImage(ICON_PIN, MapLabels.pin(density, highlight = false))
        style.addImage(ICON_PIN_ACTIVE, MapLabels.pin(density, highlight = true))
        style.addImage(ICON_PUCK, MapLabels.locationPuck(density, withHeading = true))

        style.addSource(GeoJsonSource(SRC_PATHS))
        style.addSource(GeoJsonSource(SRC_ROUTE))
        style.addSource(GeoJsonSource(SRC_LANDMARKS))
        style.addSource(GeoJsonSource(SRC_USER))

        // The drawn network: thin and muted, it is context rather than content.
        style.addLayer(
            LineLayer(LYR_PATHS, SRC_PATHS).withProperties(
                PropertyFactory.lineColor(AndroidColor.parseColor("#5A7A96")),
                PropertyFactory.lineWidth(2.5f),
                PropertyFactory.lineOpacity(0.45f)
            )
        )

        style.addLayer(
            LineLayer(LYR_ROUTE_CASING, SRC_ROUTE).withProperties(
                PropertyFactory.lineColor(AndroidColor.parseColor("#0B3D62")),
                PropertyFactory.lineWidth(11f),
                PropertyFactory.lineOpacity(0.95f),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round")
            )
        )
        style.addLayer(
            LineLayer(LYR_ROUTE, SRC_ROUTE).withProperties(
                PropertyFactory.lineColor(AndroidColor.parseColor("#2E9BFF")),
                PropertyFactory.lineWidth(7f),
                PropertyFactory.lineOpacity(1.0f),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round")
            )
        )

        // Accuracy halo, sized in metres rather than pixels so it shrinks as
        // the map zooms out -- an honest picture of how well GPS knows.
        style.addLayer(
            CircleLayer(LYR_ACCURACY, SRC_USER).withProperties(
                PropertyFactory.circleRadius(
                    Expression.interpolate(
                        Expression.exponential(2f),
                        Expression.zoom(),
                        Expression.stop(12f, Expression.get("accuracyPx12")),
                        Expression.stop(20f, Expression.get("accuracyPx20"))
                    )
                ),
                PropertyFactory.circleColor(AndroidColor.parseColor("#1E88E5")),
                PropertyFactory.circleOpacity(0.14f),
                PropertyFactory.circleStrokeWidth(1f),
                PropertyFactory.circleStrokeColor(AndroidColor.parseColor("#1E88E5")),
                PropertyFactory.circleStrokeOpacity(0.35f)
            )
        )

        style.addLayer(
            SymbolLayer(LYR_LANDMARKS, SRC_LANDMARKS).withProperties(
                PropertyFactory.iconImage(
                    Expression.switchCase(
                        Expression.get("active"), Expression.literal(ICON_PIN_ACTIVE),
                        Expression.literal(ICON_PIN)
                    )
                ),
                // Anchored at the tip, so the pin points AT the coordinate
                // rather than sitting centred on it.
                PropertyFactory.iconAnchor("bottom"),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
        )

        style.addLayer(
            SymbolLayer(LYR_LABELS, SRC_LANDMARKS).withProperties(
                PropertyFactory.iconImage(Expression.get("label")),
                PropertyFactory.iconAnchor("top"),
                PropertyFactory.iconOffset(arrayOf(0f, 4f)),
                // Labels may hide each other when they collide; the pin
                // underneath always stays visible, so nothing is ever lost.
                PropertyFactory.iconAllowOverlap(false),
                PropertyFactory.iconOptional(true)
            )
        )

        style.addLayer(
            SymbolLayer(LYR_USER, SRC_USER).withProperties(
                PropertyFactory.iconImage(ICON_PUCK),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconRotate(Expression.get("heading")),
                PropertyFactory.iconRotationAlignment("map")
            )
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to install map layers", e)
    }
}

/**
 * Pushes the current data into the existing sources.
 *
 * Landmark labels are registered as images on the fly: each name is drawn once
 * to a bitmap by Android's text engine (which shapes Arabic correctly, unlike
 * glyph-based map text) and reused until the name changes.
 */
private fun refresh(
    map: MapLibreMap,
    style: Style,
    landmarks: List<Landmark>,
    route: Route?,
    pathNetwork: PathNetwork,
    userPosition: LatLng?,
    userAccuracyM: Float?,
    userHeadingDeg: Double?,
    selectedId: String?,
    density: Float,
    registeredLabels: MutableSet<String>
) {
    try {
        val landmarkFeatures = landmarks.map { landmark ->
            val active = landmark.id == selectedId
            val labelId = "label-${landmark.id}-${if (active) "on" else "off"}"
            if (registeredLabels.add(labelId)) {
                style.addImage(labelId, MapLabels.pill(landmark.name, density, active))
            }
            Feature.fromGeometry(
                Point.fromLngLat(landmark.position.longitude, landmark.position.latitude)
            ).apply {
                addStringProperty("name", landmark.name)
                addStringProperty("label", labelId)
                addBooleanProperty("active", active)
            }
        }
        (style.getSource(SRC_LANDMARKS) as? GeoJsonSource)
            ?.setGeoJson(FeatureCollection.fromFeatures(landmarkFeatures))

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

        val userFeatures = userPosition?.takeIf { it.isValid }?.let { position ->
            // The accuracy halo is a real distance, so its pixel radius has to
            // be recomputed per zoom. Two reference stops let MapLibre
            // interpolate the rest on the GPU.
            val metres = (userAccuracyM ?: 0f).toDouble().coerceIn(0.0, 200.0)
            listOf(
                Feature.fromGeometry(
                    Point.fromLngLat(position.longitude, position.latitude)
                ).apply {
                    addNumberProperty("heading", userHeadingDeg ?: 0.0)
                    addNumberProperty("accuracyPx12", metresToPixels(metres, position.latitude, 12.0))
                    addNumberProperty("accuracyPx20", metresToPixels(metres, position.latitude, 20.0))
                }
            )
        } ?: emptyList()
        (style.getSource(SRC_USER) as? GeoJsonSource)
            ?.setGeoJson(FeatureCollection.fromFeatures(userFeatures))
    } catch (e: Exception) {
        Log.e(TAG, "Failed to refresh map data", e)
    }
}

/**
 * Metres to screen pixels at a given zoom and latitude.
 *
 * Web Mercator: one tile spans the world at zoom 0, and ground resolution
 * shrinks by cos(latitude) away from the equator.
 */
private fun metresToPixels(metres: Double, latitude: Double, zoom: Double): Double {
    val metresPerPixel = 156543.03392 *
        kotlin.math.cos(Math.toRadians(latitude)) / Math.pow(2.0, zoom)
    if (metresPerPixel <= 0.0) return 0.0
    return metres / metresPerPixel
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
