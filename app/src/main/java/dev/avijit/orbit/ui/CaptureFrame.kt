package dev.avijit.orbit.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Root view that receives captured (relative) mouse events. Captured pointer events are
 * dispatched along the focus chain; a ViewGroup only calls its own listener when it is the
 * focused view itself, so we intercept here, above the focused Compose view.
 */
@SuppressLint("ViewConstructor")
class CaptureFrame(context: Context, private val onCaptured: (MotionEvent) -> Boolean) : FrameLayout(context) {
    override fun dispatchCapturedPointerEvent(event: MotionEvent): Boolean = onCaptured(event)
}
