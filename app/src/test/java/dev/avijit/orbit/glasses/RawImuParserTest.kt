package dev.avijit.orbit.glasses

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RawImuParserTest {
    @Test fun tooShort_returnsNull() =
        assertNull(RawImuParser().parse(floatArrayOf(1f, 2f, 3f, 4f, 5f), 0))

    @Test fun radPerSec_kept() {
        val s = RawImuParser().parse(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f), 7)!!
        assertEquals(1f, s.gyro[0], 1e-6f)
        assertEquals(7L, s.timestampNs)
    }

    @Test fun degPerSec_converted() {
        val s = RawImuParser().parse(floatArrayOf(90f, 0f, 0f, 0f, 0f, 1f), 0)!!
        assertEquals(1.5707964f, s.gyro[0], 1e-5f)
    }

    @Test fun accelInG_scaled() {
        val s = RawImuParser().parse(floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f), 0)!!
        assertEquals(9.80665f, s.accel[2], 1e-4f)
    }

    @Test fun accelInMs2_kept() {
        val s = RawImuParser().parse(floatArrayOf(0f, 0f, 0f, 0f, 0f, 9.8f), 0)!!
        assertEquals(9.8f, s.accel[2], 1e-4f)
    }

    @Test fun magAndTemp_optional() {
        assertNull(RawImuParser().parse(floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f), 0)!!.mag)
        val s = RawImuParser().parse(floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 10f, 20f, 30f, 41f), 0)!!
        assertArrayEquals(floatArrayOf(10f, 20f, 30f), s.mag, 0f)
        assertEquals(41f, s.tempC!!, 0f)
    }

    @Test fun degPerSec_latchedAfterFirstLargeSample() {
        val p = RawImuParser()
        p.parse(floatArrayOf(90f, 0f, 0f, 0f, 0f, 1f), 0)
        val s = p.parse(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f), 1)!!
        assertEquals(0.017453292f, s.gyro[0], 1e-6f)
    }
}
