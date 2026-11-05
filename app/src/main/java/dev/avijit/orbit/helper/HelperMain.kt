package dev.avijit.orbit.helper

import android.content.AttributionSource
import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import dev.avijit.orbit.shell.ShellService
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import kotlin.system.exitProcess

/**
 * OrbitWalker's own helper process: started once over Wireless debugging (see [AdbStarter]), runs
 * as the shell user (uid 2000) like Shizuku's server, and keeps running until the phone restarts
 * (or until adbd is stopped, exactly like Shizuku started over Wireless debugging). Whenever OrbitWalker's process starts it hands it
 * the [ShellService] binder through [HelperProvider]; only OrbitWalker's uid may call it.
 */
object HelperMain {
    private const val TAG = "OrbitHelper"
    private const val POLL_MS = 1_000L

    @JvmStatic
    fun main(argv: Array<String>) {
        val args = HelperLaunch.parseArgs(argv) ?: run {
            Log.e(TAG, "bad arguments"); exitProcess(2)
        }
        if (Process.myUid() != SHELL_UID && Process.myUid() != 0) {
            Log.e(TAG, "must run as shell, not uid ${Process.myUid()}"); exitProcess(3)
        }
        runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
        Looper.prepareMainLooper()
        val context = packageContext() ?: run {
            Log.e(TAG, "no package context"); exitProcess(4)
        }
        // Who OrbitWalker is: an update is only followed if it's the same app (uid + signer).
        // Right after an install the package manager can lag for a moment, so retry briefly.
        signer = retry(IDENTITY_TRIES) { signerOf(context, args.appUid) } ?: run {
            Log.e(TAG, "OrbitWalker's identity doesn't match the arguments"); exitProcess(5)
        }
        // Only now replace any older helper: if this one can't start, the old one keeps working.
        stopOtherHelpers()
        val service = ShellService(context)
        Log.i(TAG, "helper up: pid=${Process.myPid()} app uid=${args.appUid}")
        Thread({ deliverLoop(context, service, args) }, "orbit-helper-deliver").apply { isDaemon = true }.start()
        Looper.loop()
    }

    /** Hands the binder to every new OrbitWalker process; follows app updates; exits on uninstall. */
    private fun deliverLoop(context: Context, service: ShellService, args: HelperLaunch.Args) {
        var deliveredPid = -1
        while (true) {
            if (!File(args.apkPath).exists()) relaunchOrExit(context, args)
            if (deliveredPid > 0 && !isApp(deliveredPid, args.appUid)) {
                // OrbitWalker died (crash, force-stop, low memory): close its screens so the apps on
                // them don't keep running invisibly. They reopen when OrbitWalker comes back.
                deliveredPid = -1
                val n = service.releaseAllDisplays()
                Log.i(TAG, "OrbitWalker stopped; released $n screen(s)")
            }
            if (deliveredPid < 0) {
                val pid = pids().firstOrNull { isApp(it, args.appUid) } ?: -1
                if (pid > 0 && deliver(service, args.appUid)) {
                    deliveredPid = pid
                    Log.i(TAG, "binder delivered to pid $pid")
                }
            }
            Thread.sleep(POLL_MS)
        }
    }

    /** The app was updated (new APK path) → start the new code and quit; uninstalled → quit. */
    private fun relaunchOrExit(context: Context, args: HelperLaunch.Args): Nothing {
        // Only follow an update of the same app: same uid and same signing certificate. A
        // different app installed under the name (after an uninstall) never gets shell rights.
        // During an install the package can briefly look missing, so keep trying for a while.
        repeat(RELAUNCH_TRIES) { attempt ->
            val newApk = runCatching {
                val ai = context.packageManager.getApplicationInfo(HelperLaunch.PACKAGE, 0)
                ai.sourceDir.takeIf { signerOf(context, args.appUid) == signer && ai.uid == args.appUid }
            }.getOrNull()
            if (newApk != null && File(newApk).exists()) {
                Log.i(TAG, "app updated; relaunching from the new APK (attempt ${attempt + 1})")
                val out = runCatching {
                    val p = ProcessBuilder("/system/bin/sh", "-c", HelperLaunch.command(newApk, args.appUid))
                        .redirectErrorStream(true).start()
                    p.inputStream.bufferedReader().readText().also { p.waitFor() }
                }.getOrDefault("")
                if (out.contains(HelperLaunch.LAUNCHED_MARKER)) {
                    Thread.sleep(5_000) // the new helper stops this one once it's confirmed
                    Log.i(TAG, "new helper didn't take over; exiting anyway")
                    exitProcess(0)
                }
                Log.w(TAG, "new helper didn't start; retrying")
            }
            Thread.sleep(1_000)
        }
        Log.i(TAG, "OrbitWalker was removed or replaced by a different app; exiting")
        exitProcess(0)
    }

    private inline fun <T> retry(times: Int, block: () -> T?): T? {
        repeat(times) { i ->
            block()?.let { return it }
            if (i < times - 1) Thread.sleep(500)
        }
        return null
    }

    /**
     * Gives the binder to OrbitWalker through its [HelperProvider], the way `content call` reaches
     * a provider from the shell. The provider must belong to OrbitWalker's uid: a different app
     * claiming the authority (e.g. after an uninstall) never receives it.
     */
    private fun deliver(service: IBinder, appUid: Int): Boolean {
        val userId = HelperLaunch.userIdOf(appUid)
        val token = Binder()
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null) ?: return false
        return try {
            val holder = am.javaClass.getMethod(
                "getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType, IBinder::class.java, String::class.java,
            ).invoke(am, HelperLaunch.AUTHORITY, userId, token, "orbitwalker") ?: return false
            val info = holder.javaClass.getField("info").get(holder) as? android.content.pm.ProviderInfo
            if (info?.packageName != HelperLaunch.PACKAGE || info.applicationInfo?.uid != appUid) {
                Log.e(TAG, "provider owner mismatch: ${info?.packageName}/${info?.applicationInfo?.uid}")
                return false
            }
            val provider = holder.javaClass.getField("provider").get(holder) ?: return false
            val call = provider.javaClass.getMethod(
                "call", AttributionSource::class.java, String::class.java, String::class.java, String::class.java, Bundle::class.java,
            )
            val source = AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PACKAGE).build()
            val extras = Bundle().apply { putBinder(HelperLaunch.KEY_BINDER, service) }
            val reply = call.invoke(provider, source, HelperLaunch.AUTHORITY, HelperLaunch.METHOD_SHELL, null, extras) as? Bundle
            reply?.getBoolean(HelperLaunch.KEY_OK) == true
        } catch (t: Throwable) {
            Log.w(TAG, "delivery failed: ${t.cause ?: t}")
            false
        } finally {
            runCatching {
                am.javaClass.getMethod("removeContentProviderExternalAsUser", String::class.java, IBinder::class.java, Int::class.javaPrimitiveType)
                    .invoke(am, HelperLaunch.AUTHORITY, token, userId)
            }
        }
    }

    private var signer: String? = null

    /** SHA-256 of OrbitWalker's signing certificate, or null if its uid isn't [appUid]. */
    private fun signerOf(context: Context, appUid: Int): String? = runCatching {
        val pi = context.packageManager.getPackageInfo(
            HelperLaunch.PACKAGE, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES,
        )
        if (pi.applicationInfo?.uid != appUid) return null
        val certs = pi.signingInfo?.apkContentsSigners ?: return null
        certs.joinToString(",") { c ->
            java.security.MessageDigest.getInstance("SHA-256").digest(c.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }.getOrNull()

    private fun packageContext(): Context? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("systemMain").invoke(null)
        val system = at.getMethod("getSystemContext").invoke(thread) as Context
        system.createPackageContext(HelperLaunch.PACKAGE, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
    }.onFailure { Log.e(TAG, "package context", it) }.getOrNull()

    private fun stopOtherHelpers() {
        val me = Process.myPid()
        pids().filter { it != me && HelperLaunch.isHelperProcess(cmdline(it)) }.forEach {
            Log.i(TAG, "stopping older helper $it")
            Process.killProcess(it)
        }
    }

    /** OrbitWalker's main process: right name *and* OrbitWalker's uid (a process can rename itself). */
    private fun isApp(pid: Int, appUid: Int) =
        HelperLaunch.isAppProcess(cmdline(pid)) && HelperLaunch.uidFromStatus(status(pid)) == appUid

    private fun status(pid: Int): String = runCatching { File("/proc/$pid/status").readText() }.getOrDefault("")

    private fun pids(): List<Int> = File("/proc").list()?.mapNotNull { it.toIntOrNull() } ?: emptyList()

    private fun cmdline(pid: Int): String = runCatching { File("/proc/$pid/cmdline").readText() }.getOrDefault("")

    private fun run(vararg cmd: String): String = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().also { p.waitFor() }
    }.getOrDefault("")

    private const val SHELL_UID = 2000
    private const val IDENTITY_TRIES = 10
    private const val RELAUNCH_TRIES = 20
    private const val SHELL_PACKAGE = "com.android.shell"
}
