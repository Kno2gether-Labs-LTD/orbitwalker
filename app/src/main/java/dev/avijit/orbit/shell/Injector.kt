package dev.avijit.orbit.shell

import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.lang.reflect.Method

/**
 * Injects input into a specific display via the hidden InputManager API, falling back to the
 * `input -d` shell command. Never throws; [lastError] holds the reason for the last fallback.
 */
class Injector {
    @Volatile var lastError: String? = null
        private set

    private val inject: Pair<Any, Method>? by lazy {
        try {
            val manager: Any = if (Build.VERSION.SDK_INT >= 34) {
                Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)!!
            } else {
                Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)!!
            }
            manager to manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        } catch (t: Throwable) {
            lastError = "injectInputEvent unavailable: ${t.describe()}"
            null
        }
    }

    fun tap(displayId: Int, x: Float, y: Float): Boolean {
        val down = SystemClock.uptimeMillis()
        if (!send(displayId, touch(down, down, MotionEvent.ACTION_DOWN, x, y))) {
            return shell("input", "-d", "$displayId", "tap", "${x.toInt()}", "${y.toInt()}")
        }
        SystemClock.sleep(TAP_HOLD_MS)
        return send(displayId, touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y))
    }

    fun scroll(displayId: Int, x: Float, y: Float, vscroll: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y
            setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll)
        })
        val e = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        return send(displayId, e) || shell(*scrollCommand(displayId, x, y, vscroll).toTypedArray())
    }

    fun key(displayId: Int, keyCode: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val ok = send(displayId, KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)) &&
            send(displayId, KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD))
        return ok || shell("input", "-d", "$displayId", "keyevent", "$keyCode")
    }

    /** One raw key event (Bluetooth keyboard passthrough); modifiers come from [meta]. */
    fun keyEvent(
        displayId: Int, action: Int, keyCode: Int, meta: Int, repeat: Int,
        downTime: Long = 0, eventTime: Long = 0, scanCode: Int = 0, deviceId: Int = KeyCharacterMap.VIRTUAL_KEYBOARD,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = if (downTime > 0) downTime else now
        val at = if (eventTime > 0) eventTime else now
        return send(displayId, KeyEvent(down, at, action, keyCode, repeat, meta, deviceId, scanCode, 0, InputDevice.SOURCE_KEYBOARD))
    }

    /** One touch event (press/drag/release from a mouse). */
    fun touchEvent(displayId: Int, action: Int, x: Float, y: Float, downTime: Long): Boolean {
        val now = SystemClock.uptimeMillis()
        return send(displayId, touch(if (downTime > 0) downTime else now, now, action, x, y))
    }

    /** Types [text] as key events from the virtual keyboard map; falls back to `input text`. */
    fun text(displayId: Int, text: String): Boolean {
        if (text.isEmpty()) return true
        val events = runCatching { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray()) }.getOrNull()
        if (events != null && events.all { send(displayId, it) }) return true
        // `input text` treats spaces as separators: %s is its escape for a space.
        return shell("input", "-d", "$displayId", "text", text.replace(" ", "%s"), redactLast = true)
    }

    private fun touch(downTime: Long, time: Long, action: Int, x: Float, y: Float): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        return MotionEvent.obtain(downTime, time, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    private fun send(displayId: Int, e: InputEvent): Boolean {
        val (manager, method) = inject ?: return false
        return try {
            e.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(e, displayId)
            method.invoke(manager, e, INJECT_MODE_ASYNC) as Boolean
        } catch (t: Throwable) {
            lastError = "inject failed: ${t.describe()}"
            false
        } finally {
            if (e is MotionEvent) e.recycle()
        }
    }

    /**
     * Runs a shell command. With [redactLast] the final argument (typed text, which may be a
     * password) never reaches [lastError], the status line or Share logs.
     */
    private fun shell(vararg cmd: String, redactLast: Boolean = false): Boolean = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        val shown = if (redactLast) cmd.dropLast(1) + "<${cmd.last().length} chars>" else cmd.toList()
        val detail = if (redactLast) "" else ": ${out.take(300)}"
        if (code != 0) lastError = listOfNotNull(lastError, "${shown.joinToString(" ")} → exit $code$detail").joinToString("; then ")
        code == 0
    } catch (t: Throwable) {
        lastError = listOfNotNull(lastError, "${cmd.first()} failed: ${t.describe()}").joinToString("; then ")
        false
    }

    /** Clears the error before a new call so failures are never stale. */
    fun clearError() {
        lastError = null
    }

    companion object {
        /** `input [source] [-d id] command`: the source must come before the display. */
        fun scrollCommand(displayId: Int, x: Float, y: Float, vscroll: Float): List<String> =
            listOf("input", "mouse", "-d", "$displayId", "scroll", "${x.toInt()}", "${y.toInt()}", "--axis", "VSCROLL,$vscroll")

        private const val INJECT_MODE_ASYNC = 0
        private const val TAP_HOLD_MS = 40L
    }
}

internal fun Throwable.describe(): String {
    val root = (this as? java.lang.reflect.InvocationTargetException)?.targetException ?: this
    return "${root.javaClass.simpleName}: ${root.message}"
}
