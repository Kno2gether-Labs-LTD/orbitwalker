package dev.avijit.orbit.layout

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VrKeyboardTest {
    private val kb = VrKeyboard

    @Test fun topLeftIsQ() {
        val k = kb.keyAt(0.02f, 0.05f, VrKeyboard.Page.LETTERS)!!
        assertEquals("q", k.label)
        assertEquals(KeyEvent.KEYCODE_Q, k.keyCode)
    }

    @Test fun topRightIsP() = assertEquals("p", kb.keyAt(0.98f, 0.05f, VrKeyboard.Page.LETTERS)!!.label)

    @Test fun spaceIsInTheMiddleOfTheBottomRow() {
        val k = kb.keyAt(0.5f, 0.9f, VrKeyboard.Page.LETTERS)!!
        assertEquals(KeyEvent.KEYCODE_SPACE, k.keyCode)
    }

    @Test fun shiftAndBackspaceOnThirdRow() {
        assertEquals(VrKeyboard.Action.SHIFT, kb.keyAt(0.02f, 0.62f, VrKeyboard.Page.LETTERS)!!.action)
        assertEquals(KeyEvent.KEYCODE_DEL, kb.keyAt(0.98f, 0.62f, VrKeyboard.Page.LETTERS)!!.keyCode)
    }

    @Test fun symbolsPage_atSignNeedsShiftTwo() {
        val k = kb.keyAt(0.02f, 0.37f, VrKeyboard.Page.SYMBOLS)!!
        assertEquals("@", k.label)
        assertEquals(KeyEvent.KEYCODE_2, k.keyCode)
        assertEquals(true, k.shift)
    }

    @Test fun outsideIsNull() {
        assertNull(kb.keyAt(-0.1f, 0.5f, VrKeyboard.Page.LETTERS))
        assertNull(kb.keyAt(0.5f, 1.2f, VrKeyboard.Page.LETTERS))
    }

    @Test fun rowsAreFilledEdgeToEdge() {
        VrKeyboard.Page.entries.forEach { page ->
            kb.rows(page).forEach { row -> assertEquals(1f, row.sumOf { it.weight.toDouble() }.toFloat() / row.sumOf { it.weight.toDouble() }.toFloat(), 1e-6f) }
        }
    }
}
