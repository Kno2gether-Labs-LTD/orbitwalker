package dev.avijit.orbit.render

/** The single Spike B panel: 1.6 m × 1.0 m (16:10, matches the 1280×800 display), 1.5 m ahead. */
object PanelGeometry {
    const val WIDTH_M = 1.6f
    const val HEIGHT_M = 1.0f
    const val DISTANCE_M = 1.5f
    const val DISPLAY_WIDTH_PX = 1280
    const val DISPLAY_HEIGHT_PX = 800
    const val DISPLAY_DPI = 213

    /** (u, v) with (0, 0) = top-left of the panel → world position on the panel plane. */
    fun uvToWorld(u: Float, v: Float): FloatArray =
        floatArrayOf((u - 0.5f) * WIDTH_M, (0.5f - v) * HEIGHT_M, -DISTANCE_M)
}
