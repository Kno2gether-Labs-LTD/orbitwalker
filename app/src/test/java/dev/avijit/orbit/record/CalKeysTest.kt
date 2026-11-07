package dev.avijit.orbit.record

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class CalKeysTest {
    @Test fun keysMapToCalibrationSteps() {
        assertEquals(CalKey.LEFT, CalKey.of(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(CalKey.FORWARD, CalKey.of(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(CalKey.BACK, CalKey.of(KeyEvent.KEYCODE_MINUS))
        assertEquals(CalKey.ZOOM_IN, CalKey.of(KeyEvent.KEYCODE_RIGHT_BRACKET))
        assertEquals(CalKey.DONE, CalKey.of(KeyEvent.KEYCODE_ENTER))
        assertEquals(CalKey.SPOT_2, CalKey.of(KeyEvent.KEYCODE_2))
        assertEquals(null, CalKey.of(KeyEvent.KEYCODE_Q))
    }

    @Test fun spotKeysPickPresets() {
        val p = Calibration.apply(CameraSpot.RIGHT_SHOULDER.preset(), CalKey.SPOT_3, false)!!
        assertEquals(CameraSpot.entries[2].preset(), p)
    }
}
