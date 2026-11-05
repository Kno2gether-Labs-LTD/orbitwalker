package dev.avijit.orbit.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyRouterTest {
    @Test fun externalKeyWithFocus_forwards() =
        assertEquals(KeyRoute.FORWARD, KeyRouter.route(true, KeyEvent.KEYCODE_A, mouseCaptured = false, hasFocusedScreen = true))

    @Test fun noFocus_local() =
        assertEquals(KeyRoute.LOCAL, KeyRouter.route(true, KeyEvent.KEYCODE_A, mouseCaptured = false, hasFocusedScreen = false))

    @Test fun escWhileCaptured_releases() =
        assertEquals(KeyRoute.RELEASE_MOUSE, KeyRouter.route(true, KeyEvent.KEYCODE_ESCAPE, mouseCaptured = true, hasFocusedScreen = true))

    @Test fun phoneKeyboard_local() =
        assertEquals(KeyRoute.LOCAL, KeyRouter.route(false, KeyEvent.KEYCODE_A, mouseCaptured = false, hasFocusedScreen = true))

    @Test fun volumeKeys_stayLocal() =
        assertEquals(KeyRoute.LOCAL, KeyRouter.route(true, KeyEvent.KEYCODE_VOLUME_UP, mouseCaptured = false, hasFocusedScreen = true))
}
