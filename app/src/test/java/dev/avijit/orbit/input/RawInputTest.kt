package dev.avijit.orbit.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RawInputTest {
    private fun ev(type: Int, code: Int, value: Int): ByteArray =
        ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putLong(0).putLong(0)
            .putShort(type.toShort()).putShort(code.toShort()).putInt(value).array()

    @Test fun parsesInputEventStruct() {
        val e = EvdevParser.parse(ev(2, 1, -5), 0)
        assertEquals(2, e.type); assertEquals(1, e.code); assertEquals(-5, e.value)
    }

    @Test fun mouseFrame_accumulatesUntilSyn() {
        val m = MouseFrame()
        assertEquals(null, m.feed(2, 0, 3))
        assertEquals(null, m.feed(2, 1, -4))
        assertEquals(null, m.feed(1, 0x110, 1))   // BTN_LEFT down
        val f = m.feed(0, 0, 0)!!
        assertEquals(3, f.dx); assertEquals(-4, f.dy); assertEquals(1, f.buttons); assertEquals(0, f.wheel)
        val g = m.feed(2, 8, 1).let { m.feed(0, 0, 0) }!!   // wheel up
        assertEquals(0, g.dx); assertEquals(1, g.wheel); assertEquals(1, g.buttons)
    }

    @Test fun linuxKeys_mapCommonKeys() {
        assertEquals(KeyEvent.KEYCODE_A, LinuxKeys.toAndroid(30))
        assertEquals(KeyEvent.KEYCODE_ENTER, LinuxKeys.toAndroid(28))
        assertEquals(KeyEvent.KEYCODE_DEL, LinuxKeys.toAndroid(14))
        assertEquals(KeyEvent.KEYCODE_1, LinuxKeys.toAndroid(2))
        assertEquals(KeyEvent.KEYCODE_0, LinuxKeys.toAndroid(11))
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, LinuxKeys.toAndroid(103))
        assertEquals(KeyEvent.KEYCODE_SPACE, LinuxKeys.toAndroid(57))
        assertEquals(KeyEvent.KEYCODE_UNKNOWN, LinuxKeys.toAndroid(9999))
    }

    @Test fun metaTracker_shiftAndCtrl() {
        val m = MetaTracker()
        m.onKey(42, 1)  // left shift down
        assertTrue(m.meta and KeyEvent.META_SHIFT_ON != 0)
        m.onKey(29, 1)  // left ctrl down
        assertTrue(m.meta and KeyEvent.META_CTRL_ON != 0)
        m.onKey(42, 0)
        assertEquals(0, m.meta and KeyEvent.META_SHIFT_ON)
        m.onKey(58, 1); m.onKey(58, 0)  // caps lock toggles on release-agnostic press
        assertTrue(m.meta and KeyEvent.META_CAPS_LOCK_ON != 0)
    }

    @Test fun linuxKeys_reverseMapGivesScanCodes() {
        assertEquals(30, LinuxKeys.toLinux(KeyEvent.KEYCODE_A))
        assertEquals(28, LinuxKeys.toLinux(KeyEvent.KEYCODE_ENTER))
        assertEquals(57, LinuxKeys.toLinux(KeyEvent.KEYCODE_SPACE))
        assertEquals(0, LinuxKeys.toLinux(KeyEvent.KEYCODE_UNKNOWN))
    }
}
