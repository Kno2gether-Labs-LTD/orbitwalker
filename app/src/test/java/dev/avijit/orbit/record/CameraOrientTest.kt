package dev.avijit.orbit.record

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraOrientTest {
    @Test fun backCamera_rotation() {
        assertEquals(90, CameraOrient.rotation(sensorDeg = 90, displayDeg = 0, front = false))   // phone upright
        assertEquals(0, CameraOrient.rotation(sensorDeg = 90, displayDeg = 90, front = false))   // landscape
        assertEquals(180, CameraOrient.rotation(sensorDeg = 90, displayDeg = 270, front = false))
    }

    @Test fun frontCamera_rotation() {
        assertEquals(270, CameraOrient.rotation(sensorDeg = 270, displayDeg = 0, front = true))
        assertEquals(0, CameraOrient.rotation(sensorDeg = 270, displayDeg = 90, front = true))
    }

    @Test fun uprightCorners_mapToTheRightSensorCorners() {
        // 90°: the sensor's left edge becomes the top of the upright picture.
        assertArrayEquals(floatArrayOf(0f, 0f), CameraOrient.sensorUv(0f, 1f, 90), 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 1f), CameraOrient.sensorUv(1f, 0f, 90), 1e-6f)
        assertArrayEquals(floatArrayOf(0.25f, 0.75f), CameraOrient.sensorUv(0.25f, 0.75f, 0), 1e-6f)
        assertArrayEquals(floatArrayOf(0.75f, 0.25f), CameraOrient.sensorUv(0.25f, 0.75f, 180), 1e-6f)
        // 270 undoes 90.
        val a = CameraOrient.sensorUv(0.2f, 0.7f, 90)
        val b = CameraOrient.sensorUv(0.2f, 0.7f, 270)
        assertArrayEquals(floatArrayOf(1f - a[0], 1f - a[1]), b, 1e-6f)
    }

    @Test fun portraitPhone_recordsPortrait() {
        assertEquals(1080 to 1920, CameraOrient.outputSize(rotation = 90))
        assertEquals(1920 to 1080, CameraOrient.outputSize(rotation = 0))
    }

    @Test fun verticalFov_ofA16by9StreamFromA4by3Sensor() {
        // 6.4 × 4.8 mm sensor, 5 mm lens. Landscape: the 16:9 stream is 6.4 × 3.6 mm.
        assertEquals(39.6f, CameraOrient.verticalFovDeg(5f, 6.4f, 4.8f, rotation = 0, outAspect = 16f / 9f), 0.1f)
        // Portrait: the stream's 6.4 mm side is vertical.
        assertEquals(65.2f, CameraOrient.verticalFovDeg(5f, 6.4f, 4.8f, rotation = 90, outAspect = 9f / 16f), 0.1f)
    }
}
