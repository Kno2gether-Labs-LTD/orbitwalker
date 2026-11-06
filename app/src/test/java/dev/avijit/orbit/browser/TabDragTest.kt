package dev.avijit.orbit.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabDragTest {
    @Test fun smallMoves_areClicks() = assertFalse(TabDrag.popsOut(dx = 10f, dy = 20f, stripHeightPx = 80f))
    @Test fun sidewaysDrags_scrollTheStrip() = assertFalse(TabDrag.popsOut(dx = 400f, dy = 150f, stripHeightPx = 80f))
    @Test fun dragDownOutOfTheStrip_popsOut() = assertTrue(TabDrag.popsOut(dx = 40f, dy = 160f, stripHeightPx = 80f))
    @Test fun dragUp_doesNot() = assertFalse(TabDrag.popsOut(dx = 0f, dy = -300f, stripHeightPx = 80f))
}
