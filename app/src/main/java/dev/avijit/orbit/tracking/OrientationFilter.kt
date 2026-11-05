package dev.avijit.orbit.tracking

import dev.avijit.orbit.glasses.ImuSample
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Madgwick AHRS on VITURE raw IMU, with automatic gyro-bias calibration while still,
 * recentering and short-horizon prediction.
 *
 * Internally works in the sensor frame (assumed: z up at rest, x right, y forward). Output is
 * converted to Orbit's world frame (x right, y up, −z forward) via [SENSOR_TO_WORLD] — if the
 * glasses' axes differ, the phone UI exposes per-axis inversions (see MainActivity).
 */
class OrientationFilter(
    private val beta: Float = 0.04f,
    @Volatile var useMag: Boolean = false,
) {
    // Sensor orientation relative to an arbitrary earth frame (rotates sensor → earth).
    private var q = Quat.IDENTITY
    // Recenter reference in the world frame (yaw · pitch, never roll).
    private var origin = Quat.IDENTITY
    private var lastNs = 0L
    private var initialized = false

    private val bias = FloatArray(3)
    private val stillSum = FloatArray(3)
    private var stillCount = 0
    private var stillSinceNs = 0L
    private val lastGyro = FloatArray(3)

    @Volatile
    var isCalibrated = false
        private set

    @Synchronized
    fun update(s: ImuSample) {
        if (!initialized) {
            q = fromGravity(s.accel)
            origin = toWorld(yawOnly(q))
            lastNs = s.timestampNs
            initialized = true
            return
        }
        val dt = ((s.timestampNs - lastNs) / 1e9f)
        lastNs = s.timestampNs
        if (dt <= 0f || dt > MAX_DT_S) return

        trackStillness(s, dt)
        val gx = s.gyro[0] - bias[0]
        val gy = s.gyro[1] - bias[1]
        val gz = s.gyro[2] - bias[2]
        lastGyro[0] = gx; lastGyro[1] = gy; lastGyro[2] = gz

        val mag = s.mag
        q = if (useMag && mag != null && (mag[0] != 0f || mag[1] != 0f || mag[2] != 0f)) {
            madgwickMarg(beta, q, gx, gy, gz, s.accel, mag, dt)
        } else {
            madgwickImu(beta, q, gx, gy, gz, s.accel, dt)
        }
    }

    /** Head orientation in world frame, relative to the last recenter. */
    @Synchronized
    fun orientation(): Quat = origin.conjugate() * toWorld(q)

    /** Orientation extrapolated [aheadNs] into the future using the current angular rate. */
    @Synchronized
    fun predicted(aheadNs: Long): Quat {
        val dt = aheadNs / 1e9f
        val wx = lastGyro[0]; val wy = lastGyro[1]; val wz = lastGyro[2]
        val rate = sqrt(wx * wx + wy * wy + wz * wz)
        if (rate < 1e-6f) return orientation()
        val step = Quat.fromAxisAngle(wx, wy, wz, rate * dt)
        return origin.conjugate() * toWorld((q * step).normalized())
    }

    /**
     * Puts the content straight ahead of where the head points now (heading and pitch).
     * Roll is not captured, so the content stays level with the real horizon.
     */
    @Synchronized
    fun recenter() {
        val f = toWorld(q).rotate(0f, 0f, -1f)
        val yaw = atan2(f[0], -f[2])
        val pitch = asin(f[1].coerceIn(-1f, 1f))
        origin = (Quat.fromAxisAngle(0f, 1f, 0f, -yaw) * Quat.fromAxisAngle(1f, 0f, 0f, pitch)).normalized()
    }

    private fun trackStillness(s: ImuSample, dt: Float) {
        val gNorm = norm(s.gyro[0], s.gyro[1], s.gyro[2])
        val aNorm = norm(s.accel[0], s.accel[1], s.accel[2])
        val still = gNorm < STILL_GYRO && abs(aNorm - GRAVITY) < STILL_ACCEL
        if (!still) {
            stillCount = 0
            stillSinceNs = 0
            stillSum.fill(0f)
            return
        }
        if (stillCount == 0) stillSinceNs = s.timestampNs
        stillCount++
        for (i in 0..2) stillSum[i] += s.gyro[i]
        val stillNs = s.timestampNs - stillSinceNs
        if (stillNs < STILL_WINDOW_NS) return
        if (!isCalibrated) {
            for (i in 0..2) bias[i] = stillSum[i] / stillCount
            isCalibrated = true
        } else {
            for (i in 0..2) bias[i] += BIAS_ALPHA * (s.gyro[i] - bias[i])
        }
    }

    private companion object {
        const val GRAVITY = 9.81f
        const val STILL_GYRO = 0.02f
        const val STILL_ACCEL = 0.3f
        const val STILL_WINDOW_NS = 1_500_000_000L
        const val BIAS_ALPHA = 0.001f
        const val MAX_DT_S = 0.1f

        /** Twist of [q] about the earth vertical (sensor-frame +z): its heading component. */
        fun yawOnly(q: Quat): Quat = Quat(q.w, 0f, 0f, q.z).normalized()

        fun norm(a: Float, b: Float, c: Float) = sqrt(a * a + b * b + c * c)

        /**
         * VITURE Pro 2 sensor (x left, y back, z up) → world (x right, y up, −z forward).
         * Confirmed on-device: the earlier x-right/y-forward guess had pitch and roll inverted.
         */
        fun toWorld(s: Quat): Quat = Quat(s.w, -s.x, s.z, s.y)

        /** Initial attitude: rotate measured gravity onto +z (yaw arbitrary, removed by origin). */
        fun fromGravity(a: FloatArray): Quat {
            val n = norm(a[0], a[1], a[2])
            if (n < 1e-3f) return Quat.IDENTITY
            val ax = a[0] / n; val ay = a[1] / n; val az = a[2] / n
            // Rotation taking (ax, ay, az) to (0, 0, 1): axis = a × z, angle = acos(az).
            val axisX = ay
            val axisY = -ax
            val angle = kotlin.math.acos(az.coerceIn(-1f, 1f))
            return Quat.fromAxisAngle(axisX, axisY, 0f, angle).normalized()
        }

        fun madgwickImu(beta: Float, q: Quat, gx: Float, gy: Float, gz: Float, acc: FloatArray, dt: Float): Quat {
            var q0 = q.w; var q1 = q.x; var q2 = q.y; var q3 = q.z
            var qDot1 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz)
            var qDot2 = 0.5f * (q0 * gx + q2 * gz - q3 * gy)
            var qDot3 = 0.5f * (q0 * gy - q1 * gz + q3 * gx)
            var qDot4 = 0.5f * (q0 * gz + q1 * gy - q2 * gx)
            val an = norm(acc[0], acc[1], acc[2])
            if (an > 1e-3f) {
                val ax = acc[0] / an; val ay = acc[1] / an; val az = acc[2] / an
                val _2q0 = 2f * q0; val _2q1 = 2f * q1; val _2q2 = 2f * q2; val _2q3 = 2f * q3
                val _4q0 = 4f * q0; val _4q1 = 4f * q1; val _4q2 = 4f * q2
                val _8q1 = 8f * q1; val _8q2 = 8f * q2
                val q0q0 = q0 * q0; val q1q1 = q1 * q1; val q2q2 = q2 * q2; val q3q3 = q3 * q3
                var s0 = _4q0 * q2q2 + _2q2 * ax + _4q0 * q1q1 - _2q1 * ay
                var s1 = _4q1 * q3q3 - _2q3 * ax + 4f * q0q0 * q1 - _2q0 * ay - _4q1 + _8q1 * q1q1 + _8q1 * q2q2 + _4q1 * az
                var s2 = 4f * q0q0 * q2 + _2q0 * ax + _4q2 * q3q3 - _2q3 * ay - _4q2 + _8q2 * q1q1 + _8q2 * q2q2 + _4q2 * az
                var s3 = 4f * q1q1 * q3 - _2q1 * ax + 4f * q2q2 * q3 - _2q2 * ay
                val sn = sqrt(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3)
                if (sn > 0f) {
                    s0 /= sn; s1 /= sn; s2 /= sn; s3 /= sn
                    qDot1 -= beta * s0
                    qDot2 -= beta * s1
                    qDot3 -= beta * s2
                    qDot4 -= beta * s3
                }
            }
            q0 += qDot1 * dt; q1 += qDot2 * dt; q2 += qDot3 * dt; q3 += qDot4 * dt
            return Quat(q0, q1, q2, q3).normalized()
        }

        fun madgwickMarg(beta: Float, q: Quat, gx: Float, gy: Float, gz: Float, acc: FloatArray, magIn: FloatArray, dt: Float): Quat {
            val an = norm(acc[0], acc[1], acc[2])
            val mn = norm(magIn[0], magIn[1], magIn[2])
            if (an < 1e-3f || mn < 1e-6f) return madgwickImu(beta, q, gx, gy, gz, acc, dt)
            var q0 = q.w; var q1 = q.x; var q2 = q.y; var q3 = q.z
            val ax = acc[0] / an; val ay = acc[1] / an; val az = acc[2] / an
            val mx = magIn[0] / mn; val my = magIn[1] / mn; val mz = magIn[2] / mn
            var qDot1 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz)
            var qDot2 = 0.5f * (q0 * gx + q2 * gz - q3 * gy)
            var qDot3 = 0.5f * (q0 * gy - q1 * gz + q3 * gx)
            var qDot4 = 0.5f * (q0 * gz + q1 * gy - q2 * gx)
            val _2q0mx = 2f * q0 * mx; val _2q0my = 2f * q0 * my; val _2q0mz = 2f * q0 * mz; val _2q1mx = 2f * q1 * mx
            val _2q0 = 2f * q0; val _2q1 = 2f * q1; val _2q2 = 2f * q2; val _2q3 = 2f * q3
            val _2q0q2 = 2f * q0 * q2; val _2q2q3 = 2f * q2 * q3
            val q0q0 = q0 * q0; val q0q1 = q0 * q1; val q0q2 = q0 * q2; val q0q3 = q0 * q3
            val q1q1 = q1 * q1; val q1q2 = q1 * q2; val q1q3 = q1 * q3
            val q2q2 = q2 * q2; val q2q3 = q2 * q3; val q3q3 = q3 * q3
            val hx = mx * q0q0 - _2q0my * q3 + _2q0mz * q2 + mx * q1q1 + _2q1 * my * q2 + _2q1 * mz * q3 - mx * q2q2 - mx * q3q3
            val hy = _2q0mx * q3 + my * q0q0 - _2q0mz * q1 + _2q1mx * q2 - my * q1q1 + my * q2q2 + _2q2 * mz * q3 - my * q3q3
            val _2bx = sqrt(hx * hx + hy * hy)
            val _2bz = -_2q0mx * q2 + _2q0my * q1 + mz * q0q0 + _2q1mx * q3 - mz * q1q1 + _2q2 * my * q3 - mz * q2q2 + mz * q3q3
            val _4bx = 2f * _2bx; val _4bz = 2f * _2bz
            var s0 = -_2q2 * (2f * q1q3 - _2q0q2 - ax) + _2q1 * (2f * q0q1 + _2q2q3 - ay) - _2bz * q2 * (_2bx * (0.5f - q2q2 - q3q3) + _2bz * (q1q3 - q0q2) - mx) + (-_2bx * q3 + _2bz * q1) * (_2bx * (q1q2 - q0q3) + _2bz * (q0q1 + q2q3) - my) + _2bx * q2 * (_2bx * (q0q2 + q1q3) + _2bz * (0.5f - q1q1 - q2q2) - mz)
            var s1 = _2q3 * (2f * q1q3 - _2q0q2 - ax) + _2q0 * (2f * q0q1 + _2q2q3 - ay) - 4f * q1 * (1 - 2f * q1q1 - 2f * q2q2 - az) + _2bz * q3 * (_2bx * (0.5f - q2q2 - q3q3) + _2bz * (q1q3 - q0q2) - mx) + (_2bx * q2 + _2bz * q0) * (_2bx * (q1q2 - q0q3) + _2bz * (q0q1 + q2q3) - my) + (_2bx * q3 - _4bz * q1) * (_2bx * (q0q2 + q1q3) + _2bz * (0.5f - q1q1 - q2q2) - mz)
            var s2 = -_2q0 * (2f * q1q3 - _2q0q2 - ax) + _2q3 * (2f * q0q1 + _2q2q3 - ay) - 4f * q2 * (1 - 2f * q1q1 - 2f * q2q2 - az) + (-_4bx * q2 - _2bz * q0) * (_2bx * (0.5f - q2q2 - q3q3) + _2bz * (q1q3 - q0q2) - mx) + (_2bx * q1 + _2bz * q3) * (_2bx * (q1q2 - q0q3) + _2bz * (q0q1 + q2q3) - my) + (_2bx * q0 - _4bz * q2) * (_2bx * (q0q2 + q1q3) + _2bz * (0.5f - q1q1 - q2q2) - mz)
            var s3 = _2q1 * (2f * q1q3 - _2q0q2 - ax) + _2q2 * (2f * q0q1 + _2q2q3 - ay) + (-_4bx * q3 + _2bz * q1) * (_2bx * (0.5f - q2q2 - q3q3) + _2bz * (q1q3 - q0q2) - mx) + (-_2bx * q0 + _2bz * q2) * (_2bx * (q1q2 - q0q3) + _2bz * (q0q1 + q2q3) - my) + _2bx * q1 * (_2bx * (q0q2 + q1q3) + _2bz * (0.5f - q1q1 - q2q2) - mz)
            val sn = sqrt(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3)
            if (sn > 0f) {
                s0 /= sn; s1 /= sn; s2 /= sn; s3 /= sn
                qDot1 -= beta * s0
                qDot2 -= beta * s1
                qDot3 -= beta * s2
                qDot4 -= beta * s3
            }
            q0 += qDot1 * dt; q1 += qDot2 * dt; q2 += qDot3 * dt; q3 += qDot4 * dt
            return Quat(q0, q1, q2, q3).normalized()
        }
    }

}
