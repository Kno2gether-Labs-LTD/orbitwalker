package dev.avijit.orbit.layout

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenLayoutTest {
    private val p = LayoutParams()
    private val none = SlotOffset()

    @Test fun centreSlotStraightAhead() {
        val pose = ScreenLayout.pose(0, p, none)
        assertEquals(0f, pose.yawDeg, 1e-4f)
        assertArrayEquals(floatArrayOf(0f, 0f, -1.5f), pose.centre(), 1e-4f)
    }

    @Test fun rightSlotIsRight() {
        val pose = ScreenLayout.pose(1, p, none)
        assertEquals(62.14f, pose.yawDeg, 0.05f)
        assertEquals(true, pose.centre()[0] > 0f)
    }

    @Test fun leftSlotMirrorsRight() {
        assertEquals(-ScreenLayout.pose(1, p, none).yawDeg, ScreenLayout.pose(2, p, none).yawDeg, 1e-4f)
    }

    @Test fun sizeScalesWidth() {
        val pose = ScreenLayout.pose(0, p.copy(sizePct = 200f), none)
        assertEquals(3.2f, pose.widthM, 1e-4f)
        assertEquals(2.0f, pose.heightM, 1e-4f)
    }

    @Test fun offsetsApplyAndClamp() {
        val pose = ScreenLayout.pose(0, p, SlotOffset(yawDeg = 10f, distanceM = 5f, scale = 0.5f))
        assertEquals(10f, pose.yawDeg, 1e-4f)
        assertEquals(3.0f, pose.distanceM, 1e-4f)
        assertEquals(0.8f, pose.widthM, 1e-4f)
    }

    @Test fun heightTiltsPitch() {
        val pose = ScreenLayout.pose(0, p.copy(heightDeg = 10f), none)
        assertEquals(10f, pose.pitchDeg, 1e-4f)
        assertEquals(true, pose.centre()[1] > 0f)
    }

    @Test fun encodeDecodeRoundTrip() {
        val params = LayoutParams(2f, 120f, 8f, -5f)
        val offs = List(ScreenLayout.SLOTS) { if (it == 0) SlotOffset(1f, 2f, 0.3f, 1.2f) else if (it == 7) SlotOffset(-4f, 0f, 0f, 0.7f) else SlotOffset() }
        val (p2, o2) = ScreenLayout.decode(ScreenLayout.encode(params, offs))!!
        assertEquals(params, p2)
        assertEquals(offs, o2)
    }

    @Test fun tenSlots_twoRowsOfFive() {
        assertEquals(10, ScreenLayout.SLOTS)
        assertEquals(-124.28f, ScreenLayout.pose(4, p, none).yawDeg, 0.1f)          // far left
        val above = ScreenLayout.pose(5, p, none)                                      // row 2, centre
        assertEquals(0f, above.yawDeg, 1e-3f)
        assertEquals(36.87f + 4f, above.pitchDeg, 0.1f)                               // screen height + row gap
        assertEquals(ScreenLayout.pose(1, p, none).yawDeg, ScreenLayout.pose(6, p, none).yawDeg, 1e-3f)
    }

    @Test fun decodeV1FourSlots_migratesToTen() {
        val v1 = "v1;2.0;120.0;8.0;-5.0;1.0;2.0;0.3;1.2;0;0;0;1;0;0;0;1;0;0;0;1"
        val (params, offs) = ScreenLayout.decode(v1)!!
        assertEquals(2f, params.distanceM, 1e-4f)
        assertEquals(4f, params.rowGapDeg, 1e-4f)
        assertEquals(10, offs.size)
        assertEquals(SlotOffset(1f, 2f, 0.3f, 1.2f), offs[0])
        assertEquals(SlotOffset(), offs[9])
    }

    @Test fun decodeGarbageIsNull() {
        assertNull(ScreenLayout.decode("hello"))
        assertNull(ScreenLayout.decode(""))
    }

    @Test fun clampOffset_keepsPoseInsideRanges_noDeadZone() {
        val o = ScreenLayout.clampOffset(1, p, SlotOffset(yawDeg = 500f, pitchDeg = -500f, distanceM = 9f, scale = 5f))
        val pose = ScreenLayout.pose(1, p, o)
        assertEquals(150f, pose.yawDeg, 1e-3f)
        assertEquals(-60f, pose.pitchDeg, 1e-3f)
        assertEquals(1.5f, o.distanceM, 1e-3f)   // 1.5 base + 1.5 = 3.0 max
        assertEquals(2f, o.scale, 1e-3f)
        // pulling back by a little moves the screen at once
        val back = ScreenLayout.pose(1, p, o.copy(distanceM = o.distanceM - 0.1f))
        assertEquals(2.9f, back.distanceM, 1e-3f)
    }

    @Test fun pixelPerfectSize_oneAppPixelPerGlassesPixel() {
        // 1920 px across at 45 px/° = 42.67°; at 1.5 m that is 1.172 m wide = 73.2% of 1.6 m.
        assertEquals(73.2f, ScreenLayout.pixelPerfectSizePct(1.5f, 1920, 45f), 0.2f)
        // Farther away needs a bigger screen for the same angular size.
        assertEquals(97.7f, ScreenLayout.pixelPerfectSizePct(2.0f, 1920, 45f), 0.3f)
    }
}
