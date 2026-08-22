package com.sarab.vision.ar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.sarab.vision.core.Vec3
import com.sarab.vision.core.groundPathLength
import com.sarab.vision.core.resamplePath
import com.sarab.vision.core.routeGroundPath
import com.sarab.vision.render.ArrowRenderer
import com.sarab.vision.render.CameraBackgroundRenderer
import com.sarab.vision.render.LabelRenderer
import com.sarab.vision.render.MarkerRenderer
import com.sarab.vision.render.PathRenderer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

private const val TAG = "SarabCampusAr"

/**
 * Draws the navigation path on the real ground, aimed by GPS.
 *
 * ## How this differs from the V2 renderer
 *
 * V2 anchored everything to a printed image and laid out a fixed route in
 * marker-relative coordinates. That cannot work across a campus: there is no
 * marker at every building, and ARCore drift ruins anything beyond ~20m.
 *
 * Here the DIRECTION comes from GPS (a compass bearing to the target, which
 * never drifts) and ARCore only supplies the ground plane to draw on. The
 * path is rebuilt every time the bearing changes, so it always points at the
 * real building even as the user walks and turns.
 *
 * The path is deliberately short. ARCore only knows the ground it can
 * actually see; a 250m ribbon would pass through buildings and float over
 * dips. Distance is carried by the floating marker instead.
 */
class CampusArRenderer(
    private val onStateChanged: (CampusArState) -> Unit
) : GLSurfaceView.Renderer {

    private val cameraRenderer = CameraBackgroundRenderer()
    private val pathRenderer = PathRenderer()
    private val arrowRenderer = ArrowRenderer()
    private val markerRenderer = MarkerRenderer()
    private val labelRenderer = LabelRenderer()

    var session: Session? = null

    /**
     * Compass bearing to the target, degrees clockwise from true north.
     * Set from GPS; null hides the path.
     */
    @Volatile
    var targetBearingDeg: Double? = null

    /** Straight-line distance to the target, in metres. */
    @Volatile
    var targetDistanceM: Double = 0.0

    /** Device heading, so we can convert a world bearing into camera space. */
    @Volatile
    var deviceHeadingDeg: Double? = null

    /** Label text for the destination; re-rasterised when it changes. */
    @Volatile
    var targetName: String? = null

    /**
     * The actual route geometry, in world coordinates.
     *
     * When present the ground ribbon follows THIS -- turns and all -- instead
     * of pointing straight at the destination through whatever is in the way.
     */
    @Volatile
    var routePoints: List<com.sarab.vision.core.LatLng> = emptyList()

    /** Where the user is, needed to place the route relative to the camera. */
    @Volatile
    var userPosition: com.sarab.vision.core.LatLng? = null

    private var lastLabelText: String? = null

    /**
     * Reads the plaque on the building in front of the camera.
     *
     * Optional on purpose: if it is never set, or its model fails to load,
     * navigation carries on exactly as before. A recognition extra must never
     * be able to take the camera down with it.
     */
    @Volatile
    var signReader: SignReader? = null

    /** Buildings the sign reader should consider. Set from GPS proximity. */
    @Volatile
    var signCandidates: List<com.sarab.vision.core.Landmark> = emptyList()

    /** Display rotation, needed to hand ML Kit a correctly oriented image. */
    @Volatile
    var displayRotationDegrees: Int = 0

    /** Height of the detected floor relative to the camera, in metres. */
    private var floorY: Float? = null

    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewProjectionMatrix = FloatArray(16)
    private val markerPos = FloatArray(3)
    private val labelPos = FloatArray(3)

    private var startNanos = 0L
    private var lastBuiltBearing = Double.NaN
    private var lastBuiltHeading = Double.NaN
    private var lastDiagnosticMs = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)

        try {
            cameraRenderer.createOnGlThread()
            pathRenderer.createOnGlThread()
            arrowRenderer.createOnGlThread()
            markerRenderer.createOnGlThread()
            labelRenderer.createOnGlThread()
        } catch (e: Exception) {
            Log.e(TAG, "Renderer init failed", e)
            onStateChanged(CampusArState.Error("تعذّر تشغيل الرسوميات"))
            return
        }

        startNanos = System.nanoTime()
        lastLabelText = null
        lastBuiltBearing = Double.NaN
        lastBuiltHeading = Double.NaN
        session?.setCameraTextureName(cameraRenderer.textureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
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

        // BEFORE the tracking gate, deliberately.
        //
        // Reading a plaque needs a camera image and nothing else -- no pose,
        // no plane, no world anchor. Gating it behind TRACKING meant the
        // reader was dead in exactly the conditions where ARCore struggles to
        // track and a user most wants to ask "which building is this?":
        // indoors, in poor light, or standing still in front of a flat wall.
        offerFrameToSignReader(frame)

        val tracking = camera.trackingState == TrackingState.TRACKING
        if (!tracking) {
            onStateChanged(CampusArState.Initialising)
            // Nothing is DRAWN without a trustworthy pose: a world-anchored
            // path drawn from a paused pose slides around the screen and
            // destroys the illusion it is really on the ground.
            return
        }

        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100f)
        Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        updateFloorHeight(session, camera.pose.ty())

        val bearing = targetBearingDeg
        val heading = deviceHeadingDeg

        if (bearing == null || heading == null) {
            onStateChanged(CampusArState.WaitingForGps)
            return
        }

        // Rebuild only when the bearing has actually moved. Rebuilding every
        // frame re-uploads GPU buffers 30 times a second for geometry that
        // barely changes, which is what overheated the phone before.
        // Rebuild when either the bearing OR the heading has moved: the route
        // is drawn relative to where the phone is pointing, so a turn changes
        // the geometry even when the destination has not moved at all.
        if (lastBuiltBearing.isNaN() ||
            kotlin.math.abs(shortestDelta(lastBuiltBearing, bearing)) > 1.5 ||
            lastBuiltHeading.isNaN() ||
            kotlin.math.abs(shortestDelta(lastBuiltHeading, heading)) > 1.5
        ) {
            rebuildPath(bearing, heading, camera.pose.tx(), camera.pose.tz())
            lastBuiltBearing = bearing
            lastBuiltHeading = heading
        }

        refreshLabel()

        val elapsed = (System.nanoTime() - startNanos) / 1_000_000_000f

        pathRenderer.draw(viewProjectionMatrix, elapsed)
        // Arrows over the ribbon: the ribbon shows where the path is, the
        // chevrons say which way to walk along it.
        arrowRenderer.draw(viewProjectionMatrix, elapsed)
        markerRenderer.draw(viewProjectionMatrix, markerPos, 0.35f, elapsed, false)
        labelRenderer.draw(viewProjectionMatrix, viewMatrix, labelPos)

        onStateChanged(CampusArState.Navigating)
        logDiagnostics(bearing, heading)
    }

    /**
     * Builds the ground ribbon pointing at the real-world bearing.
     *
     * ARCore's world frame has -Z as the direction the camera faced when the
     * session started, and that has no fixed relationship to true north. So
     * we convert: the angle between where the device points now and where the
     * target lies gives the direction in ARCore's own frame.
     */
    private fun rebuildPath(bearingDeg: Double, headingDeg: Double, camX: Float, camZ: Float) {
        // How far to turn from the current facing to face the target.
        val relative = Math.toRadians(shortestDelta(headingDeg, bearingDeg))

        // Camera forward in ARCore world space is -Z; rotate it by `relative`
        // about the vertical axis to get the target direction.
        val dirX = kotlin.math.sin(relative).toFloat()
        val dirZ = (-kotlin.math.cos(relative)).toFloat()

        val y = floorY ?: -1.4f // assume roughly eye height above the floor
        val length = groundPathLength(targetDistanceM).toFloat()
        if (length <= 0f) return

        // Prefer the REAL route. A straight ribbon along the bearing is a
        // pointer, not a path: it ignores every turn and sends the user into
        // whatever stands between them and the building.
        val here = userPosition
        val realPath = if (here != null && routePoints.size >= 2) {
            resamplePath(
                routeGroundPath(
                    userPosition = here,
                    routePoints = routePoints,
                    headingDeg = headingDeg,
                    maxVisibleMeters = length.toDouble()
                ),
                spacingMeters = 0.8f
            )
        } else {
            emptyList()
        }

        val points: List<Vec3>
        val endX: Float
        val endZ: Float

        if (realPath.size >= 2) {
            // routeGroundPath returns camera-relative offsets; lift them onto
            // the detected floor and into ARCore's world position.
            points = realPath.map { Vec3(camX + it.x, y, camZ + it.z) }
            endX = points.last().x
            endZ = points.last().z
        } else {
            // No routable network here yet: fall back to the straight stub so
            // the view still says which way to walk.
            val startOffset = 0.8f
            val straight = ArrayList<Vec3>(25)
            val steps = 24
            for (i in 0..steps) {
                val t = startOffset + (length - startOffset) * (i.toFloat() / steps)
                straight.add(Vec3(camX + dirX * t, y, camZ + dirZ * t))
            }
            points = straight
            endX = camX + dirX * length
            endZ = camZ + dirZ * length
        }

        pathRenderer.updatePath(points, widthMeters = 0.5f)
        arrowRenderer.updatePath(points)

        markerPos[0] = endX
        markerPos[1] = y + 0.5f
        markerPos[2] = endZ
        labelPos[0] = endX
        labelPos[1] = y + 1.2f
        labelPos[2] = endZ
    }

    /**
     * Hands the current camera image to the sign reader.
     *
     * acquireCameraImage throws whenever the image is not ready, which is
     * routine rather than exceptional, so a failure here is silent. The reader
     * owns closing the image: ARCore's pool is small and a leaked image stalls
     * the whole session within seconds.
     */
    private fun offerFrameToSignReader(frame: com.google.ar.core.Frame) {
        val reader = signReader ?: return
        val candidates = signCandidates
        if (candidates.isEmpty()) return

        try {
            reader.offer(frame.acquireCameraImage(), displayRotationDegrees, candidates)
        } catch (e: Throwable) {
            // NotYetAvailableException most of the time. Not worth a log line
            // at this rate, and never worth interrupting the draw.
        }
    }

    /**
     * Tracks the floor height so the path lies on the ground rather than at
     * an arbitrary depth. Falls back to an assumed height if no plane is
     * found, so the path still appears on a surface ARCore cannot resolve.
     */
    private fun updateFloorHeight(session: Session, cameraY: Float) {
        if (floorY != null) return
        try {
            val floor = session.getAllTrackables(Plane::class.java)
                .firstOrNull {
                    it.trackingState == TrackingState.TRACKING &&
                        it.type == Plane.Type.HORIZONTAL_UPWARD_FACING
                }
            if (floor != null) {
                floorY = floor.centerPose.ty()
                Log.i(TAG, "Floor found at y=${floorY}, camera at y=$cameraY")
                lastBuiltBearing = Double.NaN // force a rebuild at the right height
            }
        } catch (e: Exception) {
            Log.w(TAG, "Plane query failed", e)
        }
    }

    private fun refreshLabel() {
        val name = targetName ?: return
        val text = "$name · ${targetDistanceM.toInt()} م"
        if (text != lastLabelText) {
            labelRenderer.setText(name, "${targetDistanceM.toInt()} م")
            lastLabelText = text
        }
    }

    private fun shortestDelta(from: Double, to: Double): Double {
        var d = (to - from) % 360.0
        if (d > 180) d -= 360.0
        if (d <= -180) d += 360.0
        return d
    }

    private fun logDiagnostics(bearing: Double, heading: Double) {
        val now = System.currentTimeMillis()
        if (now - lastDiagnosticMs < 2000) return
        lastDiagnosticMs = now
        Log.i(
            TAG,
            "ar: bearing=${bearing.toInt()} heading=${heading.toInt()} " +
                "relative=${shortestDelta(heading, bearing).toInt()} " +
                "distance=${targetDistanceM.toInt()}m floorY=$floorY"
        )
    }

    /** Clears the cached floor so a new session re-detects it. */
    fun reset() {
        floorY = null
        lastBuiltBearing = Double.NaN
        lastLabelText = null
    }
}

/** What the AR view wants the UI to show. */
sealed interface CampusArState {
    /** ARCore has not converged on a pose yet. */
    data object Initialising : CampusArState

    /** Tracking is fine but there is no GPS bearing to aim at. */
    data object WaitingForGps : CampusArState

    /** Path is drawn. */
    data object Navigating : CampusArState

    data class Error(val message: String) : CampusArState
}
