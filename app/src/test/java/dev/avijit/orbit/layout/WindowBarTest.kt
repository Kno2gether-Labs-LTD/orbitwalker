package dev.avijit.orbit.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowBarTest {
    private val screen = ScreenPose(10f, -2f, 1.5f, 1.2f, 0.675f)

    @Test fun pose_sitsJustAboveTheScreen_sameWidthAndYaw() {
        val b = WindowBar.pose(screen)
        assertEquals(screen.yawDeg, b.yawDeg, 1e-4f)
        assertEquals(screen.widthM, b.widthM, 1e-4f)
        assertTrue(b.pitchDeg > screen.pitchDeg + 12f) // above the top edge (half height ≈ 12.7°)
        assertTrue(b.pitchDeg < screen.pitchDeg + 16f)
        assertEquals(WindowBar.ASPECT, b.widthM / b.heightM, 1e-3f)
    }

    @Test fun buttons_onTheRight_handleOnTheLeft() {
        assertEquals(WindowBar.Button.MOVE, WindowBar.buttonAt(0.05f))
        assertEquals(WindowBar.Button.MOVE, WindowBar.buttonAt(0.5f))
        assertEquals(WindowBar.Button.CLOSE, WindowBar.buttonAt(0.999f))
        val order = WindowBar.BUTTONS
        // Every button is reachable, in order, left to right.
        val seen = (0 until 1000).map { WindowBar.buttonAt(it / 1000f) }.distinct()
        assertEquals(listOf(WindowBar.Button.MOVE) + order, seen)
    }

    @Test fun apply_buttonsChangeTheOffset() {
        val o = SlotOffset()
        assertTrue(WindowBar.apply(WindowBar.Button.BIGGER, o).scale > 1f)
        assertTrue(WindowBar.apply(WindowBar.Button.SMALLER, o).scale < 1f)
        assertTrue(WindowBar.apply(WindowBar.Button.FURTHER, o).distanceM > 0f)
        assertTrue(WindowBar.apply(WindowBar.Button.CLOSER, o).distanceM < 0f)
        assertEquals(SlotOffset(), WindowBar.apply(WindowBar.Button.RESET, SlotOffset(5f, 3f, 0.2f, 1.3f)))
    }

    @Test fun drag_movesByTheCursorDelta() {
        val start = SlotOffset(yawDeg = 2f, pitchDeg = 1f)
        val o = WindowBar.dragged(start, startYaw = 0f, startPitch = 0f, yaw = 7f, pitch = -3f)
        assertEquals(9f, o.yawDeg, 1e-4f)
        assertEquals(-2f, o.pitchDeg, 1e-4f)
    }
}
