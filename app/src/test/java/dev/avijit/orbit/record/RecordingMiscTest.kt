package dev.avijit.orbit.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class RecordingMiscTest {
    @Test fun fileNames_sortByTime_andSayWhichView() {
        val c = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 7, 10, 5, 9) }
        assertEquals("OrbitWalker 2026-11-07 10-05-09 glasses.mp4", RecordingNames.file(c.time, RecordingView.GLASSES))
        assertEquals("OrbitWalker 2026-11-07 10-05-09 mixed reality.mp4", RecordingNames.file(c.time, RecordingView.MIXED))
    }

    @Test fun throttle_keeps30fpsFrom60() {
        val t = FrameThrottle(fps = 30)
        var n = 0
        for (i in 0 until 120) if (t.take(i * 16_666_667L)) n++ // 2 s at 60 Hz
        assertEquals(60f, n.toFloat(), 2f)
    }

    @Test fun throttle_neverRunsAhead() {
        val t = FrameThrottle(fps = 30)
        assertTrue(t.take(0))
        assertFalse(t.take(10_000_000L))
        assertTrue(t.take(34_000_000L))
    }

    @Test fun elapsedLabel() {
        assertEquals("0:07", RecordingNames.elapsed(7_400))
        assertEquals("12:03", RecordingNames.elapsed(723_000))
        assertEquals("1:02:03", RecordingNames.elapsed(3_723_000))
    }
}
