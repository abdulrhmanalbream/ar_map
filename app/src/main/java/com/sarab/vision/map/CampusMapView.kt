package com.sarab.vision.map

import android.content.Context
import android.graphics.Color as AndroidColor
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    onMapTap: ((LatLng) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // MapLibre must be initialised before any MapView is constructed.
    remember { MapLibre.getInstance(context) }

    val mapView = remember { MapView(context) }
    val mapRef = remember { arrayOfNulls<MapLibreMap>(1) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
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
                view.onCreate(null)
                view.getMapAsync { map ->
                    mapRef[0] = map
                    map.uiSettings.isRotateGesturesEnabled = true
                    map.uiSettings.isTiltGesturesEnabled = false
                    map.uiSettings.isAttributionEnabled = true
                    map.uiSettings.isLogoEnabled = false

                    map.setStyle(Style.Builder().fromJson(MapStyles.styleFor(styleKind))) { style ->
                        installLayers(style)
                        refresh(map, style, landmarks, route, pathNetwork, userPosition)
                        frame(map, landmarks, userPosition, focusOn)
                    }

                    onMapTap?.let { handler ->
                        map.addOnMapClickListener { point ->
                            handler(LatLng(point.latitude, point.longitude))
                            true
                        }
                    }
                }
            }
        },
        update = {
            val map = mapRef[0] ?: return@AndroidView
            val style = map.style ?: return@AndroidView
            refresh(map, style, landmarks, route, pathNetwork, userPosition)
            focusOn?.let {
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(MapLatLng(it.latitude, it.longitude), 17.0)
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
            points.isEmpty() -> Unit
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
