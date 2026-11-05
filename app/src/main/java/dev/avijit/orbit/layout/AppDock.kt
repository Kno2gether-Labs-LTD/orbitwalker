package dev.avijit.orbit.layout

import kotlin.math.atan2
import kotlin.math.floor

/** The floating dock of recent apps under the centre screen: list, placement and hit-testing. Pure. */
object AppDock {
    const val MAX = 8
    /** Width/height of one dock item (icon over its label). */
    const val ITEM_ASPECT = 0.8f
    /** One item's width as a fraction of the centre screen's width. */
    private const val ITEM_OF_SCREEN = 0.09f
    private const val GAP_M = 0.05f
    private const val NEARER_M = 0.05f

    /** Most recent first, no duplicates, at most [max]. */
    fun push(recent: List<String>, component: String, max: Int = MAX): List<String> =
        (listOf(component) + recent.filter { it != component }).take(max)

    /** Width of the clock/date/battery section, in item widths. */
    const val WIDGET_SLOTS = 3f

    /** Which app is at horizontal position [u] (0..1 across the dock), or null (widgets/outside). */
    fun indexAt(u: Float, count: Int, widgetSlots: Float = 0f): Int? {
        if (count <= 0 || u < 0f || u > 1f) return null
        val x = u * (widgetSlots + count) - widgetSlots
        if (x < 0f) return null
        return floor(x).toInt().coerceAtMost(count - 1)
    }

    /** Below [anchor] (the centre screen), a little nearer, one item per app. */
    fun pose(anchor: ScreenPose, count: Int, widgetSlots: Float = 0f): ScreenPose {
        val itemW = anchor.widthM * ITEM_OF_SCREEN
        val h = itemW / ITEM_ASPECT
        val dist = (anchor.distanceM - NEARER_M).coerceAtLeast(0.5f)
        val drop = Math.toDegrees(atan2((anchor.heightM / 2 + h / 2 + GAP_M).toDouble(), anchor.distanceM.toDouble())).toFloat()
        return ScreenPose(anchor.yawDeg, anchor.pitchDeg - drop, dist, itemW * (count + widgetSlots), h)
    }
}
