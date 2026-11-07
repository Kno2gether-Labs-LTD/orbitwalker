package dev.avijit.orbit.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlobalShortcutsTest {
    private val ctrlAlt = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON

    @Test fun ctrlAltC_findsTheCursor() = assertEquals(GlobalShortcut.FIND_CURSOR, GlobalShortcuts.match(KeyEvent.KEYCODE_C, ctrlAlt))
    @Test fun ctrlAltR_recenters() = assertEquals(GlobalShortcut.RECENTER, GlobalShortcuts.match(KeyEvent.KEYCODE_R, ctrlAlt))
    @Test fun ctrlEsc_releases() = assertEquals(GlobalShortcut.RELEASE, GlobalShortcuts.match(KeyEvent.KEYCODE_ESCAPE, KeyEvent.META_CTRL_ON))
    @Test fun plainCopy_goesToTheApp() = assertNull(GlobalShortcuts.match(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON))
    @Test fun altC_goesToTheApp() = assertNull(GlobalShortcuts.match(KeyEvent.KEYCODE_C, KeyEvent.META_ALT_ON))
    @Test fun extraShift_stillMatches() = assertEquals(GlobalShortcut.FIND_CURSOR, GlobalShortcuts.match(KeyEvent.KEYCODE_C, ctrlAlt or KeyEvent.META_SHIFT_ON))
    @Test fun ctrlAltS_startsOrStopsRecording() = assertEquals(GlobalShortcut.RECORD, GlobalShortcuts.match(KeyEvent.KEYCODE_S, ctrlAlt))
    @Test fun ctrlAltM_togglesTheCameraMonitor() = assertEquals(GlobalShortcut.CAMERA_MONITOR, GlobalShortcuts.match(KeyEvent.KEYCODE_M, ctrlAlt))
}
