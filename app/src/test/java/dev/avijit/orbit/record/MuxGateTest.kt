package dev.avijit.orbit.record

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MuxGateTest {
    @Test fun startsOnlyOnceEveryTrackIsKnown() {
        val g = MuxGate(tracks = 2)
        assertFalse(g.addTrack())
        assertTrue(g.addTrack()) // now start the muxer
        assertTrue(g.started)
    }

    @Test fun beforeStart_nothingIsWritten() {
        val g = MuxGate(tracks = 2)
        g.addTrack()
        assertFalse(g.shouldWrite(video = true, keyFrame = true))
    }

    @Test fun firstSampleIsAVideoKeyFrame_audioWaitsForIt() {
        val g = MuxGate(tracks = 2); g.addTrack(); g.addTrack()
        assertFalse(g.shouldWrite(video = false, keyFrame = false))
        assertFalse(g.shouldWrite(video = true, keyFrame = false))
        assertTrue(g.shouldWrite(video = true, keyFrame = true))
        assertTrue(g.shouldWrite(video = false, keyFrame = false))
        assertTrue(g.shouldWrite(video = true, keyFrame = false))
    }

    @Test fun videoOnly_works() {
        val g = MuxGate(tracks = 1)
        assertTrue(g.addTrack())
        assertTrue(g.shouldWrite(video = true, keyFrame = true))
    }
}
