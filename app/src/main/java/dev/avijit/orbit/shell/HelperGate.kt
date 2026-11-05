package dev.avijit.orbit.shell

import android.content.Context
import dev.avijit.orbit.diag.DiagLog
import dev.avijit.orbit.helper.AdbStarter
import dev.avijit.orbit.helper.BuiltInHelper
import dev.avijit.orbit.helper.HelperSetup

/** Whether app screens (the shell-level helper) are available. */
enum class HelperState {
    /** Never paired with Wireless debugging: show the one-time setup. */
    NEEDS_SETUP,
    /** Paired, but the helper isn't running (e.g. after a phone restart): tap Start. */
    STOPPED,
    STARTING,
    READY,
}

fun helperState(hasShell: Boolean, starting: Boolean, paired: Boolean): HelperState = when {
    hasShell -> HelperState.READY
    starting -> HelperState.STARTING
    paired -> HelperState.STOPPED
    else -> HelperState.NEEDS_SETUP
}

/**
 * The app's single source of the shell helper. OrbitWalker's built-in helper comes first; Shizuku
 * is used only as a fallback when it's already running and allowed (nothing asks for it any more).
 * Main thread only.
 */
class HelperGate(private val context: Context) {
    var listener: ((HelperState) -> Unit)? = null
    /** Called with the old helper (still alive) just before switching away from it. */
    var onSwitchAway: ((IOrbitShell) -> Unit)? = null
    var state: HelperState = HelperState.NEEDS_SETUP
        private set
    /** "built-in" or "Shizuku" while READY. */
    var via: String? = null
        private set

    private val shizuku = ShizukuGate(context)
    private var current: IOrbitShell? = null
    private val onChange: () -> Unit = { refresh() }

    fun start() {
        BuiltInHelper.addListener(onChange)
        HelperSetup.addListener(onChange)
        shizuku.listener = { refresh() }
        shizuku.start()
        refresh()
    }

    fun stop() {
        BuiltInHelper.removeListener(onChange)
        HelperSetup.removeListener(onChange)
        shizuku.listener = null
        shizuku.stop()
    }

    fun shell(): IOrbitShell? = BuiltInHelper.shell() ?: shizuku.shell()

    /** Starts the built-in helper over Wireless debugging (it must be on). */
    fun startHelper() = HelperSetup.startHelper(context)

    fun refresh() {
        val s = shell()
        if (current != null && s?.asBinder() !== current?.asBinder()) {
            // Switching helpers (or losing one): screens on the old helper's displays are gone.
            // If the old one is still alive (Shizuku → built-in), let it close its displays first.
            current?.takeIf { it.asBinder().pingBinder() }?.let { old -> onSwitchAway?.invoke(old) }
            current = null
            via = null
            state = HelperState.STOPPED
            listener?.invoke(state)
        }
        current = s
        via = when {
            s == null -> null
            s.asBinder() === BuiltInHelper.shell()?.asBinder() -> "built-in"
            else -> "Shizuku"
        }
        val next = helperState(s != null, HelperSetup.busy, AdbStarter.isPaired(context))
        if (next != state) DiagLog.i(TAG, "helper: $next${via?.let { " ($it)" } ?: ""}")
        state = next
        listener?.invoke(next)
    }

    private companion object { const val TAG = "apps" }
}
