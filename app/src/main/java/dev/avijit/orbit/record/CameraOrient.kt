package dev.avijit.orbit.record

import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/** How the phone stands on its tripod, which decides the mixed reality video's shape. */
enum class PhoneStand(val label: String) {
    AUTO("Auto"),
    UPRIGHT("Upright (9:16)"),
    SIDEWAYS("On its side (16:9)"),
}

/** Turning the camera's sensor picture upright, and working out what the lens sees. */
object CameraOrient {
    /**
     * How far the phone is turned from upright, clockwise in degrees (0, 90, 180, 270), from the
     * accelerometer ([ax], [ay] in the device's natural frame). Null when it lies too flat to tell.
     * Same convention as OrientationEventListener and the camera's sensor orientation.
     */
    fun tiltDeg(ax: Float, ay: Float): Int? {
        if (hypot(ax, ay) < 3f) return null
        val deg = 90 - Math.toDegrees(atan2(ay.toDouble(), -ax.toDouble())).roundToInt()
        return (((deg / 90.0).roundToInt() * 90) % 360 + 360) % 360
    }

    /**
     * Turn of the phone (clockwise from upright) to film with: [tilt] when [stand] is Auto; otherwise
     * the chosen shape, turned the way gravity says (so a phone on its side either way is upright).
     */
    fun deviceDeg(stand: PhoneStand, tilt: Int?): Int = when (stand) {
        PhoneStand.AUTO -> tilt ?: 0
        PhoneStand.UPRIGHT -> if (tilt == 180) 180 else 0
        PhoneStand.SIDEWAYS -> if (tilt == 90) 90 else 270
    }

    /** The display rotation that matches a phone turned [deviceDeg] clockwise. */
    fun displayDegFor(deviceDeg: Int): Int = (360 - deviceDeg) % 360

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
