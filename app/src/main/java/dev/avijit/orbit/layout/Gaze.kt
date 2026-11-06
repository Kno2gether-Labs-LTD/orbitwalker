package dev.avijit.orbit.layout

import dev.avijit.orbit.render.AnchorMode
import dev.avijit.orbit.tracking.Quat

object Gaze {
    /** Cursor yaw/pitch (degrees) that puts it in the middle of the view for this head pose. */
    fun cursorAngles(head: Quat, mode: AnchorMode): Pair<Float, Float> {
        if (mode == AnchorMode.FOLLOW) return 0f to 0f
        val ypr = head.yawPitchRollDeg()
        return ypr[0] to ypr[1]
    }
}
