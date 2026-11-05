package dev.avijit.orbit.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputStatusTest {
    private val base = InputFacts(
        grabbed = "Keys K2 (keyboard); MX Anywhere (mouse)",
        phoneKeyboards = listOf("Keys K2"),
        phoneMice = listOf("MX Anywhere"),
        helperReady = true, locked = false, released = false, hasScreens = true, mirror = false, cinema = false,
        focusedScreen = true, lastKeyAtMs = 9_000L, lastProblem = null, lastProblemAtMs = 0L, nowMs = 10_000L,
    )

    @Test fun bothGrabbed_inGlasses_noReason() {
        val s = inputStatus(base)
        assertEquals(Link.GLASSES, s.keyboard)
        assertEquals("Keys K2", s.keyboardName)
        assertEquals(Link.GLASSES, s.mouse)
        assertNull(s.reason)
        assertEquals("last key 1 s ago", s.lastKey)
    }

    @Test fun keyboardAsleep_notConnected_withHint() {
        val s = inputStatus(base.copy(grabbed = "MX Anywhere (mouse)", phoneKeyboards = emptyList()))
        assertEquals(Link.NONE, s.keyboard)
        assertEquals(Link.GLASSES, s.mouse)
        assertTrue(s.reason!!, s.reason!!.contains("asleep"))
    }

    @Test fun keyboardOnPhoneButNotGrabbed_isReconnecting() {
        val s = inputStatus(base.copy(grabbed = "MX Anywhere (mouse)"))
        assertEquals(Link.PHONE, s.keyboard)
        assertTrue(s.reason!!, s.reason!!.contains("taking over"))
    }

    @Test fun released_bothOnPhone() {
        val s = inputStatus(base.copy(grabbed = "", released = true))
        assertEquals(Link.PHONE, s.keyboard)
        assertEquals(Link.PHONE, s.mouse)
        assertTrue(s.reason!!, s.reason!!.contains("Ctrl+Esc"))
    }

    @Test fun locked_paused() = assertTrue(inputStatus(base.copy(grabbed = "", locked = true)).reason!!.contains("locked"))

    @Test fun helperStopped() = assertTrue(inputStatus(base.copy(grabbed = "", helperReady = false)).reason!!.contains("helper"))

    @Test fun noScreens() = assertTrue(inputStatus(base.copy(grabbed = "", hasScreens = false)).reason!!.contains("Open an app"))

    @Test fun noFocusedScreen_keysGoNowhere() =
        assertTrue(inputStatus(base.copy(focusedScreen = false)).reason!!.contains("Click a screen"))

    @Test fun recentProblem_shown_oldProblemHidden() {
        assertEquals("helper call failed", inputStatus(base.copy(lastProblem = "helper call failed", lastProblemAtMs = 8_000L)).problem)
        assertNull(inputStatus(base.copy(lastProblem = "helper call failed", lastProblemAtMs = 8_000L, nowMs = 60_000L)).problem)
    }

    @Test fun nothingConnected() {
        val s = inputStatus(base.copy(grabbed = "", phoneKeyboards = emptyList(), phoneMice = emptyList()))
        assertEquals(Link.NONE, s.keyboard)
        assertEquals(Link.NONE, s.mouse)
    }

    @Test fun lastKeyMinutes() = assertEquals("last key 2 min ago", inputStatus(base.copy(lastKeyAtMs = 10_000L, nowMs = 130_000L)).lastKey)

    @Test fun neverTyped() = assertEquals("no keys yet", inputStatus(base.copy(lastKeyAtMs = 0L, nowMs = 5_000L)).lastKey)
}
