package com.sarab.vision.ar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.AugmentedImage
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.sarab.vision.core.CampusMap
import com.sarab.vision.core.Destination
import com.sarab.vision.core.ImageMounting
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
 * How long to hunt for the reference image before giving up and using the
 * floor instead.
 *
 * A bad or unprintable reference image must never leave the user staring at
 * a camera feed with nothing on it.
 */
private const val IMAGE_SEARCH_TIMEOUT_SEC = 6f

/**
 * Squared distance (m^2) the origin anchor must move before the route is
 * rebuilt. 1cm -- below the visible threshold, far above ARCore's per-frame
 * pose jitter.
 */
private const val ORIGIN_REBUILD_THRESHOLD_SQ = 0.01f * 0.01f

/** Edge length of the cube shown on the reference image, in metres. */
private const val ORIGIN_MARKER_SIZE_M = 0.16f

/** How far to lift that cube off the image face, in metres. */
private const val ORIGIN_MARKER_LIFT_M = 0.10f


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

    /**
     * The active ARCore session.
     *
     * Assigning a NEW session resets all per-session tracking state. The
     * renderer outlives individual sessions (the Activity keeps one renderer
     * and rebuilds the session on resume), so without this the second run
     * inherited stale state from the first: a timed-out image search, a
     * detached origin anchor, and a cached route. That is why the app worked
     * once, then failed on every later launch until the user cleared app
     * data.
     */
    var session: Session? = null
        set(value) {
            if (field !== value) {
                field = value
                if (value != null) resetForNewSession()
            } else {
                field = value
            }
        }

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

    /** When the hunt for the reference image began. */
    private var imageSearchStartNanos = 0L

    /** Set once we give up on the image and switch to the floor. */
    private var imageSearchTimedOut = false

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
    private val originMarkerPos = FloatArray(3)

    /** Origin position the current route geometry was built from. */
    private val lastBuiltOriginPos = floatArrayOf(Float.NaN, Float.NaN, Float.NaN)

    /** True once the origin cube has a valid position to fall back on. */
    private var haveOriginMarkerPos = false


    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewProjectionMatrix = FloatArray(16)

    private var startNanos = 0L
    private var viewportWidth = 1
    private var viewportHeight = 1

    private var lastReportedState: ArUiState? = null

    /** Throttle for the once-a-second tracking diagnostic. */
    private var lastDiagnosticMs = 0L
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

    /**
     * Clears every piece of state tied to a previous ARCore session.
     *
     * Called automatically when a new session is attached. Anchors belong to
     * the session that created them, so a stale anchor from a closed session
     * can never track again -- keeping one meant the app sat forever with an
     * origin it could not use.
     */
    private fun resetForNewSession() {
        // Do NOT detach: the old session owns this anchor and is already
        // closed, so touching it would throw. Just drop the reference.
        originAnchor = null
        originIsFromImage = false
        worldRoute = emptyList()
        haveOriginMarkerPos = false
        lastBuiltOriginPos[0] = Float.NaN
        imageSearchStartNanos = 0L
        imageSearchTimedOut = false
        lastReportedState = null
        // Keep activeDestination: the user's chosen destination should
        // survive a restart. Force the label to be re-rasterised for the new
        // GL context, and the route to be rebuilt against the new origin.
        labelDirty = activeDestination != null
        Log.i(TAG, "Renderer state reset for new AR session")
    }

    /** Clears the origin so it can be re-acquired. */
    fun resetOrigin() {
        originAnchor?.detach()
        originAnchor = null
        originIsFromImage = false
        worldRoute = emptyList()
        haveOriginMarkerPos = false
        lastBuiltOriginPos[0] = Float.NaN
        // Give the reference image a fresh chance after an explicit reset.
        imageSearchStartNanos = 0L
        imageSearchTimedOut = false

        // Plane finding was switched off once the origin was fixed; it has to
        // come back or the plane fallback could never acquire again.
        session?.let { s ->
            try {
                val cfg = s.config
                cfg.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                s.configure(cfg)
            } catch (e: Exception) {
                Log.w(TAG, "Could not re-enable plane finding", e)
            }
        }
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
        // NO frame throttling here.
        //
        // An earlier version returned early to cap the frame rate. That was a
        // mistake: returning from onDrawFrame without drawing leaves the
        // surface with stale/undefined contents, so the preview visibly
        // stuttered and the effective frame rate collapsed. ARCore's
        // session.update() already paces us to the camera stream, which is
        // the correct and only throttle needed.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val session = this.session ?: return

        // Bind the camera texture every frame.
        //
        // An earlier version cached this to save a JNI call. That is unsafe:
        // ARCore forgets the binding across a session pause/resume, and the
        // cached flag then kept us from re-binding, leaving a frozen feed.
        // The call is cheap; correctness wins.
        session.setCameraTextureName(cameraRenderer.textureId)

        val frame = try {
            session.update()
        } catch (e: Exception) {
            Log.e(TAG, "Session update failed", e)
            return
        }

        val camera = frame.camera
        cameraRenderer.draw(frame)

        // Log why tracking is not established, at most once a second. Without
        // this the app just sits on a hint with no way to tell whether the
        // problem is lighting, motion, or the marker itself.
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastDiagnosticMs > 1000) {
            lastDiagnosticMs = nowMs
            val imgs = try {
                session.getAllTrackables(AugmentedImage::class.java)
            } catch (e: Exception) {
                emptyList<AugmentedImage>()
            }
            Log.i(
                TAG,
                "diag: cameraTracking=${camera.trackingState} " +
                    "reason=${camera.trackingFailureReason} " +
                    "images=${imgs.size} " +
                    imgs.joinToString { "${it.name}:${it.trackingState}/${it.trackingMethod}" } +
                    " origin=${if (originAnchor != null) "yes" else "no"}" +
                    " usingImage=$usingImageOrigin timedOut=$imageSearchTimedOut"
            )
        }

        val cameraFullyTracking = camera.trackingState == TrackingState.TRACKING

        // Hunt for the reference image regardless of full camera tracking.
        //
        // ARCore reports augmented images independently of camera tracking,
        // and the camera only reaches TRACKING once it has enough parallax. A
        // user holding the phone steady on the board never got there, so the
        // image was never even looked for -- the app sat on "point at the
        // board" while the board filled the frame.
        if (originAnchor == null && usingImageOrigin && !imageSearchTimedOut) {
            // Start the timeout clock here too, otherwise it only advanced
            // under full tracking and the plane fallback never fired either.
            if (imageSearchStartNanos == 0L) {
                imageSearchStartNanos = System.nanoTime()
            }
            tryAcquireImageOrigin(frame)
        }

        if (!cameraFullyTracking) {
            // Distinguish "board found, but ARCore still needs a little
            // motion" from "still looking". The user is otherwise left
            // staring at a hint telling them to point at a board they are
            // already pointing at.
            report(
                if (originAnchor != null) ArUiState.OriginFoundAwaitingTracking
                else currentSearchState()
            )

            // Draw nothing while the camera pose is not trustworthy.
            //
            // When tracking is PAUSED, ARCore's view matrix does not follow
            // the device properly, so world-anchored objects stop being
            // world-anchored: the cube smears across the screen and clings to
            // the edge as you turn, instead of staying put on the real board.
            // Showing a wrong position is worse than showing nothing -- it
            // destroys the illusion that the object is really there.
            //
            // The camera background is already drawn above, so the user still
            // sees a live feed while ARCore acquires tracking.
            return
        }

        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100f)
        Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        // Acquire the origin, preferring the reference image.
        //
        // Crucially, waiting for an image is NOT allowed to block forever: if
        // the image is never found (wrong image, bad lighting, user has not
        // pointed at it) we fall back to the floor plane so the route still
        // appears. Without this timeout a poor reference image leaves the app
        // searching indefinitely and nothing is ever drawn.
        if (originAnchor == null) {
            if (usingImageOrigin && !imageSearchTimedOut) {
                // The search itself already ran above (before the tracking
                // gate); here we only decide when to give up on it.
                val waited = (System.nanoTime() - imageSearchStartNanos) / 1_000_000_000f
                if (imageSearchStartNanos != 0L && waited > IMAGE_SEARCH_TIMEOUT_SEC) {
                    Log.w(
                        TAG,
                        "Reference image not found after ${IMAGE_SEARCH_TIMEOUT_SEC}s; " +
                            "falling back to plane-based origin."
                    )
                    imageSearchTimedOut = true
                    report(ArUiState.ImageSearchTimedOut)
                }
            } else if (cameraFullyTracking) {
                // Plane detection needs a reliable camera pose, so unlike the
                // image search this one does require full tracking.
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

        // The cube on the reference image is drawn as soon as an origin
        // exists, INDEPENDENTLY of whether a route has been built.
        //
        // It used to be nested inside the "route ready" branch, so if the
        // board was recognised before a destination was chosen (or the route
        // was empty for any reason) nothing appeared at all -- the board was
        // tracked, but the user had no way to know. This cube is the
        // confirmation that tracking worked, so it must not depend on the
        // route.
        // Refresh the cached position while the anchor is tracking...
        originAnchor?.let { anchor ->
            if (anchor.trackingState == TrackingState.TRACKING) {
                val p = anchor.pose
                // Lift the cube clear of the image surface along the image's
                // own normal (+Y is out of the face). Sitting it exactly on
                // the centre buried half of it inside the board, which made a
                // small cube nearly invisible against the artwork.
                val n = p.yAxis
                originMarkerPos[0] = p.tx() + n[0] * ORIGIN_MARKER_LIFT_M
                originMarkerPos[1] = p.ty() + n[1] * ORIGIN_MARKER_LIFT_M
                originMarkerPos[2] = p.tz() + n[2] * ORIGIN_MARKER_LIFT_M
                haveOriginMarkerPos = true
            }
        }

        // ...but draw from the CACHE, outside the anchor block. Drawing inside
        // `originAnchor?.let` meant the cube vanished the instant the anchor
        // went null or stopped tracking, which is precisely the blink the
        // cached position was meant to prevent.
        if (haveOriginMarkerPos) {
            markerRenderer.draw(
                viewProjectionMatrix,
                originMarkerPos,
                ORIGIN_MARKER_SIZE_M,
                elapsed,
                // Highlight it: this is the "tracking works" confirmation,
                // so it should read clearly against a busy marker.
                true
            )
        }

        if (originAnchor != null && worldRoute.size >= 2) {
            // Only rebuild when the anchor has actually MOVED. Rebuilding
            // every frame re-transformed ~40 points, re-built the ribbon and
            // re-uploaded GPU buffers 60x a second, which pushed the app to
            // >120% CPU: the phone got hot and janky, and the thermal
            // throttling made ARCore drop tracking, so the cube flickered in
            // and out. ARCore refines the pose in small steps, so a modest
            // threshold keeps the path glued to the floor at a fraction of
            // the cost.
            maybeRebuildRoute()

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
        if (usingImageOrigin && !imageSearchTimedOut) {
            ArUiState.SearchingForImage
        } else {
            ArUiState.Scanning
        }

    /**
     * Looks for the reference image and anchors the world origin to it.
     *
     * Only FULL_TRACKING is accepted: a PAUSED image has a stale pose, and
     * anchoring the whole campus route to a stale pose puts the path in the
     * wrong place entirely.
     */
    private fun tryAcquireImageOrigin(frame: Frame) {
        val session = this.session ?: return

        // Query ALL augmented images, not just getUpdatedTrackables().
        //
        // getUpdatedTrackables() only returns images that changed on THIS
        // frame. Once ARCore locks onto a stationary board it stops reporting
        // it as updated, so polling only the updated set meant we could miss
        // the image entirely and sit there waiting -- which is why detection
        // felt slow and unreliable.
        val images = session.getAllTrackables(AugmentedImage::class.java)

        val candidates = images.filter {
            it.name == ORIGIN_IMAGE_NAME && it.trackingState == TrackingState.TRACKING
        }

        // Prefer a fully-tracked image, but accept LAST_KNOWN_POSE as a
        // fallback. Requiring FULL_TRACKING was too strict: ARCore drops in
        // and out of it constantly for a board seen at an angle or in
        // imperfect light, and each drop made the cube vanish.
        val match = candidates.firstOrNull {
            it.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING
        } ?: candidates.firstOrNull {
            it.trackingMethod == AugmentedImage.TrackingMethod.LAST_KNOWN_POSE
        } ?: return

        originAnchor?.detach()
        originAnchor = match.createAnchor(match.centerPose)
        originIsFromImage = true

        // Work out how the marker is actually mounted instead of trusting a
        // hard-coded setting. The image's +Y axis points out of its face, so
        // comparing that to world up tells us the orientation directly:
        //   face pointing up   -> lying flat on a table/floor
        //   face pointing out  -> hanging on a wall
        // Getting this wrong draws the route metres away from where the user
        // is looking, which reads as "the path is missing".
        val faceNormalY = match.centerPose.yAxis[1]
        detectedMounting = if (kotlin.math.abs(faceNormalY) > 0.65f) {
            ImageMounting.FLAT
        } else {
            ImageMounting.VERTICAL
        }

        // When the marker lies flat, the route starts on the marker's own
        // plane, so there is no board height to drop from.
        detectedBoardHeight =
            if (detectedMounting == ImageMounting.FLAT) 0f else CampusMap.boardHeightMeters

        Log.i(
            TAG,
            "World origin acquired from reference image '${match.name}' " +
                "(mounting=$detectedMounting, faceNormalY=$faceNormalY)"
        )

        report(ArUiState.OriginAcquired)
        rebuildRoute()

        // Plane finding is only needed to acquire an origin. Leaving it on
        // afterwards keeps ARCore's plane solver running every frame for no
        // benefit, which is pure heat on a mid-range phone.
        disablePlaneFinding()
    }

    /**
     * Turns off plane detection once the origin is fixed.
     *
     * Reconfiguring a live session is cheap and does not disturb tracking.
     */
    private fun disablePlaneFinding() {
        val s = session ?: return
        try {
            val cfg = s.config
            if (cfg.planeFindingMode != Config.PlaneFindingMode.DISABLED) {
                cfg.planeFindingMode = Config.PlaneFindingMode.DISABLED
                s.configure(cfg)
                Log.i(TAG, "Plane finding disabled (origin fixed) to save CPU")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not disable plane finding", e)
        }
    }

    /** Mounting inferred from the tracked image's own orientation. */
    private var detectedMounting = ImageMounting.VERTICAL
    private var detectedBoardHeight = 0f

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
        disablePlaneFinding()
    }

    /**
     * Rebuilds the route only when the origin anchor has meaningfully moved.
     *
     * ARCore continuously refines an anchor's pose by tiny amounts. Acting on
     * every one of those refinements is pure waste; acting on none of them
     * lets the path drift off the floor. Comparing against the last pose we
     * built from gives us both.
     */
    private fun maybeRebuildRoute() {
        val anchor = originAnchor ?: return
        if (anchor.trackingState != TrackingState.TRACKING) return

        val p = anchor.pose
        val dx = p.tx() - lastBuiltOriginPos[0]
        val dy = p.ty() - lastBuiltOriginPos[1]
        val dz = p.tz() - lastBuiltOriginPos[2]
        val movedSq = dx * dx + dy * dy + dz * dz

        // 1cm of drift is far below what is visible at these distances.
        if (movedSq < ORIGIN_REBUILD_THRESHOLD_SQ) return

        lastBuiltOriginPos[0] = p.tx()
        lastBuiltOriginPos[1] = p.ty()
        lastBuiltOriginPos[2] = p.tz()
        rebuildRoute()
    }

    private fun rebuildRoute() {
        val anchor = originAnchor ?: return
        val destination = activeDestination ?: return
        if (anchor.trackingState != TrackingState.TRACKING) return

        val originPose = anchor.pose

        val local = destination.waypoints.map { wp ->
            if (originIsFromImage) {
                // Use the mounting inferred from the tracked image, not the
                // static setting -- see tryAcquireImageOrigin().
                toImageLocal(wp, detectedMounting, detectedBoardHeight)
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
        // Do NOT require a route here: the origin cube is tappable as soon as
        // the board is tracked, before any destination has been chosen.
        if (worldRoute.isEmpty() && !haveOriginMarkerPos) return

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

        // Test the destination marker first (the primary target). Guarded on
        // the route existing: without it markerWorldPos is still (0,0,0) and
        // would produce phantom hits at the world origin.
        val destHit = if (worldRoute.size >= 2) {
            val destCenter = Vec3(markerWorldPos[0], markerWorldPos[1], markerWorldPos[2])
            // Enlarged pick volume: the marker can be 15m away, and a
            // fingertip is not precise. Covers the label above it too.
            intersectAabb(ray, destCenter, MARKER_SIZE_M * 1.6f)
        } else {
            null
        }

        // ...then the small cube sitting on the reference image. Without this
        // the only tappable object is up to 15m away, so a user standing at
        // the marker taps the cube right in front of them and nothing happens.
        val originCenter = Vec3(originMarkerPos[0], originMarkerPos[1], originMarkerPos[2])
        val originHit = if (originAnchor != null && haveOriginMarkerPos) {
            // Generous pick volume relative to the cube's size, since it is
            // small on screen and fingers are not precise.
            intersectAabb(ray, originCenter, ORIGIN_MARKER_SIZE_M * 1.8f)
        } else {
            null
        }

        // Nearest hit wins, so an origin cube in the foreground is not
        // shadowed by a distant destination marker behind it.
        if (destHit != null && (originHit == null || destHit <= originHit)) {
            report(ArUiState.MarkerTapped)
        } else if (originHit != null) {
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

    /** Gave up on the reference image; now using the floor instead. */
    data object ImageSearchTimedOut : ArUiState

    /**
     * The board is anchored but ARCore has not converged on a camera pose
     * yet, so nothing is drawn. The user needs to move slightly.
     */
    data object OriginFoundAwaitingTracking : ArUiState

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
