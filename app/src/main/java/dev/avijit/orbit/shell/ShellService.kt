package dev.avijit.orbit.shell

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Process
import android.view.Surface
import org.lsposed.hiddenapibypass.HiddenApiBypass
import kotlin.system.exitProcess

/**
 * Runs as the shell user (uid 2000) in its own process: OrbitWalker's built-in helper
 * ([dev.avijit.orbit.helper.HelperMain]) or, as a fallback, a Shizuku UserService. Creates trusted
 * virtual displays that can host other apps, launches apps onto them and injects input.
 * Nothing throws across the binder; failures are kept in [lastError].
 */
class ShellService() : IOrbitShell.Stub() {

    private var context: Context? = null

    /** Used by Shizuku server v13+. */
    constructor(context: Context) : this() {
        this.context = context
    }

    /** OrbitWalker's uid: the only app allowed to use this shell-level service. */
    private val allowedUid: Int get() = context?.applicationInfo?.uid ?: -1

    override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
        if (code != INTERFACE_TRANSACTION) {
            val caller = android.os.Binder.getCallingUid()
            if (allowedUid < 0 || (caller != allowedUid && caller != Process.myUid())) {
                throw SecurityException("uid $caller may not use OrbitWalker's helper")
            }
        }
        return super.onTransact(code, data, reply, flags)
    }

    private val displays = HashMap<Int, VirtualDisplay>()
    private val injector = Injector()
    private val imeResults = HashMap<Int, Int>()
    @Volatile private var error: String? = null
    @Volatile private var flagsUsed = 0
    @Volatile private var trustedDropped = false

    init {
        runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
            .onFailure { error = "hidden API exemption failed: ${it.describe()}" }
    }

    override fun createDisplay(surface: Surface?, width: Int, height: Int, dpi: Int): Int = synchronized(displays) {
        clearErrors()
        if (surface == null || !surface.isValid) return fail("surface is not valid")
        val base = context ?: return fail("no Context from Shizuku (server older than v13?)")
        val dm = try {
            DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
                .apply { isAccessible = true }
                .newInstance(ShellContext(base))
        } catch (t: Throwable) {
            return fail("DisplayManager(Context): ${t.describe()}")
        }
        val full = DisplayFlags.full(Build.VERSION.SDK_INT)
        val vd = try {
            dm.createVirtualDisplay(DISPLAY_NAME, width, height, dpi, surface, full).also { flagsUsed = full }
        } catch (t: Throwable) {
            val reduced = DisplayFlags.reduced(full)
            error = "trusted display refused (${t.describe()}); retrying untrusted"
            try {
                dm.createVirtualDisplay(DISPLAY_NAME, width, height, dpi, surface, reduced).also {
                    flagsUsed = reduced
                    trustedDropped = true
                }
            } catch (t2: Throwable) {
                return fail("createVirtualDisplay: ${t2.describe()} (trusted attempt: ${t.describe()})")
            }
        } ?: return fail("createVirtualDisplay returned null")
        val id = vd.display.displayId
        displays[id] = vd
        // The IME only goes onto trusted displays; an untrusted fallback can't host it.
        imeResults[id] = if (trustedDropped) 0 else setImeLocal(id)
        id
    }

    override fun launch(displayId: Int, component: String?): String {
        clearErrors()
        if (component == null || !COMPONENT.matches(component)) return "Error: rejected component $component".also { error = it }
        return try {
            val p = ProcessBuilder("am", "start", "-W", "--display", displayId.toString(), "-n", component)
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            val code = p.waitFor()
            "exit $code\n$out"
        } catch (t: Throwable) {
            "Error: am start failed: ${t.describe()}".also { error = it }
        }
    }

    override fun tap(displayId: Int, x: Float, y: Float): Boolean = guarded { injector.tap(displayId, x, y) }

    override fun scroll(displayId: Int, x: Float, y: Float, vscroll: Float): Boolean =
        guarded { injector.scroll(displayId, x, y, vscroll) }

    override fun key(displayId: Int, keyCode: Int): Boolean = guarded { injector.key(displayId, keyCode) }

    override fun keyEvent(displayId: Int, action: Int, keyCode: Int, metaState: Int, repeat: Int): Boolean =
        guarded { injector.keyEvent(displayId, action, keyCode, metaState, repeat) }

    override fun keyEventTimed(displayId: Int, action: Int, keyCode: Int, metaState: Int, repeat: Int, downTime: Long, eventTime: Long): Boolean =
        guarded { injector.keyEvent(displayId, action, keyCode, metaState, repeat, downTime, eventTime) }

    private var raw: RawInputReader? = null

    override fun startRawInput(listener: IRawInputListener?, grab: Boolean): Int = synchronized(this) {
        clearErrors()
        if (listener == null) return -1
        stopRawInput()
        val reader = RawInputReader(listener, grab)
        raw = reader
        // If OrbitWalker dies, give the mouse and keyboard straight back to Android (only this reader).
        val death = android.os.IBinder.DeathRecipient { synchronized(this) { if (raw === reader) stopRawInput() else reader.stop() } }
        runCatching { listener.asBinder().linkToDeath(death, 0) }
        rawDeath = listener.asBinder() to death
        val n = try { reader.start() } catch (t: Throwable) { error = "raw input: ${t.describe()}"; -1 }
        reader.lastError?.let { error = it }
        n
    }

    private var rawDeath: Pair<android.os.IBinder, android.os.IBinder.DeathRecipient>? = null

    override fun stopRawInput() = synchronized(this) {
        raw?.stop()
        raw = null
        rawDeath?.let { (b, d) -> runCatching { b.unlinkToDeath(d, 0) } }
        rawDeath = null
    }

    override fun setRawAnyBus(on: Boolean) { RawInputReader.anyBus = on }

    override fun taskCount(displayId: Int): Int = try {
        val atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
        val list = atm.javaClass.getMethod("getAllRootTaskInfosOnDisplay", Int::class.javaPrimitiveType).invoke(atm, displayId) as List<*>
        list.size
    } catch (t: Throwable) {
        error = "taskCount: ${t.describe()}"
        -1
    }

    override fun rawInputStatus(): Int = synchronized(this) { raw?.openCount() ?: -1 }

    override fun keyEventFull(displayId: Int, action: Int, keyCode: Int, metaState: Int, repeat: Int, downTime: Long, eventTime: Long, scanCode: Int, deviceId: Int): Boolean =
        guarded { injector.keyEvent(displayId, action, keyCode, metaState, repeat, downTime, eventTime, scanCode, deviceId) }

    override fun touch(displayId: Int, action: Int, x: Float, y: Float, downTime: Long): Boolean =
        guarded { injector.touchEvent(displayId, action, x, y, downTime) }

    override fun imePolicy(displayId: Int): Int = synchronized(displays) { imeResults[displayId] ?: 0 }

    /**
     * Asks the window manager to show the soft keyboard on [displayId] itself (scrcpy's
     * --display-ime-policy=local). 1 = done, 0 = API missing, -1 = refused.
     */
    private fun setImeLocal(displayId: Int): Int = try {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "window")
        val wm = Class.forName("android.view.IWindowManager\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)!!
        val cls = wm.javaClass
        val policy = runCatching { cls.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType) }.getOrNull()
        if (policy != null) {
            policy.invoke(wm, displayId, DISPLAY_IME_POLICY_LOCAL)
            // Read it back: "didn't throw" isn't the same as "applied".
            val get = runCatching { cls.getMethod("getDisplayImePolicy", Int::class.javaPrimitiveType) }.getOrNull()
            val now = get?.let { runCatching { it.invoke(wm, displayId) as Int }.getOrNull() }
            if (now == null || now == DISPLAY_IME_POLICY_LOCAL) 1 else 0
        } else {
            val legacy = runCatching { cls.getMethod("setShouldShowIme", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType) }.getOrNull()
            if (legacy != null) { legacy.invoke(wm, displayId, true); 1 } else 0
        }
    } catch (t: Throwable) {
        error = "IME policy: ${t.describe()}"
        -1
    }

    override fun text(displayId: Int, text: String?): Boolean = guarded { injector.text(displayId, text ?: "") }

    override fun releaseDisplay(displayId: Int) {
        synchronized(displays) { imeResults.remove(displayId); displays.remove(displayId) }?.let { runCatching { it.release() } }
    }

    override fun lastError(): String? = error ?: injector.lastError ?: raw?.lastError

    override fun info(): String =
        "uid=${Process.myUid()} sdk=${Build.VERSION.SDK_INT} ctx=${context != null} " +
            "flags=0x${flagsUsed.toString(16)}${if (trustedDropped) " (untrusted fallback)" else ""} " +
            "displays=${synchronized(displays) { displays.keys.toList() }}"

    /** The client (OrbitWalker) died: close every screen it had. Not part of the AIDL interface. */
    fun releaseAllDisplays(): Int = synchronized(displays) {
        val n = displays.size
        displays.values.forEach { runCatching { it.release() } }
        displays.clear()
        imeResults.clear()
        n
    }

    override fun destroy() {
        runCatching { stopRawInput() }
        synchronized(displays) {
            displays.values.forEach { runCatching { it.release() } }
            displays.clear()
        }
        exitProcess(0)
    }

    private fun clearErrors() {
        error = null
        injector.clearError()
    }

    private inline fun guarded(block: () -> Boolean): Boolean = try {
        clearErrors()
        block()
    } catch (t: Throwable) {
        error = t.describe()
        false
    }

    private fun fail(msg: String): Int {
        error = msg
        return -1
    }

    private companion object {
        const val DISPLAY_NAME = "orbit-panel"
        const val DISPLAY_IME_POLICY_LOCAL = 0
        val COMPONENT = Regex("^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$")
    }
}
