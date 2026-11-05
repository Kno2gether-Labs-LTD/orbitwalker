package dev.avijit.orbit.layout

import android.view.KeyEvent

/**
 * OrbitWalker's own keyboard, drawn in the glasses under the focused screen and clicked with the
 * pointer. Keys are sent as real key events, so it types anywhere (including password fields)
 * without depending on Android showing its keyboard on that screen.
 */
object VrKeyboard {
    enum class Page { LETTERS, SYMBOLS }
    enum class Action { TYPE, SHIFT, PAGE, HIDE }

    class Key(
        val label: String,
        val keyCode: Int = KeyEvent.KEYCODE_UNKNOWN,
        /** Needs Shift held (e.g. "@" is Shift+2). */
        val shift: Boolean = false,
        val weight: Float = 1f,
        val action: Action = Action.TYPE,
    )

    /** Rows top to bottom; v in [0,1) splits into 4 equal rows, u by key weights. */
    const val ROWS = 4
    /** Width : height of the keyboard panel. */
    const val ASPECT = 3.2f

    private fun letters(s: String) = s.map { c -> Key(c.toString(), KeyEvent.KEYCODE_A + (c - 'a')) }
    private fun sym(label: String, code: Int, shift: Boolean = false) = Key(label, code, shift)

    private val LETTERS: List<List<Key>> = listOf(
        letters("qwertyuiop"),
        listOf(Key("", weight = 0.5f)) + letters("asdfghjkl") + listOf(Key("", weight = 0.5f)),
        listOf(Key("⇧", weight = 1.5f, action = Action.SHIFT)) + letters("zxcvbnm") + listOf(Key("⌫", KeyEvent.KEYCODE_DEL, weight = 1.5f)),
        listOf(
            Key("?123", weight = 1.5f, action = Action.PAGE), Key(",", KeyEvent.KEYCODE_COMMA),
            Key("space", KeyEvent.KEYCODE_SPACE, weight = 4f), Key(".", KeyEvent.KEYCODE_PERIOD),
            Key("⏎", KeyEvent.KEYCODE_ENTER, weight = 1.5f), Key("hide", weight = 1f, action = Action.HIDE),
        ),
    )

    private val SYMBOLS: List<List<Key>> = listOf(
        "1234567890".map { c -> Key(c.toString(), if (c == '0') KeyEvent.KEYCODE_0 else KeyEvent.KEYCODE_1 + (c - '1')) },
        listOf(
            sym("@", KeyEvent.KEYCODE_2, true), sym("#", KeyEvent.KEYCODE_3, true), sym("$", KeyEvent.KEYCODE_4, true),
            sym("%", KeyEvent.KEYCODE_5, true), sym("&", KeyEvent.KEYCODE_7, true), sym("-", KeyEvent.KEYCODE_MINUS),
            sym("+", KeyEvent.KEYCODE_EQUALS, true), sym("(", KeyEvent.KEYCODE_9, true), sym(")", KeyEvent.KEYCODE_0, true),
            sym("/", KeyEvent.KEYCODE_SLASH),
        ),
        listOf(
            sym("!", KeyEvent.KEYCODE_1, true), sym("\"", KeyEvent.KEYCODE_APOSTROPHE, true), sym("'", KeyEvent.KEYCODE_APOSTROPHE),
            sym(":", KeyEvent.KEYCODE_SEMICOLON, true), sym(";", KeyEvent.KEYCODE_SEMICOLON), sym("*", KeyEvent.KEYCODE_8, true),
            sym("?", KeyEvent.KEYCODE_SLASH, true), sym("_", KeyEvent.KEYCODE_MINUS, true), sym("=", KeyEvent.KEYCODE_EQUALS),
            Key("⌫", KeyEvent.KEYCODE_DEL, weight = 1.5f),
        ),
        listOf(
            Key("ABC", weight = 1.5f, action = Action.PAGE), Key(",", KeyEvent.KEYCODE_COMMA),
            Key("space", KeyEvent.KEYCODE_SPACE, weight = 4f), Key(".", KeyEvent.KEYCODE_PERIOD),
            Key("⏎", KeyEvent.KEYCODE_ENTER, weight = 1.5f), Key("hide", weight = 1f, action = Action.HIDE),
        ),
    )

    fun rows(page: Page): List<List<Key>> = if (page == Page.LETTERS) LETTERS else SYMBOLS

    /** Key under (u, v) on the panel ((0,0) = top-left), or null outside / on a spacer. */
    fun keyAt(u: Float, v: Float, page: Page): Key? {
        if (u < 0f || u >= 1f || v < 0f || v >= 1f) return null
        val row = rows(page)[(v * ROWS).toInt().coerceIn(0, ROWS - 1)]
        val total = row.sumOf { it.weight.toDouble() }.toFloat()
        var x = 0f
        for (k in row) {
            x += k.weight / total
            if (u < x) return k.takeIf { it.label.isNotEmpty() }
        }
        return null
    }
}
