package com.sarab.vision.ar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.AugmentedImage
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.sarab.vision.core.CampusMap
import com.sarab.vision.core.Destination
import com.sarab.vision.core.Ray
import com.sarab.vision.core.Vec3
import com.sarab.vision.core.intersectAabb
import com.sarab.vision.core.remainingRouteDistance
import com.sarab.vision.core.resampleRoute
import com.sarab.vision.core.toImageLocal
import com.sarab.vision.render.CameraBackgroundRenderer
import com.sarab.vision.render.LabelRenderer
import com.sarab.vision.render.MarkerRenderer
import com.sarab.vision.render.PathRenderer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

private const val TAG = "SarabArRenderer"

/** Cube edge length in metres. */
private const val MARKER_SIZE_M = 0.32f

/** Cube centre height above the route's end point, in metres. */
private const val MARKER_HOVER_M = 0.4f

/** Label centre height above the route's end point, in metres. */
private const val LABEL_HOVER_M = 0.95f

/**
 * Owns the GL thread and every per-frame AR operation.
 *
 * V2 changes: the world origin comes from a tracked augmented image (the map
 * board) when one is available, with a floor plane as fallback. Routes are
 * multi-point waypoint paths selected from the UI.
 */
class ArSceneRenderer(
    private val onStateChanged: (ArUiState) -> Unit
) : GLSurfaceView.Renderer {

    private val cameraRenderer = CameraBackgroundRenderer()
    private val pathRenderer = PathRenderer()
    private val markerRenderer = MarkerRenderer()
    private val labelRenderer = LabelRenderer()

    var session: Session? = null

    /**
     * True when a reference image was loaded into the session.
     *
     * When false the app never waits for an image and goes straight to
     * plane-based placement, so it is usable without a printed marker.
     */
    var usingImageOrigin: Boolean = false

    /** Anchor defining the world origin (image centre, or a floor point). */
    private var originAnchor: Anchor? = null
    private var originIsFromImage = false

    /** Set from the UI thread; consumed on the GL thread. */
    private val pendingDestination = AtomicReference<Destination?>(null)
    private val pendingTap = AtomicBoolean(false)
    private var pendingTapX = 0f
    private var pendingTapY = 0f

    private var activeDestination: Destination? = null

    /** Route in world space, rebuilt when the origin or destination changes. */
    private var worldRoute: List<Vec3> = emptyList()
    private var labelDirty = false

    private val markerWorldPos = FloatArray(3)
    private val labelWorldPos = FloatArray(3)

    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewProjectionMatrix = FloatArray(16)

    private var startNanos = 0L
    private var viewportWidth = 1
    private var viewportHeight = 1

    private var lastReportedState: ArUiState? = null
    private var lastDistanceReport = 0L

    fun onTap(x: Float, y: Float) {
        pendingTapX = x
        pendingTapY = y
        pendingTap.set(true)
    }

    /** Selects the route to draw. Safe to call from the UI thread. */
    fun selectDestination(destination: Destination) {
        pendingDestination.set(destination)
    }

    fun setHighlighted(highlighted: Boolean) {
        markerHighlighted = highlighted
    }

    private var markerHighlighted = false

    /** Clears the origin so it can be re-acquired. */
    fun resetOrigin() {
        originAnchor?.detach()
        originAnchor = null
        originIsFromImage = false
        worldRoute = emptyList()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)

        try {
            cameraRenderer.createOnGlThread()
            pathRenderer.createOnGlThread()
            markerRenderer.createOnGlThread()
            labelRenderer.createOnGlThread()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialise renderers", e)
            report(ArUiState.Error("Graphics initialisation failed"))
            return
        }

        startNanos = System.nanoTime()
        session?.setCameraTextureName(cameraRenderer.textureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        session?.setDisplayGeometry(0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val session = this.session ?: return
        session.setCameraTextureName(cameraRenderer.textureId)

        val frame = try {
            session.update()
        } catch (e: Exception) {
            Log.e(TAG, "Session update failed", e)
            return
        }

        val camera = frame.camera
        cameraRenderer.draw(frame)

        if (camera.trackingState != TrackingState.TRACKING) {
            report(if (originAnchor == null) currentSearchState() else ArUiState.Tracking)
            return
        }

        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100f)
        Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        // Acquire the origin, preferring the reference image.
        if (originAnchor == null) {
            if (usingImageOrigin) {
                tryAcquireImageOrigin(frame)
            } else {
                tryAcquirePlaneOrigin(session, frame)
            }
        }

        // Apply a pending destination change (from the UI thread).
        pendingDestination.getAndSet(null)?.let { dest ->
            activeDestination = dest
            labelDirty = true
            rebuildRoute()
        }

        if (labelDirty) {
            activeDestination?.let {
                // Rasterising must happen on the GL thread.
                labelRenderer.setText(it.name, it.category)
            }
            labelDirty = false
        }

        val elapsed = (System.nanoTime() - startNanos) / 1_000_000_000f

        if (originAnchor != null && worldRoute.size >= 2) {
            rebuildRoute()

            pathRenderer.draw(viewProjectionMatrix, elapsed)
            markerRenderer.draw(
                viewProjectionMatrix,
                markerWorldPos,
                MARKER_SIZE_M,
                elapsed,
                markerHighlighted
            )
            labelRenderer.draw(viewProjectionMatrix, viewMatrix, labelWorldPos)

            reportDistance(camera.pose)
            report(ArUiState.Navigating)
        } else if (originAnchor != null) {
            // Origin found but nothing selected yet.
            report(ArUiState.AwaitingDestination)
        } else {
            report(currentSearchState())
        }

        if (pendingTap.getAndSet(false)) {
            handleTap(pendingTapX, pendingTapY)
        }
    }

    private fun currentSearchState(): ArUiState =
        if (usingImageOrigin) ArUiState.SearchingForImage else ArUiState.Scanning

    /**
     * Looks for the reference image and anchors the world origin to it.
     *
     * Only FULL_TRACKING is accepted: a PAUSED image has a stale pose, and
     * anchoring the whole campus route to a stale pose puts the path in the
     * wrong place entirely.
     */
    private fun tryAcquireImageOrigin(frame: Frame) {
        val images = frame.getUpdatedTrackables(AugmentedImage::class.java)
        val match = images.firstOrNull {
            it.name == ORIGIN_IMAGE_NAME &&
                it.trackingState == TrackingState.TRACKING &&
                it.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING
        } ?: return

        originAnchor?.detach()
        originAnchor = match.createAnchor(match.centerPose)
        originIsFromImage = true
        Log.i(TAG, "World origin acquired from reference image '${match.name}'")

        report(ArUiState.OriginAcquired)
        rebuildRoute()
    }

    /**
     * Fallback origin: the floor in front of the user.
     *
     * Used when no reference image was supplied, so the app still works out
     * of the box. The route is laid out ahead of the user's current heading.
     */
    private fun tryAcquirePlaneOrigin(session: Session, frame: Frame) {
        val planes = session.getAllTrackables(Plane::class.java).filter {
            it.trackingState == TrackingState.TRACKING &&
                it.type == Plane.Type.HORIZONTAL_UPWARD_FACING
        }
        if (planes.isEmpty()) return

        val camPose = frame.camera.pose
        val floor = planes.minByOrNull { plane ->
            val c = plane.centerPose
            val dx = c.tx() - camPose.tx()
            val dz = c.tz() - camPose.tz()
            dx * dx + dz * dz
        } ?: return

        // Build an origin pose on the floor, rotated to face the user's
        // heading, so authored "+Y forwards" runs away from the user.
        val zAxis = camPose.zAxis
        var fx = -zAxis[0]
        var fz = -zAxis[2]
        val len = kotlin.math.sqrt(fx * fx + fz * fz)
        if (len < 1e-4f) return
        fx /= len
        fz /= len

        val yaw = kotlin.math.atan2(fx, fz)
        val half = yaw / 2f
        val rotation = floatArrayOf(0f, kotlin.math.sin(half), 0f, kotlin.math.cos(half))
        val translation = floatArrayOf(
            camPose.tx() + fx * 0.4f,
            floor.centerPose.ty(),
            camPose.tz() + fz * 0.4f
        )

        originAnchor?.detach()
        originAnchor = session.createAnchor(Pose(translation, rotation))
        originIsFromImage = false
        Log.i(TAG, "World origin acquired from floor plane (no reference image)")

        report(ArUiState.OriginAcquired)
        rebuildRoute()
    }

    /**
     * Transforms the active destination's waypoints from origin-local space
     * into world space and uploads the ribbon.
     *
     * Recomputed every frame while navigating because the anchor's pose is
     * continually refined by ARCore; using a stale transform makes the path
     * visibly drift away from the floor.
     */
    private fun rebuildRoute() {
        val anchor = originAnchor ?: return
        val destination = activeDestination ?: return
        if (anchor.trackingState != TrackingState.TRACKING) return

        val originPose = anchor.pose

        val local = destination.waypoints.map { wp ->
            if (originIsFromImage) {
                toImageLocal(wp, CampusMap.mounting, CampusMap.boardHeightMeters)
            } else {
                // Plane origin: the anchor already sits on the floor and is
                // yawed to the user's heading, so authored +Y (forwards) maps
                // to -Z, which is forward in ARCore's right-handed frame.
                Vec3(wp.x, 0f, -wp.y)
            }
        }

        val dense = resampleRoute(local, maxSegment = 0.5f)
        val world = dense.map { p ->
            val out = FloatArray(3)
            originPose.transformPoint(floatArrayOf(p.x, p.y, p.z), 0, out, 0)
            Vec3(out[0], out[1], out[2])
        }

        worldRoute = world
        pathRenderer.updatePath(world, widthMeters = 0.26f)

        val end = world.lastOrNull() ?: return
        markerWorldPos[0] = end.x
        markerWorldPos[1] = end.y + MARKER_HOVER_M
        markerWorldPos[2] = end.z

        labelWorldPos[0] = end.x
        labelWorldPos[1] = end.y + LABEL_HOVER_M
        labelWorldPos[2] = end.z
    }

    /** Emits the remaining walking distance, throttled to ~2 Hz. */
    private fun reportDistance(cameraPose: Pose) {
        val now = System.currentTimeMillis()
        if (now - lastDistanceReport < 500) return
        lastDistanceReport = now

        if (worldRoute.size < 2) return
        val here = Vec3(cameraPose.tx(), cameraPose.ty(), cameraPose.tz())
        val remaining = remainingRouteDistance(worldRoute, here)
        onStateChanged(ArUiState.DistanceUpdate(remaining))
    }

    /**
     * Converts a screen tap into a world ray and tests it against the marker.
     */
    private fun handleTap(screenX: Float, screenY: Float) {
        if (worldRoute.isEmpty()) return

        val invVp = FloatArray(16)
        if (!Matrix.invertM(invVp, 0, viewProjectionMatrix, 0)) {
            Log.w(TAG, "View-projection not invertible; ignoring tap")
            return
        }

        val ndcX = (2f * screenX / viewportWidth) - 1f
        val ndcY = 1f - (2f * screenY / viewportHeight)

        val near = unproject(invVp, ndcX, ndcY, -1f) ?: return
        val far = unproject(invVp, ndcX, ndcY, 1f) ?: return

        val ray = Ray(near, (far - near).normalized())
        val center = Vec3(markerWorldPos[0], markerWorldPos[1], markerWorldPos[2])

        // Enlarged pick volume: the marker can be 15m away, and a fingertip
        // is not precise. Covers the label above it too.
        val pickRadius = MARKER_SIZE_M * 1.6f

        if (intersectAabb(ray, center, pickRadius) != null) {
            report(ArUiState.MarkerTapped)
        }
    }

    private fun unproject(invVp: FloatArray, x: Float, y: Float, z: Float): Vec3? {
        val input = floatArrayOf(x, y, z, 1f)
        val out = FloatArray(4)
        Matrix.multiplyMV(out, 0, invVp, 0, input, 0)
        if (kotlin.math.abs(out[3]) < 1e-6f) return null
        return Vec3(out[0] / out[3], out[1] / out[3], out[2] / out[3])
    }

    /** Pushes state to the UI, suppressing duplicate level emissions. */
    private fun report(state: ArUiState) {
        // Events must always be delivered; levels are deduplicated.
        val isEvent = state is ArUiState.MarkerTapped ||
            state is ArUiState.OriginAcquired ||
            state is ArUiState.DistanceUpdate
        if (!isEvent && state == lastReportedState) return
        if (!isEvent) lastReportedState = state
        onStateChanged(state)
    }
}

/** What the AR layer wants the UI to show. */
sealed interface ArUiState {
    /** Looking for the printed reference image. */
    data object SearchingForImage : ArUiState

    /** No reference image in use; looking for a floor plane. */
    data object Scanning : ArUiState

    /** Camera tracking is degraded (fast motion, low light). */
    data object Tracking : ArUiState

    /** Origin just established. */
    data object OriginAcquired : ArUiState

    /** Origin known, waiting for the user to pick a destination. */
    data object AwaitingDestination : ArUiState

    /** Route is drawn and the user is walking it. */
    data object Navigating : ArUiState

    data class DistanceUpdate(val remainingMeters: Float) : ArUiState

    data object MarkerTapped : ArUiState

    data class Error(val message: String) : ArUiState
}
