package dev.avijit.orbit.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class OrbitSkyTest {
    @Test fun sevenFamilyApps_withNamesAndTaglines() {
        assertEquals(7, OrbitSky.PLANETS.size)
        assertTrue(OrbitSky.PLANETS.any { it.name == "Health" })
        assertTrue(OrbitSky.PLANETS.any { it.name == "Secure" })
        assertTrue(OrbitSky.PLANETS.all { it.tagline.isNotBlank() })
    }

    @Test fun centres_areUnitDirections_thatMoveSlowly() {
        val a = OrbitSky.centre(OrbitSky.PLANETS[0], 0f)
        val b = OrbitSky.centre(OrbitSky.PLANETS[0], 1f)
        assertEquals(1f, sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]), 1e-4f)
        val moved = sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))
        assertTrue("moves, slowly: $moved", moved > 0f && moved < 0.02f)
    }

    @Test fun label_sitsUnderItsPlanet_atTheSameYaw() {
        val p = OrbitSky.PLANETS[4]
        val c = OrbitSky.centre(p, 100f)
        val yaw = Math.toDegrees(kotlin.math.atan2(c[0].toDouble(), -c[2].toDouble())).toFloat()
        val pitch = Math.toDegrees(kotlin.math.asin(c[1].toDouble())).toFloat()
        val l = OrbitSky.labelPose(p, 100f)
        assertEquals(yaw, l.yawDeg, 1e-3f)
        assertTrue(l.pitchDeg < pitch - Math.toDegrees(p.radius.toDouble()).toFloat())
        assertEquals(OrbitSky.LABEL_DISTANCE_M, l.distanceM, 1e-4f)
    }

    /** Comfort: no planet may drift faster than 0.2°/s (slower than clouds; no vection). */
    @Test fun everyPlanet_movesSlowerThanTheComfortCap() {
        OrbitSky.PLANETS.forEach { p ->
            var worst = 0.0
            var t = 0f
            while (t < 4000f) {
                val a = OrbitSky.centre(p, t); val b = OrbitSky.centre(p, t + 1f)
                val dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).toDouble().coerceIn(-1.0, 1.0)
                worst = maxOf(worst, Math.toDegrees(kotlin.math.acos(dot)))
                t += 7f
            }
            assertTrue("${p.name} drifts ${"%.3f".format(worst)}°/s", worst <= OrbitSky.MAX_DEG_PER_S)
        }
    }

    @Test fun stillSky_doesNotMove() {
        val p = OrbitSky.PLANETS[0]
        val a = OrbitSky.centre(p, OrbitSky.skyTime(1234f, still = true))
        val b = OrbitSky.centre(p, OrbitSky.skyTime(9999f, still = true))
        assertTrue(a.contentEquals(b))
    }
}
