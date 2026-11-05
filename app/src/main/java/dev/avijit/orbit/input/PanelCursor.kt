package dev.avijit.orbit.input

/** Cursor position in a panel's pixel space; always stays on the panel. */
class PanelCursor(val widthPx: Int, val heightPx: Int) {
    @Volatile var x: Float = widthPx / 2f
        private set
    @Volatile var y: Float = heightPx / 2f
        private set

    fun moveBy(dx: Float, dy: Float) {
        x = (x + dx).coerceIn(0f, (widthPx - 1).toFloat())
        y = (y + dy).coerceIn(0f, (heightPx - 1).toFloat())
    }

    /** (0,0) = top-left, (1,1) = bottom-right. */
    fun uv(): Pair<Float, Float> = x / (widthPx - 1) to y / (heightPx - 1)
}
