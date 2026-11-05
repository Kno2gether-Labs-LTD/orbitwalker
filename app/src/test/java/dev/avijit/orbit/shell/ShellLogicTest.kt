package dev.avijit.orbit.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellLogicTest {
    @Test fun amStart_statusOk_isOpened() {
        val r = AmStart.parse(0, "Starting: Intent { cmp=com.android.chrome/.Main }\nStatus: ok\nLaunchState: COLD")
        assertEquals(AmStart.Outcome.OPENED, r.outcome)
    }

    @Test fun amStart_broughtToFront_isUnconfirmed() {
        val r = AmStart.parse(0, "Starting: Intent {}\nWarning: Activity not started, its current task has been brought to the front\nStatus: ok")
        assertEquals(AmStart.Outcome.UNCONFIRMED, r.outcome)
        assertTrue(r.reason.contains("brought to the front"))
    }

    @Test fun amStart_errorLine_isFailed() {
        val r = AmStart.parse(0, "Starting: Intent {}\nError: Activity class {x/y} does not exist.")
        assertEquals(AmStart.Outcome.FAILED, r.outcome)
    }

    @Test fun amStart_nonZeroExit_isFailed() = assertEquals(AmStart.Outcome.FAILED, AmStart.parse(1, "boom").outcome)

    @Test fun amStart_packageNamedError_isNotFailed() {
        val r = AmStart.parse(0, "Starting: Intent { cmp=com.errorapps.reader/.Main }\nStatus: ok")
        assertEquals(AmStart.Outcome.OPENED, r.outcome)
    }

    @Test fun reducedFlags_dropEverythingNeedingTrustedPermission() {
        val reduced = DisplayFlags.reduced(DisplayFlags.full(sdk = 34))
        listOf(DisplayFlags.TRUSTED, DisplayFlags.ALWAYS_UNLOCKED, DisplayFlags.OWN_DISPLAY_GROUP, DisplayFlags.OWN_FOCUS)
            .forEach { assertEquals(0, reduced and it) }
        assertEquals(DisplayFlags.PUBLIC, reduced and DisplayFlags.PUBLIC)
    }

    @Test fun scrollFallback_sourceBeforeDisplay() {
        assertEquals(
            listOf("input", "mouse", "-d", "7", "scroll", "10", "20", "--axis", "VSCROLL,-1.0"),
            Injector.scrollCommand(7, 10f, 20f, -1f),
        )
    }
}
