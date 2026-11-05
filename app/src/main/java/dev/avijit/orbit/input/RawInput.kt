package dev.avijit.orbit.input

import android.view.KeyEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One Linux `struct input_event` (64-bit layout: 16-byte timeval, u16 type, u16 code, s32 value). */
data class Evdev(val type: Int, val code: Int, val value: Int)

object EvdevParser {
    const val SIZE = 24
    const val EV_SYN = 0
    const val EV_KEY = 1
    const val EV_REL = 2

    fun parse(buf: ByteArray, offset: Int): Evdev {
        val b = ByteBuffer.wrap(buf, offset, SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.position(offset + 16)
        val type = b.short.toInt() and 0xFFFF
        val code = b.short.toInt() and 0xFFFF
        return Evdev(type, code, b.int)
    }
}

/** A mouse report between two SYN events. [buttons]: bit 0 left, bit 1 right, bit 2 middle. */
data class MouseReport(val dx: Int, val dy: Int, val buttons: Int, val wheel: Int)

/** Accumulates REL/BTN events and emits one report per EV_SYN. */
class MouseFrame {
    private var dx = 0
    private var dy = 0
    private var wheel = 0
    private var buttons = 0
    private var dirty = false

    fun feed(type: Int, code: Int, value: Int): MouseReport? {
        when (type) {
            EvdevParser.EV_REL -> {
                when (code) { REL_X -> dx += value; REL_Y -> dy += value; REL_WHEEL -> wheel += value }
                dirty = true
            }
            EvdevParser.EV_KEY -> {
                val bit = when (code) { BTN_LEFT -> 1; BTN_RIGHT -> 2; BTN_MIDDLE -> 4; else -> 0 }
                if (bit != 0) {
                    buttons = if (value != 0) buttons or bit else buttons and bit.inv()
                    dirty = true
                }
            }
            EvdevParser.EV_SYN -> if (dirty) {
                val r = MouseReport(dx, dy, buttons, wheel)
                dx = 0; dy = 0; wheel = 0; dirty = false
                return r
            }
        }
        return null
    }

    companion object {
        const val REL_X = 0
        const val REL_Y = 1
        const val REL_WHEEL = 8
        const val BTN_LEFT = 0x110
        const val BTN_RIGHT = 0x111
        const val BTN_MIDDLE = 0x112
    }
}

/** Linux input-event-codes → Android key codes (Android's Generic.kl, the common keys). */
object LinuxKeys {
    private val map: Map<Int, Int> = buildMap {
        put(1, KeyEvent.KEYCODE_ESCAPE)
        val digits = intArrayOf(KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_5,
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_0)
        digits.forEachIndexed { i, k -> put(2 + i, k) }
        put(12, KeyEvent.KEYCODE_MINUS); put(13, KeyEvent.KEYCODE_EQUALS); put(14, KeyEvent.KEYCODE_DEL); put(15, KeyEvent.KEYCODE_TAB)
        "QWERTYUIOP".forEachIndexed { i, c -> put(16 + i, KeyEvent.KEYCODE_A + (c - 'A')) }
        put(26, KeyEvent.KEYCODE_LEFT_BRACKET); put(27, KeyEvent.KEYCODE_RIGHT_BRACKET); put(28, KeyEvent.KEYCODE_ENTER)
        put(29, KeyEvent.KEYCODE_CTRL_LEFT)
        "ASDFGHJKL".forEachIndexed { i, c -> put(30 + i, KeyEvent.KEYCODE_A + (c - 'A')) }
        put(39, KeyEvent.KEYCODE_SEMICOLON); put(40, KeyEvent.KEYCODE_APOSTROPHE); put(41, KeyEvent.KEYCODE_GRAVE)
        put(42, KeyEvent.KEYCODE_SHIFT_LEFT); put(43, KeyEvent.KEYCODE_BACKSLASH)
        "ZXCVBNM".forEachIndexed { i, c -> put(44 + i, KeyEvent.KEYCODE_A + (c - 'A')) }
        put(51, KeyEvent.KEYCODE_COMMA); put(52, KeyEvent.KEYCODE_PERIOD); put(53, KeyEvent.KEYCODE_SLASH)
        put(54, KeyEvent.KEYCODE_SHIFT_RIGHT); put(55, KeyEvent.KEYCODE_NUMPAD_MULTIPLY); put(56, KeyEvent.KEYCODE_ALT_LEFT)
        put(57, KeyEvent.KEYCODE_SPACE); put(58, KeyEvent.KEYCODE_CAPS_LOCK)
        for (i in 0 until 10) put(59 + i, KeyEvent.KEYCODE_F1 + i)
        put(69, KeyEvent.KEYCODE_NUM_LOCK); put(70, KeyEvent.KEYCODE_SCROLL_LOCK)
        put(71, KeyEvent.KEYCODE_NUMPAD_7); put(72, KeyEvent.KEYCODE_NUMPAD_8); put(73, KeyEvent.KEYCODE_NUMPAD_9)
        put(74, KeyEvent.KEYCODE_NUMPAD_SUBTRACT); put(75, KeyEvent.KEYCODE_NUMPAD_4); put(76, KeyEvent.KEYCODE_NUMPAD_5)
        put(77, KeyEvent.KEYCODE_NUMPAD_6); put(78, KeyEvent.KEYCODE_NUMPAD_ADD); put(79, KeyEvent.KEYCODE_NUMPAD_1)
        put(80, KeyEvent.KEYCODE_NUMPAD_2); put(81, KeyEvent.KEYCODE_NUMPAD_3); put(82, KeyEvent.KEYCODE_NUMPAD_0)
        put(83, KeyEvent.KEYCODE_NUMPAD_DOT); put(86, KeyEvent.KEYCODE_BACKSLASH)
        put(87, KeyEvent.KEYCODE_F11); put(88, KeyEvent.KEYCODE_F12)
        put(96, KeyEvent.KEYCODE_NUMPAD_ENTER); put(97, KeyEvent.KEYCODE_CTRL_RIGHT); put(98, KeyEvent.KEYCODE_NUMPAD_DIVIDE)
        put(99, KeyEvent.KEYCODE_SYSRQ); put(100, KeyEvent.KEYCODE_ALT_RIGHT)
        put(102, KeyEvent.KEYCODE_MOVE_HOME); put(103, KeyEvent.KEYCODE_DPAD_UP); put(104, KeyEvent.KEYCODE_PAGE_UP)
        put(105, KeyEvent.KEYCODE_DPAD_LEFT); put(106, KeyEvent.KEYCODE_DPAD_RIGHT); put(107, KeyEvent.KEYCODE_MOVE_END)
        put(108, KeyEvent.KEYCODE_DPAD_DOWN); put(109, KeyEvent.KEYCODE_PAGE_DOWN); put(110, KeyEvent.KEYCODE_INSERT)
        put(111, KeyEvent.KEYCODE_FORWARD_DEL); put(113, KeyEvent.KEYCODE_VOLUME_MUTE); put(114, KeyEvent.KEYCODE_VOLUME_DOWN)
        put(115, KeyEvent.KEYCODE_VOLUME_UP); put(119, KeyEvent.KEYCODE_BREAK)
        // 125/126 (Meta) only act as modifiers (MetaTracker); injecting META/HOME triggers system actions.
        put(127, KeyEvent.KEYCODE_MENU)
        put(163, KeyEvent.KEYCODE_MEDIA_NEXT); put(164, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        put(165, KeyEvent.KEYCODE_MEDIA_PREVIOUS); put(166, KeyEvent.KEYCODE_MEDIA_STOP)
        put(158, KeyEvent.KEYCODE_BACK)
    }

    fun toAndroid(linux: Int): Int = map[linux] ?: KeyEvent.KEYCODE_UNKNOWN

    private val reverse: Map<Int, Int> by lazy { map.entries.associate { (l, a) -> a to l } }

    /** Android key code → Linux scan code (0 if unknown), so injected keys look like a real keyboard's. */
    fun toLinux(android: Int): Int = reverse[android] ?: 0
}

/** Modifier state from raw key events (shift/ctrl/alt/meta and caps lock). */
class MetaTracker {
    private var held = 0
    private var caps = false

    /** Forget held modifiers (after we stop seeing the keyboard); caps lock is kept. */
    fun reset() {
        held = 0
    }
    val meta: Int get() = held or (if (caps) KeyEvent.META_CAPS_LOCK_ON else 0)

    fun onKey(linux: Int, value: Int) {
        val bits = when (linux) {
            42 -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            54 -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_RIGHT_ON
            29 -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            97 -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_RIGHT_ON
            56 -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            100 -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON
            125 -> KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
            126 -> KeyEvent.META_META_ON or KeyEvent.META_META_RIGHT_ON
            58 -> { if (value == 1) caps = !caps; return }
            else -> return
        }
        // Recompute so releasing one shift keeps SHIFT_ON while the other is still held.
        held = if (value != 0) held or bits else held and bits.inv()
        if (held and (KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_SHIFT_RIGHT_ON) != 0) held = held or KeyEvent.META_SHIFT_ON
        if (held and (KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_CTRL_RIGHT_ON) != 0) held = held or KeyEvent.META_CTRL_ON
        if (held and (KeyEvent.META_ALT_LEFT_ON or KeyEvent.META_ALT_RIGHT_ON) != 0) held = held or KeyEvent.META_ALT_ON
        if (held and (KeyEvent.META_META_LEFT_ON or KeyEvent.META_META_RIGHT_ON) != 0) held = held or KeyEvent.META_META_ON
    }
}

/** Keys currently held: where their DOWN went (so the UP goes to the same screen) and repeat counts. */
class HeldKeys {
    class Held(val code: Int, val display: Int, val downAt: Long, var repeats: Int = 0)

    private val held = HashMap<Int, Held>()

    fun down(code: Int, display: Int, at: Long) { held[code] = Held(code, display, at) }
    fun repeat(code: Int): Int = held[code]?.let { ++it.repeats } ?: 0
    fun get(code: Int): Held? = held[code]
    fun up(code: Int): Held? = held.remove(code)

    /** Everything still held (to send UPs for), and forget it. */
    fun releaseAll(): List<Held> = held.values.toList().also { held.clear() }
}

/** One input device as described by `getevent -i`. */
data class InputDeviceInfo(val path: String, val name: String, val bus: Int, val keys: Set<Int>, val rels: Set<Int>) {
    /** Relative X/Y plus a left button. */
    val isMouse: Boolean get() = 0 in rels && 1 in rels && 0x110 in keys
    /** Letters A and Z plus Enter. */
    val isKeyboard: Boolean get() = 30 in keys && 44 in keys && 28 in keys
}

/**
 * Parses `getevent -i`. The shell user can't read /sys/class/input (SELinux), but `getevent`
 * queries devices with ioctls that are allowed, so this is how the helper finds mice and keyboards.
 */
object GeteventParser {
    fun parse(text: String): List<InputDeviceInfo> {
        val out = mutableListOf<InputDeviceInfo>()
        var path: String? = null
        var name = ""
        var bus = -1
        val keys = mutableSetOf<Int>()
        val rels = mutableSetOf<Int>()
        var section = ""
        fun flush() {
            path?.let { out += InputDeviceInfo(it, name, bus, keys.toSet(), rels.toSet()) }
            path = null; name = ""; bus = -1; keys.clear(); rels.clear(); section = ""
        }
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("add device") -> { flush(); path = line.substringAfter(": ").trim() }
                line.startsWith("bus:") -> bus = line.substringAfter(":").trim().toIntOrNull(16) ?: -1
                line.startsWith("name:") -> name = line.substringAfter(":").trim().trim('"')
                line.startsWith("input props") -> section = ""
                line.matches(Regex("^[A-Z]+ \\([0-9a-f]{4}\\):.*")) -> {
                    section = line.substringBefore(" ")
                    codes(line.substringAfter("):"))
                        .forEach { if (section == "KEY") keys += it else if (section == "REL") rels += it }
                }
                section == "KEY" || section == "REL" -> codes(line).forEach { if (section == "KEY") keys += it else rels += it }
            }
        }
        flush()
        // Only real event nodes, and never a path listed twice (a forged block inside a device name).
        val counts = out.groupingBy { it.path }.eachCount()
        return out.filter { EVENT_NODE.matches(it.path) && counts[it.path] == 1 }
    }

    private val EVENT_NODE = Regex("^/dev/input/event\\d+$")

    /**
     * Parses `getevent -i <path>` (one device). Returns null unless the output describes exactly
     * one device at [path], so a name containing a fake device block is rejected.
     */
    fun parseSingle(text: String, path: String): InputDeviceInfo? {
        val headers = text.lineSequence().count { it.trim().startsWith("add device") }
        if (headers != 1) return null
        return parse(text).singleOrNull()?.takeIf { it.path == path }
    }

    /** Hex codes on a line; for ABS-style lines only the code before ':' counts. */
    private fun codes(s: String): List<Int> =
        s.substringBefore(" :").trim().split(Regex("\\s+")).mapNotNull { if (it.length == 4) it.toIntOrNull(16) else null }
}
