package dev.avijit.orbit.glasses

import kotlin.math.abs
import kotlin.math.ln

/**
 * VITURE's raw IMU timestamp unit is undocumented. Detect it by comparing device-clock deltas
 * with host-clock deltas over the first samples and snapping to ns / µs / ms.
 */
class TimestampScale(private val samplesNeeded: Int = 200) {
    @Volatile
    var nsPerUnit: Long? = null
        private set

    private var firstDevice = 0L
    private var firstHost = 0L
    private var count = 0

    fun observe(device: Long, hostNs: Long) {
        if (nsPerUnit != null) return
        if (count == 0) {
            firstDevice = device
            firstHost = hostNs
        }
        count++
        if (count < samplesNeeded) return
        val dDevice = device - firstDevice
        val dHost = hostNs - firstHost
        if (dDevice <= 0 || dHost <= 0) return
        val ratio = dHost.toDouble() / dDevice
        nsPerUnit = CANDIDATES.minBy { abs(ln(ratio / it)) }
    }

    /** Converts a device timestamp to ns; falls back to the host clock until the unit is known. */
    fun toNs(device: Long, hostNs: Long): Long = nsPerUnit?.let { device * it } ?: hostNs

    private companion object {
        val CANDIDATES = listOf(1L, 1_000L, 1_000_000L)
    }
}
