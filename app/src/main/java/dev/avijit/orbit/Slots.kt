package dev.avijit.orbit

import dev.avijit.orbit.layout.ScreenLayout
import kotlin.math.abs

/** Slot bookkeeping. Slots keep their place in the arc (column and row from [ScreenLayout]). */
object Slots {
    private fun pos(slot: Int) = ScreenLayout.col(slot)
    private fun row(slot: Int) = ScreenLayout.row(slot)

    fun nextFree(open: Set<Int>): Int? = (0 until ScreenLayout.SLOTS).firstOrNull { it !in open }

    /** Nearest remaining screen to the one just closed (ties go to the more central one). */
    fun focusAfterClose(closed: Int, open: Set<Int>): Int? =
        open.minWithOrNull(compareBy<Int>({ abs(pos(it) - pos(closed)) + 2 * abs(row(it) - row(closed)) }, { abs(pos(it)) }, { row(it) }))

    /** Next open screen to the right (wrapping), or the most central one when nothing is focused. */
    fun nextInArc(current: Int?, open: Set<Int>): Int? {
        if (open.isEmpty()) return null
        val sorted = open.sortedWith(compareBy({ row(it) }, { pos(it) }))
        if (current == null || current !in open) return open.minWith(compareBy({ row(it) }, { abs(pos(it)) }))
        return sorted[(sorted.indexOf(current) + 1) % sorted.size]
    }
}
