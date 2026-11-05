package dev.avijit.orbit.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class StereoTest {
    @Test fun monoViewport_isWholeOutput() {
        assertArrayEquals(intArrayOf(0, 0, 3840, 1080), Stereo.viewport(Eye.MONO, 3840, 1080))
    }

    @Test fun sbsViewports_splitInHalves() {
        assertArrayEquals(intArrayOf(0, 0, 1920, 1080), Stereo.viewport(Eye.LEFT, 3840, 1080))
        assertArrayEquals(intArrayOf(1920, 0, 1920, 1080), Stereo.viewport(Eye.RIGHT, 3840, 1080))
    }

    @Test fun eyeOffset_isHalfIpdInViewSpace() {
        // The left eye sits to the left, so the world shifts right in its view: +ipd/2 on x.
        assertEquals(0.0315f, Stereo.eyeShiftX(Eye.LEFT, 0.063f), 1e-6f)
        assertEquals(-0.0315f, Stereo.eyeShiftX(Eye.RIGHT, 0.063f), 1e-6f)
        assertEquals(0f, Stereo.eyeShiftX(Eye.MONO, 0.063f), 1e-6f)
    }

    @Test fun contentUv_sideBySide() {
        // (offsetU, offsetV, scaleU, scaleV) in SurfaceTexture UV space (v = 0 at the bottom).
        assertArrayEquals(floatArrayOf(0f, 0f, 0.5f, 1f), Stereo.contentUv(ContentMode.SBS, Eye.LEFT), 1e-6f)
        assertArrayEquals(floatArrayOf(0.5f, 0f, 0.5f, 1f), Stereo.contentUv(ContentMode.SBS, Eye.RIGHT), 1e-6f)
    }

    @Test fun contentUv_topBottom_leftEyeIsTopHalf() {
        assertArrayEquals(floatArrayOf(0f, 0.5f, 1f, 0.5f), Stereo.contentUv(ContentMode.TB, Eye.LEFT), 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 0.5f), Stereo.contentUv(ContentMode.TB, Eye.RIGHT), 1e-6f)
    }

    @Test fun contentUv_topBottom_letterboxed16by9On16by10_cropsBars() {
        // A 16:9 video on a 16:10 screen leaves 5% bars top and bottom; each eye's half must exclude them.
        val bar = Stereo.letterbox(panelAspect = 1.6f, videoAspect = 16f / 9f)
        assertEquals(0.05f, bar, 1e-4f)
        assertArrayEquals(floatArrayOf(0f, 0.5f, 1f, 0.45f), Stereo.contentUv(ContentMode.TB, Eye.LEFT, bar), 1e-4f)
        assertArrayEquals(floatArrayOf(0f, 0.05f, 1f, 0.45f), Stereo.contentUv(ContentMode.TB, Eye.RIGHT, bar), 1e-4f)
    }

    @Test fun displayMode_3dRange() {
        assertEquals(true, Stereo.isSbsMode(0x37))
        assertEquals(true, Stereo.isSbsMode(0x3C))
        assertEquals(false, Stereo.isSbsMode(0x31))
        assertEquals(false, Stereo.isSbsMode(-1))
    }

    @Test fun contentUv_3dContentOnMonoDesk_showsLeftHalfOnly() {
        assertArrayEquals(floatArrayOf(0f, 0f, 0.5f, 1f), Stereo.contentUv(ContentMode.SBS, Eye.MONO), 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f), Stereo.contentUv(ContentMode.FLAT, Eye.LEFT), 1e-6f)
    }

    @Test fun perEyeAspect_isAlways16by9() {
        assertEquals(16f / 9f, Stereo.eyeAspect(), 1e-6f)
    }
}
