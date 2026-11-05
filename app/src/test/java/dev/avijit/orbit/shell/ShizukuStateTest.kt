package dev.avijit.orbit.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuStateTest {
    @Test fun binderDead_isNotRunning() {
        assertEquals(ShizukuState.NOT_RUNNING, shizukuState(false, true, true))
        assertEquals(ShizukuState.NOT_RUNNING, shizukuState(false, false, false))
    }

    @Test fun noPermission() = assertEquals(ShizukuState.NO_PERMISSION, shizukuState(true, false, true))

    @Test fun permittedNotBound_isConnecting() = assertEquals(ShizukuState.CONNECTING, shizukuState(true, true, false))

    @Test fun permittedAndBound_isReady() = assertEquals(ShizukuState.READY, shizukuState(true, true, true))
}
