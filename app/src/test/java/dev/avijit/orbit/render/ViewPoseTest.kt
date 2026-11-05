package dev.avijit.orbit.render

import dev.avijit.orbit.tracking.Quat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2

class ViewPoseTest {
    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    private fun rad(deg: Float) = Math.toRadians(deg.toDouble()).toFloat()

    /** Applies a column-major 4x4 matrix to point (x, y, z, 1). */
    private fun FloatArray.apply(x: Float, y: Float, z: Float) = floatArrayOf(
        this[0] * x + this[4] * y + this[8] * z + this[12],
        this[1] * x + this[5] * y + this[9] * z + this[13],
        this[2] * x + this[6] * y + this[10] * z + this[14],
    )

    @Test fun follow_isIdentity() {
        val head = Quat.fromAxisAngle(0f, 1f, 0f, rad(40f))
        assertArrayEquals(identity, ViewPose.viewMatrix(head, AnchorMode.FOLLOW), 1e-6f)
    }

    @Test fun pinned_identityHead_isIdentity() =
        assertArrayEquals(identity, ViewPose.viewMatrix(Quat.IDENTITY, AnchorMode.PINNED), 1e-6f)

    @Test fun pinned_yawRight30_movesWorldLeft() {
        val head = Quat.fromAxisAngle(0f, 1f, 0f, rad(-30f)) // turn right
        val p = ViewPose.viewMatrix(head, AnchorMode.PINNED).apply(0f, 0f, -1.5f)
        assertTrue(p[0] < 0f)
        assertEquals(30.0, Math.toDegrees(atan2(-p[0], -p[2]).toDouble()), 0.5)
    }

    @Test fun pinned_pitchUp10_movesWorldDown() {
        val head = Quat.fromAxisAngle(1f, 0f, 0f, rad(10f)) // look up
        val p = ViewPose.viewMatrix(head, AnchorMode.PINNED).apply(0f, 0f, -1.5f)
        assertTrue(p[1] < 0f)
    }

    @Test fun yawPitchRoll_roundTrip() {
        val q = AxisFix.fromYawPitchRollDeg(10f, 20f, 5f)
        assertArrayEquals(floatArrayOf(10f, 20f, 5f), q.yawPitchRollDeg(), 0.01f)
    }

    @Test fun axisFix_invertsOnlySelectedAxes() {
        val q = AxisFix.fromYawPitchRollDeg(10f, 20f, 5f)
        val fixed = AxisFix(invertYaw = true, invertPitch = false, invertRoll = true).apply(q)
        assertArrayEquals(floatArrayOf(-10f, 20f, -5f), fixed.yawPitchRollDeg(), 0.01f)
    }

    @Test fun axisFix_noInversion_isUnchanged() {
        val q = AxisFix.fromYawPitchRollDeg(-35f, 12f, 3f)
        assertEquals(q, AxisFix().apply(q))
    }
}
