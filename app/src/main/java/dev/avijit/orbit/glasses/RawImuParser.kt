package dev.avijit.orbit.glasses

/** One IMU sample in SI units. gyro: rad/s, accel: m/s², mag: raw sensor units. */
class ImuSample(
    val gyro: FloatArray,
    val accel: FloatArray,
    val mag: FloatArray?,
    val tempC: Float?,
    val timestampNs: Long,
)

/**
 * Decodes VITURE's raw IMU callback array `[gx,gy,gz, ax,ay,az, mx,my,mz, temp]`.
 * Unit detection mirrors SpaceWalker (SlamPoseProvider.D/F/G): gyro above 32 is deg/s,
 * accel at or below 4 is in g. Once any gyro sample proves deg/s, that unit is latched so slow
 * motion and rest noise are not misread as rad/s.
 */
class RawImuParser {
    @Volatile
    var gyroInDegrees = false
        private set


    fun parse(raw: FloatArray, timestampNs: Long): ImuSample? {
        if (raw.size < 6) return null
        if (!gyroInDegrees && maxAbs(raw, 0) > 32f) gyroInDegrees = true
        val gyroScale = if (gyroInDegrees) DEG_TO_RAD else 1f
        val accelScale = if (maxAbs(raw, 3) <= 4f) STANDARD_GRAVITY else 1f
        return ImuSample(
            gyro = floatArrayOf(raw[0] * gyroScale, raw[1] * gyroScale, raw[2] * gyroScale),
            accel = floatArrayOf(raw[3] * accelScale, raw[4] * accelScale, raw[5] * accelScale),
            mag = if (raw.size >= 9) floatArrayOf(raw[6], raw[7], raw[8]) else null,
            tempC = if (raw.size >= 10) raw[9] else null,
            timestampNs = timestampNs,
        )
    }

    private fun maxAbs(a: FloatArray, from: Int): Float =
        maxOf(kotlin.math.abs(a[from]), kotlin.math.abs(a[from + 1]), kotlin.math.abs(a[from + 2]))

    private companion object {
        const val DEG_TO_RAD = 0.017453292f
        const val STANDARD_GRAVITY = 9.80665f
    }
}
