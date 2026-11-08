package dev.avijit.orbit.record

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** [CLEAN]: just the screen you're using, flat and full size, for pinning onto a wall in a video editor. */
enum class RecordingView(val suffix: String) { GLASSES("glasses"), MIXED("mixed reality"), CLEAN("clean screen") }

object RecordingNames {
    fun file(start: Date, view: RecordingView): String =
        "OrbitWalker ${SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.ROOT).format(start)} ${view.suffix}.mp4"

    fun elapsed(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600; val m = (s / 60) % 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, sec) else "%d:%02d".format(Locale.ROOT, m, sec)
    }
}

/** Records at a steady [fps] from a faster render loop. */
class FrameThrottle(fps: Int) {
    private val interval = 1_000_000_000L / fps
    private var next = Long.MIN_VALUE

    fun take(nowNs: Long): Boolean {
        if (next != Long.MIN_VALUE && nowNs < next - SLACK_NS) return false
        next = if (next == Long.MIN_VALUE || nowNs - next > interval) nowNs + interval else next + interval
        return true
    }

    private companion object { const val SLACK_NS = 2_000_000L }
}

/**
 * MediaMuxer bookkeeping: it can only start once every track's format is known, and the file must
 * begin with a video key frame (audio before it is dropped so picture and sound start together).
 */
class MuxGate(private val tracks: Int) {
    private var added = 0
    private var sawKeyFrame = false
    var started = false
        private set

    /** A track's format is known; true when the muxer should start now. */
    fun addTrack(): Boolean {
        added++
        if (added == tracks) started = true
        return started && added == tracks
    }

    fun shouldWrite(video: Boolean, keyFrame: Boolean): Boolean {
        if (!started) return false
        if (!sawKeyFrame) {
            if (video && keyFrame) sawKeyFrame = true
            return sawKeyFrame
        }
        return true
    }
}

/** What's behind you in the mixed reality view. */
enum class MixedBackground(val label: String) {
    REAL("Real room"),
    VIRTUAL("Virtual space"),
}
