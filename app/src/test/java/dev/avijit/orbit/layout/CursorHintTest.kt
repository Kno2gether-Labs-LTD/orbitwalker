package dev.avijit.orbit.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorHintTest {
    private val fov = 30f
    private val aspect = 16f / 9f

    @Test fun inView_noArrow() = assertNull(CursorHint.edgeArrow(floatArrayOf(0.05f, 0.02f, -1f), fov, aspect))

    @Test fun farRight_arrowOnTheRightEdge_pointingRight() {
        val a = CursorHint.edgeArrow(floatArrayOf(1f, 0f, -0.2f), fov, aspect)!!
        assertEquals(CursorHint.EDGE, a.x, 1e-3f)
        assertEquals(0f, a.y, 1e-3f)
        assertEquals(0f, a.angleDeg, 1e-2f)
    }

    @Test fun above_arrowOnTheTopEdge_pointingUp() {
        val a = CursorHint.edgeArrow(floatArrayOf(0f, 1f, -0.3f), fov, aspect)!!
        assertEquals(CursorHint.EDGE, a.y, 1e-3f)
        assertEquals(90f, a.angleDeg, 1e-2f)
    }

    @Test fun behindAndLeft_stillPointsLeft() {
        val a = CursorHint.edgeArrow(floatArrayOf(-0.3f, 0f, 1f), fov, aspect)!!
        assertEquals(-CursorHint.EDGE, a.x, 1e-3f)
        assertEquals(180f, kotlin.math.abs(a.angleDeg), 1e-2f)
    }

    @Test fun straightBehind_pointsSomewhere() = assertTrue(CursorHint.edgeArrow(floatArrayOf(0f, 0f, 1f), fov, aspect) != null)

    @Test fun diagonal_staysOnTheBorder() {
        val a = CursorHint.edgeArrow(floatArrayOf(1f, 1f, -0.1f), fov, aspect)!!
        assertTrue(kotlin.math.max(kotlin.math.abs(a.x), kotlin.math.abs(a.y)) in (CursorHint.EDGE - 1e-3f)..(CursorHint.EDGE + 1e-3f))
    }
}
