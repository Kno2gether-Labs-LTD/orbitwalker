package dev.avijit.orbit.layout

import kotlin.math.atan2

/**
 * The title bar above the focused screen, for arranging screens from the glasses with a mouse:
 * drag the handle to move the screen (release to leave it there), buttons to resize, push/pull,
 * reset and close; the wheel over the bar also pushes/pulls. Pure: placement, hit areas, effects.
 */
object WindowBar {
    enum class Button { MOVE, SMALLER, BIGGER, CLOSER, FURTHER, RESET, CLOSE }

    /** Buttons on the right, left to right; the rest of the bar is the drag handle. */
    val BUTTONS = listOf(Button.SMALLER, Button.BIGGER, Button.CLOSER, Button.FURTHER, Button.RESET, Button.CLOSE)

    /** Bar width / height. */
    const val ASPECT = 22f
    /** One button's width in bar heights. */
    const val BUTTON_W = 1.5f
    private const val GAP_M = 0.012f

    fun pose(screen: ScreenPose): ScreenPose {
        val h = screen.widthM / ASPECT
        val rise = Math.toDegrees(atan2((screen.heightM / 2 + h / 2 + GAP_M).toDouble(), screen.distanceM.toDouble())).toFloat()
        return ScreenPose(screen.yawDeg, screen.pitchDeg + rise, screen.distanceM - 0.005f, screen.widthM, h)
    }

    /** u = 0..1 across the bar. */
    fun buttonAt(u: Float): Button {
        val buttonU = BUTTON_W / ASPECT
        val firstButton = 1f - BUTTONS.size * buttonU
        if (u < firstButton) return Button.MOVE
        val i = ((u - firstButton) / buttonU).toInt().coerceIn(0, BUTTONS.size - 1)
        return BUTTONS[i]
    }

    /** Effect of a button click on the screen's offset (clamping is the caller's job). */
    fun apply(b: Button, o: SlotOffset): SlotOffset = when (b) {
        Button.SMALLER -> o.copy(scale = o.scale / SCALE_STEP)
        Button.BIGGER -> o.copy(scale = o.scale * SCALE_STEP)
        Button.CLOSER -> o.copy(distanceM = o.distanceM - DISTANCE_STEP_M)
        Button.FURTHER -> o.copy(distanceM = o.distanceM + DISTANCE_STEP_M)
        Button.RESET -> SlotOffset()
        Button.MOVE, Button.CLOSE -> o
    }

    /** Dragging the handle: the screen follows the pointer's angular movement since the press. */
    fun dragged(start: SlotOffset, startYaw: Float, startPitch: Float, yaw: Float, pitch: Float): SlotOffset =
        start.copy(yawDeg = start.yawDeg + (yaw - startYaw), pitchDeg = start.pitchDeg + (pitch - startPitch))

    const val SCALE_STEP = 1.1f
    const val DISTANCE_STEP_M = 0.1f
}
