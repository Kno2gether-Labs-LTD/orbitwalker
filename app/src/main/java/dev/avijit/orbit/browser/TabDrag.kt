package dev.avijit.orbit.browser

import kotlin.math.abs

/** Dragging a tab down, out of the tab strip, moves it onto a screen of its own. */
object TabDrag {
    fun popsOut(dx: Float, dy: Float, stripHeightPx: Float): Boolean = dy > stripHeightPx * 1.5f && dy > abs(dx)
}
