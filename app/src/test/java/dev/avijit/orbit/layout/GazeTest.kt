package dev.avijit.orbit.layout

import dev.avijit.orbit.render.AnchorMode
import dev.avijit.orbit.render.AxisFix
import dev.avijit.orbit.render.ViewPose
import org.junit.Assert.assertEquals
import org.junit.Test

class GazeTest {
    /** Where the cursor ends up in the eye's view: (0, 0, -1) means dead centre. */
    private fun inView(yawDeg: Float, pitchDeg: Float, mode: AnchorMode): FloatArray {
        val head = AxisFix.fromYawPitchRollDeg(yawDeg, pitchDeg, 0f)
        val (y, p) = Gaze.cursorAngles(head, mode)
        val c = CursorRay().apply { aimAt(y, p) }.direction()
        val m = ViewPose.viewMatrix(head, mode)
        return floatArrayOf(
            m[0] * c[0] + m[4] * c[1] + m[8] * c[2],
            m[1] * c[0] + m[5] * c[1] + m[9] * c[2],
            m[2] * c[0] + m[6] * c[1] + m[10] * c[2],
        )
    }

    @Test fun pinned_cursorLandsWhereYouLook() {
        for ((yaw, pitch) in listOf(0f to 0f, 35f to 0f, -60f to 12f, 20f to -25f)) {
            val v = inView(yaw, pitch, AnchorMode.PINNED)
            assertEquals("x for $yaw/$pitch", 0f, v[0], 1e-3f)
            assertEquals("y for $yaw/$pitch", 0f, v[1], 1e-3f)
            assertEquals(-1f, v[2], 1e-3f)
        }
    }

    @Test fun follow_cursorLandsAtTheCentre() {
        val v = inView(40f, 10f, AnchorMode.FOLLOW)
        assertEquals(0f, v[0], 1e-3f); assertEquals(0f, v[1], 1e-3f)
    }
}
