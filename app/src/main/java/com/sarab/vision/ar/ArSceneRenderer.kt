package com.sarab.vision.ar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.sarab.vision.core.Ray
import com.sarab.vision.core.Vec3
import com.sarab.vision.core.buildPathPoints
import com.sarab.vision.core.intersectAabb
import com.sarab.vision.render.CameraBackgroundRenderer
import com.sarab.vision.render.MarkerRenderer
import com.sarab.vision.render.PathRenderer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

private const val TAG = "SarabArRenderer"

/** How far in front of the user the path is laid, in metres. */
private const val PATH_LENGTH_M = 4.0f

/** Cube edge length in metres. */
private const val MARKER_SIZE_M = 0.28f

/** Cube centre height above the floor, in metres. */
private const val MARKER_HOVER_M = 0.35f

/**
 * Owns the GL thread and every per-frame AR operation.
 *
 * Responsibilities are deliberately narrow: ARCore session pumping, placing
 * the path once a floor is found, drawing, and answering tap queries. All UI
 * state is pushed out through [onStateChanged] so Compose owns presentation.
 */
class ArSceneRenderer(
    private val onStateChanged: (ArUiState) -> Unit
) : GLSurfaceView.Renderer {

    private val cameraRenderer = CameraBackgroundRenderer()
    private val pathRenderer = PathRenderer()
    private val markerRenderer = MarkerRenderer()

    var session: Session? = null

    /** Anchor at the start of the path; owns the path's world position. */
    private var pathAnchor: Anchor? = null

    private var markerWorldPos = FloatArray(3)
    private var markerPlaced = false
    private var markerHighlighted = false

    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewProjectionMatrix = FloatArray(16)

    private var startNanos = 0L
    private var viewportWidth = 1
    private var viewportHeight = 1

    /** Set from the UI thread on tap; consumed on the GL thread. */
    private val pendingTap = AtomicBoolean(false)
    private var pendingTapX = 0f
    private var pendingTapY = 0f

    private var lastReportedState: ArUiState? = null

    fun onTap(x: Float, y: Float) {
        pendingTapX = x
        pendingTapY = y
        pendingTap.set(true)
    }

    /** Clears the current placement so the path can be re-laid. */
    fun resetPlacement() {
        pathAnchor?.detach()
        pathAnchor = null
        markerPlaced = false
        markerHighlighted = false
    }

    fun setHighlighted(highlighted: Boolean) {
        markerHighlighted = highlighted
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)

        try {
            cameraRenderer.createOnGlThread()
            pathRenderer.createOnGlThread()
            markerRenderer.createOnGlThread()
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
        // ARCore needs the texture name every resume, not just at creation.
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
            report(ArUiState.Scanning)
            return
        }

        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100f)
        Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        if (!markerPlaced) {
            tryPlacePath(session, frame)
        }

        val elapsed = (System.nanoTime() - startNanos) / 1_000_000_000f

        if (markerPlaced) {
            // The anchor may drift as ARCore refines its map; re-derive the
            // path from it each frame so the visuals stay glued to the floor.
            refreshFromAnchor()

            pathRenderer.draw(viewProjectionMatrix, elapsed)
            markerRenderer.draw(
                viewProjectionMatrix,
                markerWorldPos,
                MARKER_SIZE_M,
                elapsed,
                markerHighlighted
            )
            report(ArUiState.Ready)
        }

        if (pendingTap.getAndSet(false)) {
            handleTap(pendingTapX, pendingTapY)
        }
    }

    /**
     * Places the path once a horizontal floor plane is tracked.
     *
     * We anchor to the plane nearest the user rather than the first plane
     * ARCore reports, because early detections are often a table or a wall
     * fragment.
     */
    private fun tryPlacePath(session: Session, frame: com.google.ar.core.Frame) {
        val planes = session.getAllTrackables(Plane::class.java)
            .filter {
                it.trackingState == TrackingState.TRACKING &&
                    it.type == Plane.Type.HORIZONTAL_UPWARD_FACING
            }

        if (planes.isEmpty()) {
            report(ArUiState.Scanning)
            return
        }

        val camera = frame.camera
        val camPose = camera.pose

        // Prefer the plane closest to (and below) the camera -- the floor.
        val floor = planes.minByOrNull { plane ->
            val c = plane.centerPose
            val dx = c.tx() - camPose.tx()
            val dz = c.tz() - camPose.tz()
            dx * dx + dz * dz
        } ?: return

        // Forward direction on the floor: camera forward flattened to the
        // horizontal plane. -Z is forward in ARCore's camera space.
        val zAxis = camPose.zAxis
        var fx = -zAxis[0]
        var fz = -zAxis[2]
        val len = kotlin.math.sqrt(fx * fx + fz * fz)
        if (len < 1e-4f) {
            // The user is pointing straight down; no usable heading yet.
            report(ArUiState.Scanning)
            return
        }
        fx /= len
        fz /= len

        // Start the path slightly ahead so it is not underfoot.
        val startX = camPose.tx() + fx * 0.6f
        val startZ = camPose.tz() + fz * 0.6f
        val floorY = floor.centerPose.ty()

        val anchorPose = com.google.ar.core.Pose.makeTranslation(startX, floorY, startZ)
        pathAnchor?.detach()
        pathAnchor = floor.createAnchor(anchorPose)

        pathForwardX = fx
        pathForwardZ = fz
        markerPlaced = true
        report(ArUiState.Ready)
    }

    private var pathForwardX = 0f
    private var pathForwardZ = -1f

    /** Rebuilds path geometry and marker position from the live anchor pose. */
    private fun refreshFromAnchor() {
        val anchor = pathAnchor ?: return
        if (anchor.trackingState != TrackingState.TRACKING) return

        val p = anchor.pose
        val start = Vec3(p.tx(), p.ty() + 0.01f, p.tz())
        val forward = Vec3(pathForwardX, 0f, pathForwardZ)

        val points = buildPathPoints(start, forward, PATH_LENGTH_M)
        pathRenderer.updatePath(points)

        val end = points.lastOrNull() ?: return
        markerWorldPos[0] = end.x
        markerWorldPos[1] = end.y + MARKER_HOVER_M
        markerWorldPos[2] = end.z
    }

    /**
     * Converts a screen tap into a world ray and tests it against the marker.
     *
     * Uses an inverse view-projection unprojection rather than ARCore's
     * hit-test, because we need to hit our own virtual cube, not a plane.
     */
    private fun handleTap(screenX: Float, screenY: Float) {
        if (!markerPlaced) return

        val invVp = FloatArray(16)
        if (!Matrix.invertM(invVp, 0, viewProjectionMatrix, 0)) {
            Log.w(TAG, "View-projection not invertible; ignoring tap")
            return
        }

        // Screen -> normalised device coordinates. Y is flipped because
        // Android touch origin is top-left and NDC origin is bottom-left.
        val ndcX = (2f * screenX / viewportWidth) - 1f
        val ndcY = 1f - (2f * screenY / viewportHeight)

        val near = unproject(invVp, ndcX, ndcY, -1f) ?: return
        val far = unproject(invVp, ndcX, ndcY, 1f) ?: return

        val ray = Ray(near, (far - near).normalized())
        val center = Vec3(markerWorldPos[0], markerWorldPos[1], markerWorldPos[2])

        // Test against a slightly enlarged box: the cube is small on screen at
        // 4m, and a finger is not precise. This is a usability allowance, not
        // a correctness fudge.
        val pickRadius = MARKER_SIZE_M * 0.9f

        if (intersectAabb(ray, center, pickRadius) != null) {
            report(ArUiState.MarkerTapped)
        }
    }

    /** Unprojects an NDC point at the given depth into world space. */
    private fun unproject(invVp: FloatArray, x: Float, y: Float, z: Float): Vec3? {
        val input = floatArrayOf(x, y, z, 1f)
        val out = FloatArray(4)
        Matrix.multiplyMV(out, 0, invVp, 0, input, 0)
        if (kotlin.math.abs(out[3]) < 1e-6f) return null
        return Vec3(out[0] / out[3], out[1] / out[3], out[2] / out[3])
    }

    /** Pushes state to the UI, suppressing duplicate emissions. */
    private fun report(state: ArUiState) {
        // MarkerTapped is an event, not a level -- always deliver it.
        if (state != ArUiState.MarkerTapped && state == lastReportedState) return
        lastReportedState = state
        onStateChanged(state)
    }
}

/** What the AR layer wants the UI to show. */
sealed interface ArUiState {
    data object Scanning : ArUiState
    data object Ready : ArUiState
    data object MarkerTapped : ArUiState
    data class Error(val message: String) : ArUiState
}
