package dev.avijit.orbit.layout

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.tan

/** An arrow at the edge of the view that points to a cursor you can't see. */
object CursorHint {
    /** How far out the arrow sits, in normalised view coordinates (1 = the very edge). */
    const val EDGE = 0.85f

    class Arrow(val x: Float, val y: Float, val angleDeg: Float)

    /**
     * [v] is the cursor's direction in eye space (-z ahead). Null while the cursor is in view;
     * otherwise where on the border to draw the arrow, and which way it points (0° = right, 90° = up).
     */
    fun edgeArrow(v: FloatArray, verticalFovDeg: Float, aspect: Float): Arrow? {
        val tanV = tan(Math.toRadians(verticalFovDeg / 2.0)).toFloat()
        val tanH = tanV * aspect
        var x: Float
        var y: Float
        if (v[2] < 0f) {
            x = v[0] / -v[2] / tanH
            y = v[1] / -v[2] / tanV
            if (abs(x) <= 1f && abs(y) <= 1f) return null
        } else {
            x = v[0] / tanH
            y = v[1] / tanV
            if (abs(x) < 1e-4f && abs(y) < 1e-4f) { x = 0f; y = -1f } // straight behind: point down
        }
        val s = EDGE / max(abs(x), abs(y))
        x *= s; y *= s
        return Arrow(x, y, Math.toDegrees(atan2(y.toDouble(), x.toDouble())).toFloat())
    }
}
