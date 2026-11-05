package dev.avijit.orbit.layout

import kotlin.math.cos
import kotlin.math.sin

/** The shared cursor: a direction from the user's head origin, steered by the trackpad. */
class CursorRay {
    @Volatile var yawDeg = 0f
        private set
    @Volatile var pitchDeg = 0f
        private set

    fun moveByPx(dx: Float, dy: Float, degPerPx: Float = DEG_PER_PX) {
        yawDeg = (yawDeg + dx * degPerPx).coerceIn(-170f, 170f)
        pitchDeg = (pitchDeg - dy * degPerPx).coerceIn(-80f, 80f)
    }

    fun aimAt(yaw: Float, pitch: Float) {
        yawDeg = yaw.coerceIn(-170f, 170f)
        pitchDeg = pitch.coerceIn(-80f, 80f)
    }

    fun direction(): FloatArray {
        val y = Math.toRadians(yawDeg.toDouble()); val p = Math.toRadians(pitchDeg.toDouble())
        return floatArrayOf((sin(y) * cos(p)).toFloat(), sin(p).toFloat(), (-cos(y) * cos(p)).toFloat())
    }

    companion object {
        const val DEG_PER_PX = 0.05f
    }
}

class Hit(val slot: Int, val u: Float, val v: Float, val distance: Float)

/** Where a ray meets [pose]'s plane as (u, v), even outside the screen; null if it points away. */
fun projectOnto(dir: FloatArray, pose: ScreenPose): Pair<Float, Float>? {
    val (r, up, f) = pose.basis()
    val denom = dir[0] * f[0] + dir[1] * f[1] + dir[2] * f[2]
    if (denom <= 1e-4f) return null
    val t = pose.distanceM / denom
    val c = pose.centre()
    val lx = dir[0] * t - c[0]; val ly = dir[1] * t - c[1]; val lz = dir[2] * t - c[2]
    return ((lx * r[0] + ly * r[1] + lz * r[2]) / pose.widthM + 0.5f) to (0.5f - (lx * up[0] + ly * up[1] + lz * up[2]) / pose.heightM)
}

/** Nearest screen hit by a ray from the origin along [dir] (unit vector), or null. */
fun hitTest(dir: FloatArray, screens: Map<Int, ScreenPose>): Hit? {
    var best: Hit? = null
    for ((slot, pose) in screens) {
        val (r, up, f) = pose.basis()
        val denom = dir[0] * f[0] + dir[1] * f[1] + dir[2] * f[2]
        if (denom <= 1e-4f) continue
        val t = pose.distanceM / denom
        val c = pose.centre()
        val lx = dir[0] * t - c[0]; val ly = dir[1] * t - c[1]; val lz = dir[2] * t - c[2]
        val u = (lx * r[0] + ly * r[1] + lz * r[2]) / pose.widthM + 0.5f
        val v = 0.5f - (lx * up[0] + ly * up[1] + lz * up[2]) / pose.heightM
        if (u < 0f || u > 1f || v < 0f || v > 1f) continue
        if (best == null || t < best.distance) best = Hit(slot, u, v, t)
    }
    return best
}
