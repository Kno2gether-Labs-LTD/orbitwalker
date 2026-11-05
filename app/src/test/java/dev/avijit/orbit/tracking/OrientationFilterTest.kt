package dev.avijit.orbit.tracking

import dev.avijit.orbit.glasses.ImuSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos

class OrientationFilterTest {

    private val dtNs = 1_000_000L // 1 kHz
    private var t = 0L

    private fun OrientationFilter.feed(seconds: Double, gx: Float = 0f, gy: Float = 0f, gz: Float = 0f) {
        val n = (seconds * 1000).toInt()
        repeat(n) {
            t += dtNs
            update(ImuSample(floatArrayOf(gx, gy, gz), floatArrayOf(0f, 0f, 9.81f), null, null, t))
        }
    }

    private fun angleDeg(q: Quat): Double {
        val w = abs(q.normalized().w).coerceAtMost(1f)
        return 2 * acos(w.toDouble()) * 180 / PI
    }

    @Test fun stationary_staysIdentity() {
        val f = OrientationFilter(useMag = false)
        f.feed(2.0)
        val ypr = f.orientation().yawPitchRollDeg()
        ypr.forEach { assertEquals(0f, it, 0.5f) }
    }

    @Test fun constantYaw_integrates90deg() {
        val f = OrientationFilter(useMag = false)
        f.feed(1.0, gz = (PI / 2).toFloat())
        assertEquals(90f, abs(f.orientation().yawPitchRollDeg()[0]), 2f)
    }

    @Test fun recenter_resetsToIdentity() {
        val f = OrientationFilter(useMag = false)
        f.feed(1.0, gz = (PI / 2).toFloat())
        f.recenter()
        assertTrue(angleDeg(f.orientation()) < 0.5)
    }

    @Test fun gyroBias_removedAfterStill() {
        val f = OrientationFilter(useMag = false)
        f.feed(2.0, gz = 0.01f)
        assertTrue(f.isCalibrated)
        val before = f.orientation().yawPitchRollDeg()[0]
        f.feed(10.0, gz = 0.01f)
        val after = f.orientation().yawPitchRollDeg()[0]
        assertEquals(0f, after - before, 1f)
    }

    @Test fun prediction_extrapolatesYaw() {
        val f = OrientationFilter(useMag = false)
        f.feed(0.5, gz = (PI / 2).toFloat())
        val now = f.orientation().yawPitchRollDeg()[0]
        val ahead = f.predicted(20_000_000).yawPitchRollDeg()[0]
        assertEquals(1.8f, abs(ahead - now), 0.3f)
    }

    @Test fun recenter_bringsYawAndPitchToZero() {
        val f = OrientationFilter(useMag = false)
        f.feed(0.5, gz = (PI / 2).toFloat())         // turn ~45°
        f.feed(0.2, gx = (PI / 2).toFloat())         // then pitch ~17°
        assertTrue(abs(f.orientation().yawPitchRollDeg()[1]) > 10f)
        f.recenter()
        val ypr = f.orientation().yawPitchRollDeg()
        assertEquals(0f, ypr[0], 0.5f)
        assertEquals(0f, ypr[1], 0.5f)
    }

    @Test fun recenter_keepsRollLevelWithHorizon() {
        val f = OrientationFilter(useMag = false)
        f.feed(0.2, gy = (PI / 2).toFloat())         // tilt head ~17°
        val rollBefore = f.orientation().yawPitchRollDeg()[2]
        f.recenter()
        assertEquals(rollBefore, f.orientation().yawPitchRollDeg()[2], 1f)
        assertTrue(abs(rollBefore) > 10f)
    }

    @Test fun recenter_thenTurning_movesRelativeToNewCenter() {
        val f = OrientationFilter(useMag = false)
        f.feed(0.2, gx = (PI / 2).toFloat())         // look ~17° down
        f.recenter()
        f.feed(0.5, gz = (PI / 2).toFloat())         // then turn
        assertEquals(45f, abs(f.orientation().yawPitchRollDeg()[0]), 3f)
    }

    // Pro 2 axis mapping, confirmed on-device 2026-11-04: with the original mapping, pitch and
    // roll had to be inverted (yaw was right). So a sensor-x rotation is pitch DOWN and a
    // sensor-y rotation is roll in the opposite sense to the original z-up/y-forward mapping.
    @Test fun pro2_sensorX_isPitchDown() {
        val f = OrientationFilter(useMag = false)
        f.feed(0.2, gx = (PI / 2).toFloat())
        assertEquals(-17f, f.orientation().yawPitchRollDeg()[1], 2f)
    }

    @Test fun pro2_sensorY_isRollLeft() {
        val f = OrientationFilter(useMag = false)
        f.feed(0.2, gy = (PI / 2).toFloat())
        assertEquals(-17f, f.orientation().yawPitchRollDeg()[2], 2f)
    }

    @Test fun pro2_sensorZ_yawUnchanged() {
        val f = OrientationFilter(useMag = false)
        f.feed(0.5, gz = (PI / 2).toFloat())
        assertEquals(-45f, f.orientation().yawPitchRollDeg()[0], 2f)
    }
}
