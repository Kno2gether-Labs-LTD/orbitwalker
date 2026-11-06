package dev.avijit.orbit.input

import android.view.KeyEvent

enum class GlobalShortcut { FIND_CURSOR, RECENTER, RELEASE }

/** Keyboard shortcuts OrbitWalker keeps for itself; every other key goes to the focused app. */
object GlobalShortcuts {
    fun match(keyCode: Int, meta: Int): GlobalShortcut? {
        val ctrl = meta and KeyEvent.META_CTRL_ON != 0
        val alt = meta and KeyEvent.META_ALT_ON != 0
        return when {
            ctrl && keyCode == KeyEvent.KEYCODE_ESCAPE -> GlobalShortcut.RELEASE
            ctrl && alt && keyCode == KeyEvent.KEYCODE_C -> GlobalShortcut.FIND_CURSOR
            ctrl && alt && keyCode == KeyEvent.KEYCODE_R -> GlobalShortcut.RECENTER
            else -> null
        }
    }
}
