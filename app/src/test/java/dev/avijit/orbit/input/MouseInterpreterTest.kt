package dev.avijit.orbit.input

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MouseInterpreterTest {
    private val primary = MotionEvent.BUTTON_PRIMARY
    private val secondary = MotionEvent.BUTTON_SECONDARY

    @Test fun moveReported() = assertEquals(listOf(MouseOut.Move(3f, -2f)), MouseInterpreter().onCaptured(3f, -2f, 0, 0f))

    @Test fun primaryRelease_clicksOnce() {
        val m = MouseInterpreter()
        assertTrue(m.onCaptured(0f, 0f, primary, 0f).isEmpty())
        assertEquals(listOf(MouseOut.Click), m.onCaptured(0f, 0f, 0, 0f))
        assertTrue(m.onCaptured(0f, 0f, 0, 0f).isEmpty())
    }

    @Test fun secondaryRelease_backNotClick() {
        val m = MouseInterpreter()
        m.onCaptured(0f, 0f, secondary, 0f)
        assertEquals(listOf(MouseOut.Back), m.onCaptured(0f, 0f, 0, 0f))
    }

    @Test fun wheelAccumulatesNotches() {
        val m = MouseInterpreter()
        assertTrue(m.onCaptured(0f, 0f, 0, 0.5f).isEmpty())
        assertEquals(listOf(MouseOut.Scroll(1)), m.onCaptured(0f, 0f, 0, 0.6f))
    }
}
