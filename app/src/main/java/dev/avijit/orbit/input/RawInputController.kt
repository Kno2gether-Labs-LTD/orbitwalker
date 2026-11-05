package dev.avijit.orbit.input

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import dev.avijit.orbit.diag.DiagLog
import dev.avijit.orbit.shell.IOrbitShell
import dev.avijit.orbit.shell.IRawInputListener

/**
 * Bluetooth/USB mouse and keyboard, read directly by the Shizuku helper and taken over
 * exclusively while OrbitWalker has screens open. This avoids Android's focus rules entirely:
 * the mouse never shows Android's pointer, and keys always go to the focused glasses screen.
 */
class RawInputController(
    private val context: Context,
    private val shell: () -> IOrbitShell?,
    private val sink: Sink,
) {
    /** Where raw input goes (implemented by the app). */
    interface Sink {
        fun onPointerMove(dx: Float, dy: Float)
        /** Left button pressed / moved while pressed / released: press, drag and long-press in apps. */
        fun onPointerDown()
        fun onPointerDrag()
        fun onPointerUp()
        fun onClick()
        fun onBack()
        fun onScroll(notches: Int)
        /** Display that keys go to, or -1 for none. */
        fun keyTarget(): Int
        fun injectKey(displayId: Int, action: Int, keyCode: Int, meta: Int, repeat: Int, downTime: Long, eventTime: Long, scanCode: Int, deviceId: Int)
        fun onReleasedByShortcut()
        fun onChanged()
    }

    /** The helper is reading (may have 0 devices until one connects). */
    @Volatile var running = false
        private set
    @Volatile var devices: String = ""
        private set
    /** At least one device is grabbed: Android isn't seeing the mouse/keyboard. */
    val active: Boolean get() = running && devices.isNotBlank()

    private val main = Handler(Looper.getMainLooper())
    /** Helper calls can block (thread joins, file opens): never on the UI thread. */
    private val worker = Handler(HandlerThread("orbit-rawinput").apply { start() }.looper)
    private val meta = MetaTracker()
    private val held = HeldKeys()
    private var buttons = 0
    @Volatile private var wanted = false
    private var startedOn: IOrbitShell? = null
    private var syncs = 0
    /** Android's id for the grabbed keyboard (so injected keys come from a "real" keyboard), or -1. */
    @Volatile private var keyboardDeviceId = android.view.KeyCharacterMap.VIRTUAL_KEYBOARD

    private val listener = object : IRawInputListener.Stub() {
        override fun onMouse(dx: Int, dy: Int, buttons: Int, wheel: Int) {
            main.post { mouse(dx, dy, buttons, wheel) }
        }

        override fun onKey(linuxCode: Int, value: Int) {
            main.post { key(linuxCode, value) }
        }

        override fun onDevices(description: String?) {
            main.post {
                devices = description.orEmpty()
                keyboardDeviceId = findKeyboardDeviceId(devices)
                DiagLog.i(TAG, "raw input devices: ${devices.ifEmpty { "none" }}")
                sink.onChanged()
            }
        }
    }

    /**
     * Starts or stops reading so it matches [want]. Cheap to call often (main thread). Also heals:
     * if the helper restarted (new binder) or its reader stopped, reading is restarted.
     */
    fun sync(want: Boolean) {
        val s = shell()
        if (want && wanted && running && (s == null || s !== startedOn || !s.asBinder().pingBinder())) {
            DiagLog.e(TAG, "raw input: helper changed or died; restarting")
            restartReset()
        }
        // A start that failed (helper briefly unavailable) is retried with a short backoff.
        if (want && wanted && !running && !starting && SystemClock.uptimeMillis() - lastAttemptMs > RETRY_MS) {
            DiagLog.i(TAG, "raw input: retrying start")
            restartReset()
        }
        if (want && wanted && running && ++syncs % HEALTH_EVERY == 0) {
            worker.post {
                val status = runCatching { startedOn?.rawInputStatus() ?: -1 }.getOrDefault(-1)
                if (status < 0) main.post {
                    DiagLog.e(TAG, "raw input: reader not running in the helper; restarting")
                    restartReset()
                }
            }
        }
        if (want == wanted) return
        wanted = want
        if (!want) releaseHeld()
        starting = true
        lastAttemptMs = SystemClock.uptimeMillis()
        worker.post {
            val s = shell() ?: run { running = false; return@post }
            if (wanted) {
                val n = runCatching { s.startRawInput(listener, true) }.getOrDefault(-1)
                running = n >= 0
                startedOn = s
                val note = runCatching { s.lastError() }.getOrNull()
                if (n < 0) DiagLog.e(TAG, "raw input failed: $note")
                else DiagLog.i(TAG, "raw input on: $n device(s) grabbed${note?.let { " (note: $it)" } ?: ""}")
            } else {
                runCatching { s.stopRawInput() }
                running = false
                DiagLog.i(TAG, "raw input off")
            }
            main.post { starting = false; if (!running) devices = ""; sink.onChanged() }
        }
    }

    @Volatile private var starting = false
    private var lastAttemptMs = 0L

    /** Before a restart: lift held keys/buttons (their UPs may have gone to Android) and start over. */
    private fun restartReset() {
        releaseHeld()
        wanted = false
        running = false
    }

    /** Lift every key and button we still think is down, so nothing stays stuck (e.g. Ctrl after Ctrl+Esc). */
    private fun releaseHeld() {
        val now = SystemClock.uptimeMillis()
        held.releaseAll().forEach { h ->
            val code = LinuxKeys.toAndroid(h.code)
            if (code != KeyEvent.KEYCODE_UNKNOWN && h.display >= 0) {
                sink.injectKey(h.display, KeyEvent.ACTION_UP, code, 0, 0, h.downAt, now, h.code, keyboardDeviceId)
            }
        }
        meta.reset()
        if (buttons and LEFT != 0) sink.onPointerUp()
        buttons = 0
    }

    private fun findKeyboardDeviceId(names: String): Int = android.view.InputDevice.getDeviceIds().firstOrNull { id ->
        android.view.InputDevice.getDevice(id)?.let {
            it.isExternal && it.keyboardType == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC && names.contains(it.name)
        } == true
    } ?: android.view.KeyCharacterMap.VIRTUAL_KEYBOARD

    private fun mouse(dx: Int, dy: Int, newButtons: Int, wheel: Int) {
        val pressed = newButtons and buttons.inv()
        val released = buttons and newButtons.inv()
        if (pressed and LEFT != 0) sink.onPointerDown()
        if (dx != 0 || dy != 0) {
            sink.onPointerMove(dx * MOUSE_GAIN, dy * MOUSE_GAIN)
            if (newButtons and LEFT != 0) sink.onPointerDrag()
        }
        if (released and LEFT != 0) sink.onPointerUp()
        if (released and RIGHT != 0 && newButtons and LEFT == 0) sink.onBack()
        buttons = newButtons
        if (wheel != 0) sink.onScroll(wheel)
    }

    private fun key(code: Int, value: Int) {
        meta.onKey(code, value)
        val android = LinuxKeys.toAndroid(code)
        if (android == KeyEvent.KEYCODE_UNKNOWN) return
        // Ctrl+Esc gives the mouse and keyboard back to the phone (held keys are lifted first).
        if (android == KeyEvent.KEYCODE_ESCAPE && value == 1 && meta.meta and KeyEvent.META_CTRL_ON != 0) {
            sink.onReleasedByShortcut()
            return
        }
        // Volume keys keep working on the phone.
        if (android == KeyEvent.KEYCODE_VOLUME_UP || android == KeyEvent.KEYCODE_VOLUME_DOWN || android == KeyEvent.KEYCODE_VOLUME_MUTE) {
            if (value == 1) context.getSystemService(AudioManager::class.java).adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                when (android) { KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE; KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER; else -> AudioManager.ADJUST_TOGGLE_MUTE },
                AudioManager.FLAG_SHOW_UI,
            )
            return
        }
        val now = SystemClock.uptimeMillis()
        when (value) {
            1 -> {
                val target = sink.keyTarget()
                if (target < 0) return
                held.down(code, target, now)
                sink.injectKey(target, KeyEvent.ACTION_DOWN, android, meta.meta, 0, now, now, code, keyboardDeviceId)
            }
            2 -> {
                val h = held.get(code) ?: return
                sink.injectKey(h.display, KeyEvent.ACTION_DOWN, android, meta.meta, held.repeat(code), h.downAt, now, code, keyboardDeviceId)
            }
            0 -> {
                // The UP goes where its DOWN went, even if focus moved meanwhile.
                val h = held.up(code) ?: return
                sink.injectKey(h.display, KeyEvent.ACTION_UP, android, meta.meta, 0, h.downAt, now, code, keyboardDeviceId)
            }
        }
    }

    private companion object {
        const val TAG = "rawinput"
        const val LEFT = 1
        const val RIGHT = 2
        /** Mouse counts → trackpad pixels (the trackpad maps 0.05° per px). */
        const val MOUSE_GAIN = 1.2f
        /** Health check every N syncs (sync runs every 500 ms). */
        const val HEALTH_EVERY = 6
        const val RETRY_MS = 2_000L
    }
}
