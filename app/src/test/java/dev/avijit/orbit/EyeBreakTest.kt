package dev.avijit.orbit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EyeBreakTest {
    private val min = 60_000L

    @Test fun shortBreak_everyInterval_ofWearing() {
        val e = EyeBreak(intervalMin = 20)
        assertNull(e.tick(0, wearing = true))
        assertNull(e.tick(19 * min, wearing = true))
        assertEquals(EyeBreak.Reminder.LOOK_AWAY, e.tick(20 * min, wearing = true))
        assertNull(e.tick(21 * min, wearing = true))
        assertEquals(EyeBreak.Reminder.LOOK_AWAY, e.tick(40 * min, wearing = true))
    }

    @Test fun takingGlassesOff_forFiveMinutes_resetsTheSession() {
        val e = EyeBreak(intervalMin = 20)
        e.tick(0, true)
        e.tick(15 * min, true)
        e.tick(16 * min, wearing = false)
        assertNull(e.tick(22 * min, wearing = true)) // off for 6 min: new session starts here
        assertNull(e.tick(41 * min, wearing = true))
        assertEquals(EyeBreak.Reminder.LOOK_AWAY, e.tick(42 * min, wearing = true))
    }

    @Test fun longSession_suggestsTakingThemOff_once_thenHourly() {
        val e = EyeBreak(intervalMin = 20)
        var t = 0L
        val got = mutableListOf<Pair<Long, EyeBreak.Reminder>>()
        while (t <= 160 * min) { e.tick(t, true)?.let { got += (t / min) to it }; t += min }
        val longs = got.filter { it.second == EyeBreak.Reminder.TAKE_OFF }.map { it.first }
        assertEquals(listOf(90L, 150L), longs)
        // A long reminder replaces the short one due at the same time.
        assertEquals(1, got.count { it.first == 120L })
    }

    @Test fun off_neverReminds() {
        val e = EyeBreak(intervalMin = 0)
        assertNull(e.tick(0, true))
        assertNull(e.tick(500 * min, true))
    }

    @Test fun watchingVideo_holdsTheReminder_untilPlaybackStops() {
        val e = EyeBreak(intervalMin = 20)
        e.tick(0, true)
        assertNull(e.tick(20 * min, wearing = true, busy = true))      // due, but a video is playing
        assertNull(e.tick(25 * min, wearing = true, busy = true))      // still playing: still held
        assertEquals(EyeBreak.Reminder.LOOK_AWAY, e.tick(26 * min, wearing = true, busy = false))
        assertNull(e.tick(27 * min, wearing = true, busy = false))     // shown once
    }

    @Test fun heldReminder_droppedIfTheGlassesComeOff() {
        val e = EyeBreak(intervalMin = 20)
        e.tick(0, true)
        e.tick(20 * min, wearing = true, busy = true)
        e.tick(21 * min, wearing = false)
        assertNull(e.tick(30 * min, wearing = true, busy = false))     // rested meanwhile: nothing stale
    }
}
