package dev.avijit.orbit.helper

/**
 * How OrbitWalker's built-in helper is started and recognised. Pure: shared by the app (which
 * sends [command] over Wireless debugging), the helper (which re-launches itself after an app
 * update) and the tests.
 */
object HelperLaunch {
    const val PACKAGE = "dev.avijit.orbit"
    const val NICE_NAME = "orbitwalker_helper"
    const val MAIN_CLASS = "dev.avijit.orbit.helper.HelperMain"
    const val AUTHORITY = "$PACKAGE.helper"
    const val METHOD_SHELL = "shell"
    const val KEY_BINDER = "shell"
    const val KEY_OK = "ok"
    const val LAUNCHED_MARKER = "orbit-helper-launched"
    const val FAILED_MARKER = "orbit-helper-failed"

    data class Args(val appUid: Int, val apkPath: String)

    /**
     * Shell command that starts the helper detached from the adb session, so it keeps running
     * after the session ends. Like Shizuku started over adb, it stays in adbd's process group: it
     * lives until the phone restarts or adbd itself is stopped (e.g. USB *and* Wireless debugging off).
     */
    fun command(apkPath: String, appUid: Int): String {
        require(validApk(apkPath)) { "unexpected APK path" }
        require(appUid >= FIRST_APP_UID) { "not an app uid" }
        // adbd kills what's left of the session when the command returns, so wait (up to 5 s)
        // until the new process has detached and renamed itself before exiting.
        return "CLASSPATH='$apkPath' setsid /system/bin/app_process /system/bin --nice-name=$NICE_NAME " +
            "$MAIN_CLASS $appUid '$apkPath' </dev/null >/dev/null 2>&1 & " +
            "p=$!; i=0; while [ \$i -lt 50 ]; do " +
            "case \"$(tr '\\0' ' ' < /proc/\$p/cmdline 2>/dev/null)\" in $NICE_NAME*) break;; esac; " +
            "sleep 0.1; i=$((i+1)); done; " +
            "if [ \$i -ge 50 ]; then echo $FAILED_MARKER; else echo $LAUNCHED_MARKER; fi"
    }

    fun parseArgs(args: Array<String>): Args? {
        if (args.size < 2) return null
        val uid = args[0].toIntOrNull() ?: return null
        if (uid % PER_USER_RANGE < FIRST_APP_UID || !validApk(args[1])) return null
        return Args(uid, args[1])
    }

    /** /proc/<pid>/cmdline of OrbitWalker's main process (not its ":shell" Shizuku process). */
    fun isAppProcess(cmdline: String): Boolean = cmdline.substringBefore('\u0000') == PACKAGE

    /** Real uid from /proc/<pid>/status ("Uid:\treal\teffective…"), or -1. */
    fun uidFromStatus(status: String): Int =
        status.lineSequence().firstOrNull { it.startsWith("Uid:") }?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull() ?: -1

    fun isHelperProcess(cmdline: String): Boolean = cmdline.substringBefore('\u0000') == NICE_NAME

    fun userIdOf(uid: Int): Int = uid / PER_USER_RANGE

    private fun validApk(path: String): Boolean =
        path.startsWith("/data/app/") && path.endsWith(".apk") && path.none { it == '\'' || it == '\n' || it == '\u0000' }

    private const val PER_USER_RANGE = 100_000
    private const val FIRST_APP_UID = 10_000
}
