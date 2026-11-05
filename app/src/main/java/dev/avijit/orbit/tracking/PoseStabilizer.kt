package dev.avijit.orbit.tracking

import kotlin.math.abs

/**
 * Holds the rendered head pose still through sensor jitter (sub-pixel motion makes text shimmer)
 * with a *soft* deadband: the held pose trails the real one by at most [DEADBAND_DEG] and moves by
 * only the excess each frame, so slow turns stay smooth (no stepping) and fast turns have no lag
 * beyond that fraction of a pixel.
 */
class PoseStabilizer {
    private var held: Quat? = null

    fun filter(q: Quat): Quat {
        val h = held ?: return q.also { held = it }
        val angle = angleDeg(h, q)
        if (angle <= DEADBAND_DEG) return h
        val t = ((angle - DEADBAND_DEG) / angle).toFloat()
        return slerp(h, q, t).also { held = it }
    }

    companion object {
        /** About one glasses pixel at 45 px/°. */
        const val DEADBAND_DEG = 0.02

        /** Rotation angle between two orientations; atan2 of the relative rotation stays precise at tiny angles. */
        fun angleDeg(a: Quat, b: Quat): Double {
            val r = a.conjugate() * b
            val v = kotlin.math.sqrt((r.x * r.x + r.y * r.y + r.z * r.z).toDouble())
            return Math.toDegrees(2 * kotlin.math.atan2(v, abs(r.w).toDouble()))
        }

        /** Normalised lerp along the shorter arc (fine for the tiny steps used here). */
        private fun slerp(a: Quat, b: Quat, t: Float): Quat {
            val sign = if (a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z < 0) -1f else 1f
            return Quat(
                a.w + (sign * b.w - a.w) * t, a.x + (sign * b.x - a.x) * t,
                a.y + (sign * b.y - a.y) * t, a.z + (sign * b.z - a.z) * t,
            ).normalized()
        }
    }
}
