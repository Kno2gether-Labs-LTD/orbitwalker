package dev.avijit.orbit.input

import android.view.KeyEvent

enum class KeyRoute { FORWARD, RELEASE_MOUSE, LOCAL }

/** Decides where a hardware key goes: into the focused glasses screen, or stays on the phone. */
object KeyRouter {
    private val PHONE_KEYS = setOf(
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH,
    )

    fun route(isExternalKeyboard: Boolean, keyCode: Int, mouseCaptured: Boolean, hasFocusedScreen: Boolean): KeyRoute = when {
        mouseCaptured && keyCode == KeyEvent.KEYCODE_ESCAPE -> KeyRoute.RELEASE_MOUSE
        !isExternalKeyboard || !hasFocusedScreen || keyCode in PHONE_KEYS -> KeyRoute.LOCAL
        else -> KeyRoute.FORWARD
    }
}
