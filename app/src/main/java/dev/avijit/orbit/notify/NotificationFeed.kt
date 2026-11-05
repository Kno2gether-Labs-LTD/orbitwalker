package dev.avijit.orbit.notify

import android.graphics.Bitmap
import android.os.SystemClock

/** One notification card shown in the glasses. [art] is the pre-rendered card. */
class NotificationCard(val key: String, val art: Bitmap, val postedMs: Long, val showMs: Long = NotificationFeed.SHOW_MS)

/** Recent notifications for the glasses (newest last). Thread-safe; contents are never logged. */
object NotificationFeed {
    const val SHOW_MS = 5_000L
    const val FADE_MS = 600L
    private const val MAX = 3

    @Volatile var enabled = true
    @Volatile var hideText = false
    @Volatile var cards: List<NotificationCard> = emptyList()
        private set

    @Synchronized
    fun post(key: String, art: Bitmap) {
        if (!enabled) return
        cards = (cards.filter { it.key != key } + NotificationCard(key, art, SystemClock.uptimeMillis())).takeLast(MAX)
    }

    /** OrbitWalker's own cards (eye breaks): shown even when notification cards are off. */
    @Synchronized
    fun postSystem(key: String, art: Bitmap, showMs: Long) {
        cards = (cards.filter { it.key != key } + NotificationCard(key, art, SystemClock.uptimeMillis(), showMs)).takeLast(MAX)
    }

    /** Cards still on screen at [nowMs]. */
    fun visible(nowMs: Long): List<NotificationCard> = cards.filter { nowMs - it.postedMs < it.showMs + FADE_MS }
}
