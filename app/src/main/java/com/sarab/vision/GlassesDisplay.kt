package com.sarab.vision

import android.app.Presentation
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.sarab.vision.core.GuidanceMode
import com.sarab.vision.core.HudCommand
import com.sarab.vision.core.HudMenuState
import com.sarab.vision.core.hudMenuView
import com.sarab.vision.core.relativeBearing
import com.sarab.vision.ui.GlassesHud

private const val TAG = "SarabGlasses"

/**
 * Puts the navigation HUD on display glasses whenever a pair is plugged in.
 *
 * Glasses like the XREAL One are, to Android, nothing but a USB-C external
 * display: tracking, GPS and rendering all stay on the phone, and the
 * glasses' own chip anchors the picture in front of the wearer. By default
 * Android mirrors the phone screen onto them; the Presentation API lets this
 * app replace that mirror with a purpose-built landscape HUD while the phone
 * keeps the camera view and the touch controls.
 *
 * On Samsung phones this requires screen-mirroring mode, not DeX: DeX runs
 * its own desktop on the external display and apps cannot present to it.
 */
class GlassesDisplayController(
    private val activity: ComponentActivity,
    private val campus: CampusState,
    /** Live menu state, read from inside the HUD's composition. */
    private val menu: () -> HudMenuState,
    /** Where input that reaches the presentation window gets routed. */
    private val onCommand: (HudCommand) -> Unit,
    /**
     * Fired when glasses appear or vanish, so the activity can change how it
     * treats shared inputs (volume keys, media buttons) only while the
     * wearer actually depends on them.
     */
    private val onConnectionChanged: (Boolean) -> Unit = {}
) {
    private val displayManager =
        activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private var presentation: GlassesHudPresentation? = null
    private var lastConnected = false
    private var mirror = false

    /** True while glasses (any external presentation display) are plugged in. */
    val connected: Boolean get() = lastConnected

    /** True while the HUD owns the glasses. False in mirror mode. */
    val hudShown: Boolean get() = presentation != null

    /**
     * Mirror mode: dismiss the HUD and let Android's default screen
     * mirroring show the whole phone -- camera view, menus, map -- on the
     * glasses. The HUD is the better instrument for see-through optics, but
     * the full picture is the user's to choose, not ours to withhold.
     */
    fun setMirror(on: Boolean) {
        mirror = on
        sync()
    }

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = sync()
        override fun onDisplayRemoved(displayId: Int) = sync()
        override fun onDisplayChanged(displayId: Int) = Unit
    }

    /** Call from onResume: shows the HUD if glasses are already connected. */
    fun start() {
        displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        sync()
    }

    /** Call from onPause: the HUD must not outlive the sensors feeding it. */
    fun stop() {
        displayManager.unregisterDisplayListener(listener)
        presentation?.dismiss()
        presentation = null
        if (lastConnected) {
            lastConnected = false
            onConnectionChanged(false)
        }
    }

    private fun sync() {
        val display = displayManager
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.isValid }

        val connectedNow = display != null
        if (connectedNow != lastConnected) {
            lastConnected = connectedNow
            onConnectionChanged(connectedNow)
        }

        if (display == null || mirror) {
            presentation?.dismiss()
            presentation = null
            return
        }
        if (presentation?.display?.displayId == display.displayId) return

        presentation?.dismiss()
        presentation = GlassesHudPresentation(activity, display, campus, menu, onCommand)
        try {
            presentation?.show()
            Log.i(TAG, "HUD presented on external display: ${display.name}")
        } catch (e: WindowManager.InvalidDisplayException) {
            // The cable can be unplugged between enumerating the display and
            // showing on it; treat it as "no glasses" rather than crashing.
            Log.w(TAG, "External display vanished before show()", e)
            presentation = null
        }
    }
}

private class GlassesHudPresentation(
    private val activity: ComponentActivity,
    display: Display,
    private val campus: CampusState,
    private val menu: () -> HudMenuState,
    private val onCommand: (HudCommand) -> Unit
) : Presentation(activity, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Any window chrome would glow as a visible frame on see-through
        // glasses, so the window itself is forced edge-to-edge black before
        // content goes in.
        window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val view = ComposeView(context).apply {
            // A Presentation window sits outside the Activity's view tree, so
            // Compose cannot find a lifecycle by walking up the hierarchy.
            // Point it at the host Activity's explicitly, or setContent
            // throws on attach.
            setViewTreeLifecycleOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setContent {
                GlassesHud(
                    guidance = campus.guidance,
                    targetName = campus.target?.name,
                    relativeDegrees = relativeToTarget(),
                    needsCalibration = campus.compassNeedsCalibration,
                    menu = hudMenuView(menu(), campus.fix?.position)
                )
            }
        }
        setContentView(view)
    }

    /**
     * Input safety net: it is not certain which display Android will route
     * the glasses' HID pointer to when a Presentation is up. The activity
     * intercepts mouse events on the default display; this catches the case
     * where they land on the external one instead. Both funnel into the same
     * commands, so it does not matter which one wins on real hardware.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) onCommand(HudCommand.CONFIRM)
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f) {
                onCommand(if (v < 0) HudCommand.NEXT else HudCommand.PREV)
                return true
            }
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    /**
     * Where the target is relative to the phone's heading, for the arrow.
     *
     * Compass mode already computed it; the other modes get the same maths
     * from the live heading and bearing. Reads Compose state, so it must be
     * called from inside composition to keep the arrow updating.
     */
    private fun relativeToTarget(): Double? {
        (campus.guidance as? GuidanceMode.Compass)?.let { return it.relativeDegrees }
        val heading = campus.headingDegrees ?: return null
        val bearing = campus.targetBearing() ?: return null
        return relativeBearing(heading, bearing)
    }
}
