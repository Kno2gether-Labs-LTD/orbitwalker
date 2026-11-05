package dev.avijit.orbit.input

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollAccumulatorTest {
    @Test fun smallMoves_emitNothingUntilANotch() {
        val a = ScrollAccumulator(pxPerNotch = 40f)
        assertEquals(0, a.add(15f))
        assertEquals(0, a.add(15f))
        assertEquals(1, a.add(15f))   // 45 px → 1 notch, 5 px carried
        assertEquals(0, a.add(30f))   // 35
        assertEquals(1, a.add(5f))    // 40
    }

    @Test fun bigMove_emitsSeveral_andNegativeWorks() {
        val a = ScrollAccumulator(pxPerNotch = 40f)
        assertEquals(2, a.add(85f))
        assertEquals(-2, a.add(-85f))  // 5 - 85 = -80
    }
}
