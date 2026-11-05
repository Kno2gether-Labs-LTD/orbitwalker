package dev.avijit.orbit.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimestampScaleTest {

    private fun detect(deviceStep: Long, hostStepNs: Long, n: Int = 300): TimestampScale {
        val s = TimestampScale()
        repeat(n) { s.observe(1_000L + it * deviceStep, 5_000_000_000L + it * hostStepNs) }
        return s
    }

    @Test fun nanoseconds_detected() = assertEquals(1L, detect(1_000_000, 1_000_000).nsPerUnit)

    @Test fun microseconds_detected() = assertEquals(1_000L, detect(1_000, 1_000_000).nsPerUnit)

    @Test fun milliseconds_detected() = assertEquals(1_000_000L, detect(1, 1_000_000).nsPerUnit)

    @Test fun undecidedBeforeEnoughSamples() = assertNull(detect(1_000, 1_000_000, n = 10).nsPerUnit)

    @Test fun toNs_usesHostClockUntilDecided() {
        val s = TimestampScale()
        assertEquals(42L, s.toNs(device = 7, hostNs = 42))
    }

    @Test fun toNs_isMonotonicFromFirstDeviceStamp() {
        val s = detect(1_000, 1_000_000)
        val a = s.toNs(device = 1_000_000, hostNs = 0)
        val b = s.toNs(device = 1_000_500, hostNs = 0)
        assertEquals(500_000L, b - a)
    }
}
