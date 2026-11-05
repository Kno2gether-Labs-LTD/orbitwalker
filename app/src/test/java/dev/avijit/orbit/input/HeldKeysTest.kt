package dev.avijit.orbit.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldKeysTest {
    @Test fun upGoesToTheDisplayOfItsDown() {
        val h = HeldKeys()
        h.down(42, display = 7, at = 100)
        assertEquals(7, h.up(42)!!.display)
    }

    @Test fun repeatCountIncrements() {
        val h = HeldKeys()
        h.down(30, 7, 0)
        assertEquals(1, h.repeat(30))
        assertEquals(2, h.repeat(30))
    }

    @Test fun releaseAll_returnsEveryHeldKeyOnce() {
        val h = HeldKeys()
        h.down(29, 7, 0); h.down(30, 8, 0)
        val all = h.releaseAll()
        assertEquals(setOf(29, 30), all.map { it.code }.toSet())
        assertTrue(h.releaseAll().isEmpty())
    }
}
