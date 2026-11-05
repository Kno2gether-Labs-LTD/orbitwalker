package dev.avijit.orbit.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperLaunchTest {

    private val apk = "/data/app/~~abc==/dev.avijit.orbit-xyz==/base.apk"

    @Test
    fun command_runsMainClassFromApk_detachedWithNiceName() {
        val cmd = HelperLaunch.command(apk, 10234)
        assertTrue(cmd, cmd.contains("CLASSPATH='$apk'"))
        assertTrue(cmd, cmd.contains("setsid /system/bin/app_process /system/bin --nice-name=orbitwalker_helper dev.avijit.orbit.helper.HelperMain 10234 '$apk' <"))
        assertTrue(cmd, cmd.contains("</dev/null >/dev/null 2>&1 &"))
        assertTrue(cmd, cmd.contains("then echo orbit-helper-failed; else echo orbit-helper-launched; fi"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun command_rejectsApkPathWithQuote() {
        HelperLaunch.command("/data/app/x'; reboot; '/base.apk", 10234)
    }

    @Test(expected = IllegalArgumentException::class)
    fun command_rejectsPathOutsideDataApp() {
        HelperLaunch.command("/sdcard/evil.apk", 10234)
    }

    @Test
    fun parseArgs_roundTrips() {
        val a = HelperLaunch.parseArgs(arrayOf("10234", apk))!!
        assertEquals(10234, a.appUid)
        assertEquals(apk, a.apkPath)
    }

    @Test
    fun parseArgs_rejectsGarbage() {
        assertNull(HelperLaunch.parseArgs(arrayOf("x", apk)))
        assertNull(HelperLaunch.parseArgs(arrayOf("10234")))
        assertNull(HelperLaunch.parseArgs(arrayOf("1000", apk))) // not an app uid
    }

    @Test
    fun isAppProcess_matchesOnlyTheMainProcess() {
        assertTrue(HelperLaunch.isAppProcess("dev.avijit.orbit\u0000"))
        assertTrue(HelperLaunch.isAppProcess("dev.avijit.orbit"))
        assertFalse(HelperLaunch.isAppProcess("dev.avijit.orbit:shell\u0000"))
        assertFalse(HelperLaunch.isAppProcess("dev.avijit.orbitx"))
        assertFalse(HelperLaunch.isAppProcess(""))
    }

    @Test
    fun isHelperProcess_matchesNiceName() {
        assertTrue(HelperLaunch.isHelperProcess("orbitwalker_helper\u0000"))
        assertFalse(HelperLaunch.isHelperProcess("app_process\u0000"))
    }

    @Test
    fun userIdOf_appUid() {
        assertEquals(0, HelperLaunch.userIdOf(10234))
        assertEquals(150, HelperLaunch.userIdOf(15010234))
    }

    @Test
    fun uidFromStatus_readsRealUid() {
        assertEquals(10234, HelperLaunch.uidFromStatus("Name:\tdev.avijit.orbit\nUid:\t10234\t10234\t10234\t10234\nGid:\t1\n"))
        assertEquals(-1, HelperLaunch.uidFromStatus("Name:\tx\n"))
    }
}
