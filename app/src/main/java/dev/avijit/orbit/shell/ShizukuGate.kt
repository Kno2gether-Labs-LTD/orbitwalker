package dev.avijit.orbit.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.avijit.orbit.BuildConfig
import dev.avijit.orbit.diag.DiagLog
import rikka.shizuku.Shizuku

enum class ShizukuState { NOT_RUNNING, NO_PERMISSION, CONNECTING, READY }

fun shizukuState(binderAlive: Boolean, permissionGranted: Boolean, serviceBound: Boolean): ShizukuState = when {
    !binderAlive -> ShizukuState.NOT_RUNNING
    !permissionGranted -> ShizukuState.NO_PERMISSION
    !serviceBound -> ShizukuState.CONNECTING
    else -> ShizukuState.READY
}

/** Tracks Shizuku availability and binds Orbit's [ShellService]. Main thread only. */
class ShizukuGate(private val context: Context) {

    var listener: ((ShizukuState) -> Unit)? = null
    var state: ShizukuState = ShizukuState.NOT_RUNNING
        private set

    @Volatile private var shell: IOrbitShell? = null
    private var binding = false

    /** The service didn't connect within [BIND_TIMEOUT_MS]; the UI offers Retry. */
    var bindStalled = false
        private set

    /** The user chose "deny and don't ask again" in Shizuku; it must be allowed inside Shizuku. */
    var permissionBlocked = false
        private set

    private var autoRetries = 0

    private val bindTimeout: Runnable = Runnable {
        if (binding && shell == null) {
            DiagLog.e(TAG, "shell service did not connect within ${BIND_TIMEOUT_MS / 1000} s")
            runCatching { Shizuku.unbindUserService(args, connection, true) }
            binding = false
            if (autoRetries++ < AUTO_RETRIES) {
                // A helper that died leaves a stale record in Shizuku; a fresh bind usually works.
                DiagLog.i(TAG, "retrying the shell service automatically ($autoRetries/$AUTO_RETRIES)")
                main.postDelayed({ refresh() }, RETRY_DELAY_MS)
            } else {
                bindStalled = true
                listener?.invoke(state)
            }
        }
    }
    private val main = Handler(Looper.getMainLooper())

    private val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellService::class.java.name))
        .daemon(false)
        .processNameSuffix("shell")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            main.removeCallbacks(bindTimeout)
            if (binder != null && binder.pingBinder()) {
                autoRetries = 0
                shell = IOrbitShell.Stub.asInterface(binder)
                DiagLog.i(TAG, "shell service connected: ${runCatching { shell?.info() }.getOrNull()}")
            } else {
                DiagLog.e(TAG, "shell service returned a dead binder")
            }
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            DiagLog.e(TAG, "shell service disconnected")
            shell = null
            binding = false
            main.removeCallbacks(bindTimeout)
            // Drop Shizuku's record of the dead helper before binding a new one, or the new
            // binder can be delivered to the stale record and never reach us.
            runCatching { Shizuku.unbindUserService(args, connection, true) }
            listener?.invoke(state)
            main.postDelayed({ refresh() }, RETRY_DELAY_MS)
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { main.post { refresh() } }
    private val binderDead = Shizuku.OnBinderDeadListener {
        main.post {
            DiagLog.e(TAG, "Shizuku stopped")
            shell = null
            binding = false
            refresh()
        }
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, result ->
        main.post {
            DiagLog.i(TAG, "Shizuku permission ${if (result == PackageManager.PERMISSION_GRANTED) "granted" else "denied"}")
            refresh()
        }
    }

    fun start() {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    fun stop() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        if (shell != null || binding) runCatching { Shizuku.unbindUserService(args, connection, true) }
        shell = null
        binding = false
    }

    fun shell(): IOrbitShell? = shell?.takeIf { it.asBinder().pingBinder() }

    fun requestPermission() {
        if (!alive()) return
        permissionBlocked = runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)
        if (permissionBlocked) {
            DiagLog.e(TAG, "Shizuku permission was denied permanently")
            listener?.invoke(state)
            return
        }
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
            .onFailure { DiagLog.e(TAG, "requestPermission failed", it) }
    }

    /** Try binding again after [bindStalled]. */
    fun retry() {
        bindStalled = false
        refresh()
    }

    private fun alive() = runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }.getOrDefault(false)

    private fun granted() = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    private fun refresh() {
        val alive = alive()
        val granted = alive && granted()
        if (granted) permissionBlocked = false
        if (alive && granted && shell() == null && !binding && !bindStalled) {
            binding = true
            main.removeCallbacks(bindTimeout)
            main.postDelayed(bindTimeout, BIND_TIMEOUT_MS)
            DiagLog.i(TAG, "binding shell service (Shizuku v${runCatching { Shizuku.getVersion() }.getOrNull()})")
            runCatching { Shizuku.bindUserService(args, connection) }.onFailure {
                binding = false
                DiagLog.e(TAG, "bindUserService failed", it)
            }
        }
        val s = shizukuState(alive, granted, shell() != null)
        if (s != state) {
            state = s
            DiagLog.i(TAG, "Shizuku: $s")
        }
        listener?.invoke(s)
    }

    private companion object {
        const val TAG = "apps"
        const val REQUEST_CODE = 7
        const val BIND_TIMEOUT_MS = 10_000L
        const val AUTO_RETRIES = 2
        const val RETRY_DELAY_MS = 1_000L
    }
}
