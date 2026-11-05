package dev.avijit.orbit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlotsTest {
    @Test fun nextFree_fillsLowest() {
        assertEquals(0, Slots.nextFree(emptySet()))
        assertEquals(1, Slots.nextFree(setOf(0, 2)))
    }

    @Test fun nextFree_fullIsNull() = assertNull(Slots.nextFree((0 until 10).toSet()))

    @Test fun nextFree_fifthGoesFarLeftThenSecondRow() {
        assertEquals(4, Slots.nextFree(setOf(0, 1, 2, 3)))
        assertEquals(5, Slots.nextFree(setOf(0, 1, 2, 3, 4)))
    }

    @Test fun focusAfterClose_prefersSameRow() =
        assertEquals(1, Slots.focusAfterClose(0, setOf(1, 5)))

    @Test fun focusAfterClose_prefersNeighbour() {
        // arc positions: slot0=0, slot1=+1, slot2=-1, slot3=+2. Closing slot1 (+1): slot0 (0) and slot3 (+2) are equally near; prefer the centre-most.
        assertEquals(0, Slots.focusAfterClose(1, setOf(0, 3)))
        assertEquals(1, Slots.focusAfterClose(3, setOf(1, 2)))
    }

    @Test fun focusAfterClose_lastClosedIsNull() = assertNull(Slots.focusAfterClose(0, emptySet()))

    @Test fun nextInArc_cyclesLeftToRight() {
        // left-to-right arc order: slot2(-1), slot0(0), slot1(+1), slot3(+2)
        assertEquals(1, Slots.nextInArc(0, setOf(0, 1, 2)))
        assertEquals(2, Slots.nextInArc(1, setOf(0, 1, 2)))
        assertEquals(0, Slots.nextInArc(null, setOf(0, 1)))
    }
}
