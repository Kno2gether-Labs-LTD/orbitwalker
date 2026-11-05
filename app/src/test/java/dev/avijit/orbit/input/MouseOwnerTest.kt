package dev.avijit.orbit.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MouseOwnerTest {
    @Test fun eitherWindowCapturing_isCaptured() {
        assertTrue(MouseOwner().glasses(true).captured)
        assertTrue(MouseOwner().phone(true).captured)
    }

    @Test fun lateGlassesLoss_doesNotClearPhoneCapture() {
        val s = MouseOwner().glasses(true).phone(true).glasses(false)
        assertTrue(s.captured)
    }

    @Test fun presentationGone_clearsGlassesOnly() {
        assertFalse(MouseOwner().glasses(true).presentationGone().captured)
    }

    @Test fun released_hidesNothingAndWantsNothing() {
        val s = MouseOwner().release()
        assertFalse(s.wants(screensReady = true))
        assertTrue(MouseOwner().wants(screensReady = true))
        assertFalse(MouseOwner().wants(screensReady = false))
        assertTrue(s.reclaim().wants(screensReady = true))
    }
}
