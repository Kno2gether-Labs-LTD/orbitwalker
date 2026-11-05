package dev.avijit.orbit.input

import android.view.MotionEvent

sealed interface MouseOut {
    data class Move(val dx: Float, val dy: Float) : MouseOut
    data object Click : MouseOut
    data object Back : MouseOut
    data class Scroll(val notches: Int) : MouseOut
}

/** Captured (relative) Bluetooth-mouse events → pointer moves, clicks, Back and scroll notches. */
class MouseInterpreter(private val gain: Float = 1f) {
    private var buttons = 0
    private var wheel = 0f

    fun onCaptured(dx: Float, dy: Float, buttonState: Int, vscroll: Float): List<MouseOut> {
        val out = mutableListOf<MouseOut>()
        if (dx != 0f || dy != 0f) out += MouseOut.Move(dx * gain, dy * gain)
        val released = buttons and buttonState.inv()
        if (released and MotionEvent.BUTTON_SECONDARY != 0) out += MouseOut.Back
        else if (released and MotionEvent.BUTTON_PRIMARY != 0) out += MouseOut.Click
        buttons = buttonState
        wheel += vscroll
        val notches = wheel.toInt()
        if (notches != 0) {
            wheel -= notches
            out += MouseOut.Scroll(notches)
        }
        return out
    }
}
