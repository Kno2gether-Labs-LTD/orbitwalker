package dev.avijit.orbit.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackpadInterpreterTest {

    @Test fun quickStill_isTap() {
        val t = TrackpadInterpreter()
        val out = t.down(0, 100f, 100f, 0) + t.up(0, 103f, 100f, 100)
        assertEquals(listOf(TrackpadAction.Tap), out.filter { it is TrackpadAction.Tap })
        assertFalse(out.any { it is TrackpadAction.Move })
    }

    @Test fun slowStill_isNotTap() {
        val t = TrackpadInterpreter()
        val out = t.down(0, 100f, 100f, 0) + t.up(0, 103f, 100f, 400)
        assertFalse(out.any { it is TrackpadAction.Tap })
    }

    @Test fun drag_movesWithGain() {
        val t = TrackpadInterpreter()
        t.down(0, 0f, 0f, 0)
        assertEquals(listOf(TrackpadAction.Move(48f, 0f)), t.move(0, 30f, 0f, 10))
        assertFalse(t.up(0, 30f, 0f, 20).any { it is TrackpadAction.Tap })
    }

    @Test fun bigTravel_isNotTap() {
        val t = TrackpadInterpreter()
        t.down(0, 0f, 0f, 0)
        t.move(0, 30f, 0f, 50)
        assertFalse(t.up(0, 30f, 0f, 100).any { it is TrackpadAction.Tap })
    }

    @Test fun twoFingers_scrollNotMove() {
        val t = TrackpadInterpreter()
        val out = mutableListOf<TrackpadAction>()
        out += t.down(0, 100f, 100f, 0)
        out += t.down(1, 200f, 100f, 5)
        out += t.move(0, 100f, 120f, 20)
        out += t.move(1, 200f, 120f, 20)
        out += t.up(0, 100f, 120f, 40)
        out += t.up(1, 200f, 120f, 45)
        assertFalse(out.any { it is TrackpadAction.Move })
        assertFalse(out.any { it is TrackpadAction.Tap })
        val scroll = out.filterIsInstance<TrackpadAction.Scroll>().sumOf { it.dy.toDouble() }
        assertTrue(scroll > 0)
        assertEquals(20.0 * 1.6, scroll, 1.0)
    }

    @Test fun twoFingersSpreading_isPinch() {
        val t = TrackpadInterpreter()
        val out = mutableListOf<TrackpadAction>()
        out += t.down(0, 100f, 100f, 0)
        out += t.down(1, 200f, 100f, 5)
        out += t.move(1, 260f, 100f, 20)   // span 100 → 160
        val pinch = out.filterIsInstance<TrackpadAction.Pinch>()
        assertEquals(1, pinch.size)
        assertEquals(1.6f, pinch[0].scale, 0.01f)
        assertFalse(out.any { it is TrackpadAction.Scroll })
    }
}
