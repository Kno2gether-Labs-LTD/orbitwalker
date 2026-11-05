package dev.avijit.orbit.tracking

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Unit quaternion. In Orbit's world/GL frame: x right, y up, −z forward.
 * A head orientation quaternion rotates head-local vectors into the world frame.
 */
data class Quat(val w: Float, val x: Float, val y: Float, val z: Float) {

    operator fun times(o: Quat): Quat = Quat(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
    )

    fun conjugate(): Quat = Quat(w, -x, -y, -z)

    fun normalized(): Quat {
        val n = sqrt(w * w + x * x + y * y + z * z)
        return if (n == 0f) IDENTITY else Quat(w / n, x / n, y / n, z / n)
    }

    /** Rotates vector (vx, vy, vz) by this quaternion. */
    fun rotate(vx: Float, vy: Float, vz: Float): FloatArray {
        val r = this * Quat(0f, vx, vy, vz) * conjugate()
        return floatArrayOf(r.x, r.y, r.z)
    }

    /**
     * Head angles in degrees: [yaw, pitch, roll] with yaw > 0 = turned right,
     * pitch > 0 = looking up, roll > 0 = tilted right.
     */
    fun yawPitchRollDeg(): FloatArray {
        val f = rotate(0f, 0f, -1f)
        val r = rotate(1f, 0f, 0f)
        val u = rotate(0f, 1f, 0f)
        val yaw = atan2(f[0], -f[2])
        val pitch = asin(f[1].coerceIn(-1f, 1f))
        val roll = atan2(-r[1], u[1])
        return floatArrayOf(Math.toDegrees(yaw.toDouble()).toFloat(), Math.toDegrees(pitch.toDouble()).toFloat(), Math.toDegrees(roll.toDouble()).toFloat())
    }

    companion object {
        val IDENTITY = Quat(1f, 0f, 0f, 0f)

        fun fromAxisAngle(ax: Float, ay: Float, az: Float, rad: Float): Quat {
            val n = sqrt(ax * ax + ay * ay + az * az)
            if (n == 0f) return IDENTITY
            val s = sin(rad / 2) / n
            return Quat(cos(rad / 2), ax * s, ay * s, az * s)
        }
    }
}
