package dev.avijit.orbit.helper

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import dev.avijit.orbit.diag.DiagLog
import io.github.muntashirakon.adb.android.AdbMdns
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The built-in setup flow, shared by the Setup screen and the pairing notification:
 * find the "Pair device with pairing code" dialog → pair with its code → start the helper.
 * State is read on the main thread; work runs on its own thread.
 */
object HelperSetup {
    enum class Phase {
        IDLE,
        /** Looking for the pairing dialog (Wireless debugging → Pair device with pairing code). */
        FINDING_DIALOG,
        /** The dialog is open: waiting for the 6-digit code. */
        WAITING_FOR_CODE,
        PAIRING,
        STARTING,
        DONE,
        /** Pairing (or finding the dialog) went wrong: enter the code again. */
        FAILED,
        /** Paired, but starting the helper failed: tap Start again. */
        START_FAILED,
    }

    @Volatile var phase = Phase.IDLE
        private set
    /** Why the last step failed, in plain words. */
    @Volatile var message: String? = null
        private set

    private var pairHost: String? = null
    private var pairPort = -1
    private var mdns: AdbMdns? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val worker by lazy { Handler(HandlerThread("orbit-helper-setup").apply { start() }.looper) }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    val busy: Boolean get() = phase == Phase.PAIRING || phase == Phase.STARTING

    /** Starts watching for the pairing dialog. Main thread. */
    fun beginPairing(context: Context) {
        if (busy) return
        stopDiscovery()
        pairPort = -1
        set(Phase.FINDING_DIALOG, null)
        mdns = AdbMdns(context.applicationContext, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host, port ->
            main.post {
                if (port > 0 && host != null) {
                    pairHost = host.hostAddress
                    pairPort = port
                    DiagLog.i(TAG, "pairing dialog found")
                    if (phase == Phase.FINDING_DIALOG || phase == Phase.FAILED) set(Phase.WAITING_FOR_CODE, null)
                } else {
                    // The pairing dialog closed: its port is dead now.
                    pairPort = -1
                    if (phase == Phase.WAITING_FOR_CODE || phase == Phase.FAILED) set(Phase.FINDING_DIALOG, null)
                }
            }
        }.also { it.start() }
        main.removeCallbacks(giveUp)
        main.postDelayed(giveUp, PAIRING_TIMEOUT_MS)
    }

    /** Nobody entered a code for a while: stop looking (and let the notification go). */
    private val giveUp = Runnable {
        if (phase == Phase.FINDING_DIALOG || phase == Phase.WAITING_FOR_CODE || phase == Phase.FAILED) {
            DiagLog.i(TAG, "pairing timed out")
            cancel()
        }
    }

    /** The user typed the code (notification reply or the Setup screen). Main thread. */
    fun submitCode(context: Context, code: String) {
        val host = pairHost
        val port = pairPort
        if (busy) return
        if (host == null || port <= 0) {
            set(Phase.FAILED, "Open Wireless debugging → Pair device with pairing code first, then enter the code")
            return
        }
        val app = context.applicationContext
        set(Phase.PAIRING, null)
        worker.post {
            val err = AdbStarter.pair(app, host, port, code)
            main.post {
                if (err != null) {
                    set(Phase.FAILED, err)
                } else {
                    AdbStarter.setPaired(app, true)
                    stopDiscovery()
                    phase = Phase.IDLE // pairing is over; startHelper refuses while busy
                    startHelper(app)
                }
            }
        }
    }

    /** Connects to Wireless debugging and starts the helper (after pairing, or after a restart). Main thread. */
    fun startHelper(context: Context) {
        if (busy) return
        if (BuiltInHelper.shell() != null) { stopDiscovery(); return set(Phase.DONE, null) }
        val app = context.applicationContext
        set(Phase.STARTING, null)
        worker.post {
            val err = AdbStarter.start(app)
            if (err != null) {
                main.post { set(if (AdbStarter.isPaired(app)) Phase.START_FAILED else Phase.FAILED, err) }
                return@post
            }
            val until = SystemClock.uptimeMillis() + CONNECT_WAIT_MS
            while (BuiltInHelper.shell() == null && SystemClock.uptimeMillis() < until) SystemClock.sleep(200)
            main.post {
                if (BuiltInHelper.shell() != null) { stopDiscovery(); set(Phase.DONE, null) }
                else set(Phase.START_FAILED, "The helper started but didn't connect. Tap Start again, or share logs")
            }
        }
    }

    fun cancel() {
        main.removeCallbacks(giveUp)
        stopDiscovery()
        pairPort = -1
        if (!busy) set(Phase.IDLE, null)
    }

    private fun stopDiscovery() {
        runCatching { mdns?.stop() }
        mdns = null
    }

    private fun set(p: Phase, msg: String?) {
        phase = p
        message = msg
        if (p == Phase.FAILED || p == Phase.START_FAILED) DiagLog.e(TAG, "setup: $msg")
        listeners.forEach { it() }
    }

    private const val TAG = "helper"
    private const val CONNECT_WAIT_MS = 10_000L
    private const val PAIRING_TIMEOUT_MS = 3 * 60_000L
}
