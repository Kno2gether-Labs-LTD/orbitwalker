package dev.avijit.orbit.input

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelCursorTest {
    @Test fun startsCentred() {
        val c = PanelCursor(1280, 800)
        assertEquals(640f, c.x, 0.01f)
        assertEquals(400f, c.y, 0.01f)
    }

    @Test fun clampsAtEdges() {
        val c = PanelCursor(1280, 800)
        c.moveBy(-5000f, 5000f)
        assertEquals(0f, c.x, 0.01f)
        assertEquals(799f, c.y, 0.01f)
    }

    @Test fun uvAtCorners() {
        val c = PanelCursor(1280, 800)
        c.moveBy(5000f, 5000f)
        assertEquals(1f to 1f, c.uv())
        c.moveBy(-5000f, -5000f)
        assertEquals(0f to 0f, c.uv())
    }
}
