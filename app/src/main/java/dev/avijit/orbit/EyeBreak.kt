package dev.avijit.orbit

/**
 * Eye-care reminders while the glasses are worn: every [intervalMin] minutes a short "look far
 * away for 20 seconds" (the 20-20-20 habit), and after 90 minutes a suggestion to take the glasses
 * off for a while, repeated hourly. Taking them off for 5+ minutes starts a fresh session.
 * 0 = off. Never interrupts video: due reminders wait until playback stops.
 * Pure: the caller ticks it with the clock, whether the glasses stream, and whether media plays.
 */
class EyeBreak(var intervalMin: Int) {
    enum class Reminder { LOOK_AWAY, TAKE_OFF }

    private var sessionStart = -1L
    /** When the glasses were last seen off (-1 = worn since). */
    private var offSince = -1L
    private var nextShort = 0L
    private var nextLong = 0L

    /** A reminder that came due while a video was playing; shown once playback stops. */
    private var held: Reminder? = null

    /**
     * Call about once a second. [busy] = Cinema or a video/music app is playing: a due reminder is
     * held (never interrupts a film) and shown as soon as playback stops.
     */
    fun tick(nowMs: Long, wearing: Boolean, busy: Boolean = false): Reminder? {
        val due = due(nowMs, wearing)
        if (!wearing) { held = null; return null }
        if (due != null && held != Reminder.TAKE_OFF) held = due
        if (busy) return null
        return held.also { held = null }
    }

    private fun due(nowMs: Long, wearing: Boolean): Reminder? {
        if (intervalMin <= 0) return null
        if (!wearing) {
            if (offSince < 0) offSince = nowMs
            return null
        }
        val rested = offSince >= 0 && nowMs - offSince >= RESET_MS
        offSince = -1L
        if (sessionStart < 0 || rested) {
            sessionStart = nowMs
            nextShort = nowMs + intervalMin * MIN
            nextLong = nowMs + LONG_FIRST_MS
        }
        val short = nowMs >= nextShort
        while (nextShort <= nowMs) nextShort += intervalMin * MIN
        if (nowMs >= nextLong) {
            while (nextLong <= nowMs) nextLong += LONG_REPEAT_MS
            return Reminder.TAKE_OFF
        }
        return if (short) Reminder.LOOK_AWAY else null
    }

    private companion object {
        const val MIN = 60_000L
        const val RESET_MS = 5 * MIN
        const val LONG_FIRST_MS = 90 * MIN
        const val LONG_REPEAT_MS = 60 * MIN
    }
}
