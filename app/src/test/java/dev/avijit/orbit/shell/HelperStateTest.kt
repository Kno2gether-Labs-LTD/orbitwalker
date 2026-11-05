package dev.avijit.orbit.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class HelperStateTest {
    @Test fun shellAlive_isReady_evenIfNeverPaired() =
        assertEquals(HelperState.READY, helperState(hasShell = true, starting = false, paired = false)) // e.g. Shizuku fallback

    @Test fun starting_winsOverPaired() =
        assertEquals(HelperState.STARTING, helperState(hasShell = false, starting = true, paired = true))

    @Test fun pairedButNotRunning_isStopped() =
        assertEquals(HelperState.STOPPED, helperState(hasShell = false, starting = false, paired = true))

    @Test fun neverPaired_needsSetup() =
        assertEquals(HelperState.NEEDS_SETUP, helperState(hasShell = false, starting = false, paired = false))
}
