package dev.avijit.orbit.render

import dev.avijit.orbit.tracking.Quat

enum class AnchorMode { PINNED, FOLLOW }

object ViewPose {
    /**
     * World → eye view matrix (column-major, 16 floats). PINNED applies the inverse head rotation
     * so world content stays put; FOLLOW ignores the head so content moves with it.
     */
    fun viewMatrix(head: Quat, mode: AnchorMode): FloatArray =
        if (mode == AnchorMode.FOLLOW) rotationMatrix(Quat.IDENTITY) else rotationMatrix(head.conjugate())

    fun rotationMatrix(q0: Quat): FloatArray {
        val q = q0.normalized()
        val (w, x, y, z) = q
        return floatArrayOf(
            1 - 2 * (y * y + z * z), 2 * (x * y + w * z), 2 * (x * z - w * y), 0f,
            2 * (x * y - w * z), 1 - 2 * (x * x + z * z), 2 * (y * z + w * x), 0f,
            2 * (x * z + w * y), 2 * (y * z - w * x), 1 - 2 * (x * x + y * y), 0f,
            0f, 0f, 0f, 1f,
        )
    }
}

/** On-device correction for glasses whose IMU axes differ from Orbit's assumption. */
data class AxisFix(
    val invertYaw: Boolean = false,
    val invertPitch: Boolean = false,
    val invertRoll: Boolean = false,
) {
    fun apply(q: Quat): Quat {
        if (!invertYaw && !invertPitch && !invertRoll) return q
        val (yaw, pitch, roll) = q.yawPitchRollDeg().let { Triple(it[0], it[1], it[2]) }
        return fromYawPitchRollDeg(
            if (invertYaw) -yaw else yaw,
            if (invertPitch) -pitch else pitch,
            if (invertRoll) -roll else roll,
        )
    }

    companion object {
        /** Inverse of [Quat.yawPitchRollDeg]: yaw right, pitch up, roll right (degrees). */
        fun fromYawPitchRollDeg(yaw: Float, pitch: Float, roll: Float): Quat {
            val ry = Quat.fromAxisAngle(0f, 1f, 0f, Math.toRadians(-yaw.toDouble()).toFloat())
            val rx = Quat.fromAxisAngle(1f, 0f, 0f, Math.toRadians(pitch.toDouble()).toFloat())
            val rz = Quat.fromAxisAngle(0f, 0f, 1f, Math.toRadians(-roll.toDouble()).toFloat())
            return (ry * rx * rz).normalized()
        }
    }
}
