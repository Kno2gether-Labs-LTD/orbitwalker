package dev.avijit.orbit.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class PoseStabilizerTest {
    private fun yaw(deg: Double) = Quat.fromAxisAngle(0f, 1f, 0f, (deg * PI / 180).toFloat())
    private fun angleDeg(a: Quat, b: Quat): Double = PoseStabilizer.angleDeg(a, b)

    @Test fun tinyJitter_isHeldStill() {
        val s = PoseStabilizer()
        val first = s.filter(yaw(10.0))
        val out = s.filter(yaw(10.02))
        assertEquals(0.0, angleDeg(first, out), 1e-4)
    }

    @Test fun realMovement_passesStraightThrough() {
        val s = PoseStabilizer()
        s.filter(yaw(10.0))
        val out = s.filter(yaw(12.0))
        // Soft deadband: trails the real pose by at most the deadband (a fraction of a pixel).
        assertTrue(angleDeg(out, yaw(12.0)) <= PoseStabilizer.DEADBAND_DEG + 1e-3)
    }

    @Test fun slowDrift_isFollowedWithoutLag() {
        val s = PoseStabilizer()
        s.filter(yaw(0.0))
        var out = yaw(0.0)
        for (i in 1..100) out = s.filter(yaw(i * 0.01)) // 1° over 100 frames
        assertTrue(angleDeg(out, yaw(1.0)) <= PoseStabilizer.DEADBAND_DEG + 1e-3)
    }

    @Test fun slowTurn_movesSmoothlyWithoutJumps() {
        // Soft deadband: each frame the output moves by the excess only, never jumping a whole deadband.
        val s = PoseStabilizer()
        var prev = s.filter(yaw(0.0))
        for (i in 1..60) {
            val out = s.filter(yaw(i * 0.01))
            assertTrue(angleDeg(prev, out) <= 0.0101)
            prev = out
        }
    }
}
