package dev.avijit.orbit.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sqrt

class CursorRayTest {
    private val p = LayoutParams()
    private fun pose(slot: Int, o: SlotOffset = SlotOffset()) = ScreenLayout.pose(slot, p, o)
    private fun norm(v: FloatArray): FloatArray { val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); return floatArrayOf(v[0] / n, v[1] / n, v[2] / n) }

    @Test fun straightAheadHitsCentreOfSlot0() {
        val hit = hitTest(floatArrayOf(0f, 0f, -1f), mapOf(0 to pose(0), 1 to pose(1)))!!
        assertEquals(0, hit.slot)
        assertEquals(0.5f, hit.u, 1e-3f)
        assertEquals(0.5f, hit.v, 1e-3f)
    }

    @Test fun rayToTopLeftCornerGivesUv00() {
        val corner = pose(0).pointAt(0.001f, 0.001f)
        val hit = hitTest(norm(corner), mapOf(0 to pose(0)))!!
        assertEquals(0f, hit.u, 0.01f)
        assertEquals(0f, hit.v, 0.01f)
    }

    @Test fun missReturnsNull() = assertNull(hitTest(floatArrayOf(0f, 1f, 0f), mapOf(0 to pose(0))))

    @Test fun behindReturnsNull() = assertNull(hitTest(floatArrayOf(0f, 0f, 1f), mapOf(0 to pose(0))))

    @Test fun nearerScreenWins() {
        val far = pose(0)
        val near = pose(1, SlotOffset(yawDeg = -62.14f, distanceM = -0.5f))
        assertEquals(1, hitTest(floatArrayOf(0f, 0f, -1f), mapOf(0 to far, 1 to near))!!.slot)
    }

    @Test fun aimAtPixel_roundTrips_forMovedScaledPushedPose() {
        val moved = pose(1, SlotOffset(yawDeg = 7f, pitchDeg = 12f, distanceM = 0.9f, scale = 1.4f))
        val target = moved.pointAt(0.25f, 0.75f)
        val hit = hitTest(norm(target), mapOf(1 to moved))!!
        assertEquals(0.25f, hit.u, 1e-3f)
        assertEquals(0.75f, hit.v, 1e-3f)
    }

    @Test fun cursorRayStartsAheadAndClamps() {
        val c = CursorRay()
        assertNotNull(hitTest(c.direction(), mapOf(0 to pose(0))))
        c.moveByPx(0f, -100000f)
        assertEquals(80f, c.pitchDeg, 1e-3f)
    }

    @Test fun projectOnto_givesUvEvenOutsideTheScreen() {
        val p0 = pose(0)
        val inside = projectOnto(floatArrayOf(0f, 0f, -1f), p0)!!
        assertEquals(0.5f, inside.first, 1e-3f)
        // Aim past the right edge: u > 1 (drags keep tracking beyond the edge, clamped by the caller).
        val outside = projectOnto(norm(floatArrayOf(1.2f, 0f, -1.5f)), p0)!!
        assertEquals(true, outside.first > 1f)
        assertNull(projectOnto(floatArrayOf(0f, 0f, 1f), p0))
    }
}
