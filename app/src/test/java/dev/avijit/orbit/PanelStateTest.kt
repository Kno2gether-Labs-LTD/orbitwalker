package dev.avijit.orbit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelStateTest {
    @Test fun shellLost_dropsDisplayButKeepsAppForReopen() {
        val s = PanelState(displayId = 5, launched = true, component = "a/b")
        val after = s.onShellLost()
        assertEquals(-1, after.displayId)
        assertFalse(after.active)
        assertEquals("a/b", after.component)
        assertTrue(after.wantsReopen)
    }

    @Test fun launchFailed_forgetsApp() {
        val s = PanelState(displayId = 5, launched = false, component = "a/b").onLaunchFailed()
        assertEquals(null, s.component)
        assertFalse(s.wantsReopen)
    }
}
