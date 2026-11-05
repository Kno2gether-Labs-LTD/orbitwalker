package dev.avijit.orbit.input

/** Collects trackpad scroll pixels and releases them as whole notches (one injected event each batch). */
class ScrollAccumulator(private val pxPerNotch: Float = 40f) {
    private var carry = 0f

    /** Adds [dy] pixels; returns the whole notches now due (sign = direction). */
    @Synchronized
    fun add(dy: Float): Int {
        carry += dy
        val notches = (carry / pxPerNotch).toInt()
        carry -= notches * pxPerNotch
        return notches
    }
}
