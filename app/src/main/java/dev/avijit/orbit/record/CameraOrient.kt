package dev.avijit.orbit.record

import kotlin.math.atan

/** Turning the camera's sensor picture upright, and working out what the lens sees. */
object CameraOrient {
    /** Degrees (clockwise) to turn the sensor picture so it's upright for how the phone is held. */
    fun rotation(sensorDeg: Int, displayDeg: Int, front: Boolean): Int =
        if (front) (sensorDeg + displayDeg) % 360 else (sensorDeg - displayDeg + 360) % 360

    /** Upright picture coordinate (u right, v up) → coordinate in the sensor picture. */
    fun sensorUv(u: Float, v: Float, rotation: Int): FloatArray = when (rotation) {
        90 -> floatArrayOf(1f - v, u)
        180 -> floatArrayOf(1f - u, 1f - v)
        270 -> floatArrayOf(v, 1f - u)
        else -> floatArrayOf(u, v)
    }

    /** The mixed reality video follows the phone: portrait on a portrait phone (Shorts, Reels). */
    fun outputSize(rotation: Int): Pair<Int, Int> = if (rotation % 180 == 90) 1080 to 1920 else 1920 to 1080

    /**
     * Vertical field of view of a 16:9 camera stream from a [sensorW]×[sensorH] mm sensor with a
     * [focalMm] lens, once turned upright and cropped to [outAspect] (width / height).
     */
    fun verticalFovDeg(focalMm: Float, sensorW: Float, sensorH: Float, rotation: Int, outAspect: Float): Float {
        val stream = 16f / 9f
        val (sw, sh) = if (sensorW / sensorH > stream) (sensorH * stream) to sensorH else sensorW to (sensorW / stream)
        val (iw, ih) = if (rotation % 180 == 90) sh to sw else sw to sh
        val visibleH = if (iw / ih > outAspect + 1e-4f) ih else iw / outAspect
        return Math.toDegrees(2.0 * atan(visibleH / 2.0 / focalMm)).toFloat()
    }
}
