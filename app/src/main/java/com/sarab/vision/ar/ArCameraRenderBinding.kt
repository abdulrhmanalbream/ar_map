package com.sarab.vision.ar

/** Display rotation is the Surface constant (0..3), not the angle in degrees. */
internal data class ArDisplayGeometry(val rotation: Int, val width: Int, val height: Int)

/** GL-thread state: a late/new session must receive the current surface, even at the same size. */
internal class ArCameraRenderBinding {
    private var boundSession: Any? = null
    private var boundTexture = -1
    private var appliedGeometry: ArDisplayGeometry? = null

    fun reset() {
        boundSession = null
        boundTexture = -1
        appliedGeometry = null
    }

    /** Commit each binding only after its API call succeeds, so a transient failure can retry. */
    fun apply(session: Any, texture: Int, width: Int, height: Int, rotationDegrees: Int,
        bindTexture: () -> Unit, setGeometry: (ArDisplayGeometry) -> Unit): Boolean {
        require(texture > 0 && width > 0 && height > 0)
        require(rotationDegrees in 0..270 && rotationDegrees % 90 == 0)
        var changed = false
        if (boundSession !== session || boundTexture != texture) {
            bindTexture()
            boundSession = session
            boundTexture = texture
            appliedGeometry = null
            changed = true
        }
        val previous = appliedGeometry
        val rotation = rotationDegrees / 90
        if (previous == null || previous.rotation != rotation || previous.width != width || previous.height != height) {
            val geometry = ArDisplayGeometry(rotation, width, height)
            setGeometry(geometry)
            appliedGeometry = geometry
            changed = true
        }
        return changed
    }
}
