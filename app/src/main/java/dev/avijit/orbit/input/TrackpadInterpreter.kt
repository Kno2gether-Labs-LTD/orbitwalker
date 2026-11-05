package dev.avijit.orbit.input

import kotlin.math.hypot

sealed interface TrackpadAction {
    data class Move(val dx: Float, val dy: Float) : TrackpadAction
    data object Tap : TrackpadAction
    data class Scroll(val dy: Float) : TrackpadAction
    /** Two-finger spread (>1) or squeeze (<1) since the last Pinch. */
    data class Pinch(val scale: Float) : TrackpadAction
}

/**
 * Turns raw pointer events from the phone trackpad into cursor moves, taps and scrolls.
 * One finger moves (or taps); two or more fingers scroll, and that gesture can never become
 * a tap or a move until every finger has lifted.
 */
class TrackpadInterpreter(
    val tapMaxMs: Long = 250,
    val tapSlopPx: Float = 24f,
    val gain: Float = 1.6f,
) {
    private class Pointer(var x: Float, var y: Float)

    private val pointers = HashMap<Int, Pointer>()
    private var gestureStartMs = 0L
    private var travel = 0f
    private var multiTouch = false
    private var lastSpan = 0f

    fun down(id: Int, x: Float, y: Float, tMs: Long): List<TrackpadAction> {
        if (pointers.isEmpty()) {
            gestureStartMs = tMs
            travel = 0f
            multiTouch = false
        }
        pointers[id] = Pointer(x, y)
        if (pointers.size > 1) {
            multiTouch = true
            lastSpan = span()
        }
        return emptyList()
    }

    fun move(id: Int, x: Float, y: Float, tMs: Long): List<TrackpadAction> {
        val p = pointers[id] ?: return emptyList()
        val dx = x - p.x
        val dy = y - p.y
        p.x = x; p.y = y
        if (dx == 0f && dy == 0f) return emptyList()
        travel += hypot(dx, dy)
        return if (multiTouch) {
            val span = span()
            if (pointers.size >= 2 && lastSpan > 0f && kotlin.math.abs(span / lastSpan - 1f) > PINCH_THRESHOLD) {
                val ratio = span / lastSpan
                lastSpan = span
                listOf(TrackpadAction.Pinch(ratio))
            } else if (dy != 0f) {
                listOf(TrackpadAction.Scroll(dy / pointers.size * gain))
            } else {
                emptyList()
            }
        } else {
            listOf(TrackpadAction.Move(dx * gain, dy * gain))
        }
    }

    private fun span(): Float {
        val ps = pointers.values.take(2)
        return if (ps.size < 2) 0f else hypot(ps[0].x - ps[1].x, ps[0].y - ps[1].y)
    }

    fun up(id: Int, x: Float, y: Float, tMs: Long): List<TrackpadAction> {
        val out = move(id, x, y, tMs).toMutableList()
        pointers.remove(id)
        lastSpan = if (pointers.size >= 2) span() else 0f
        if (pointers.isEmpty() && !multiTouch && tMs - gestureStartMs <= tapMaxMs && travel <= tapSlopPx) {
            out.removeAll { it is TrackpadAction.Move }
            out += TrackpadAction.Tap
        }
        return out
    }

    private companion object {
        const val PINCH_THRESHOLD = 0.04f
    }
}
