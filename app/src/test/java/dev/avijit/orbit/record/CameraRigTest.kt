package dev.avijit.orbit.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CameraRigTest {
    /** Where world point (x, y, z) lands in the camera image: NDC x, NDC y, and its distance along the view. */
    private fun project(pose: RigPose, x: Float, y: Float, z: Float, vfov: Float = 60f, aspect: Float = 16f / 9f): FloatArray {
        val v = CameraRig.viewMatrix(pose)
        val p = CameraRig.projection(vfov, aspect)
        val e = floatArrayOf(
            v[0] * x + v[4] * y + v[8] * z + v[12], v[1] * x + v[5] * y + v[9] * z + v[13],
            v[2] * x + v[6] * y + v[10] * z + v[14], 1f,
        )
        val c = FloatArray(4)
        for (r in 0..3) c[r] = p[r] * e[0] + p[4 + r] * e[1] + p[8 + r] * e[2] + p[12 + r] * e[3]
        return floatArrayOf(c[0] / c[3], c[1] / c[3], c[3])
    }

    private val screen = floatArrayOf(0f, 0f, -1.5f)

    @Test fun everySpot_aimsBetweenYouAndYourScreens() {
        CameraSpot.entries.forEach { spot ->
            val ndc = project(spot.preset(), 0f, CameraRig.LOOK_Y, -CameraRig.LOOK_M)
            assertEquals("$spot x", 0f, ndc[0], 1e-3f)
            assertEquals("$spot y", 0f, ndc[1], 1e-3f)
        }
    }

    @Test fun everySpot_framesYouAndTheCentreScreen() {
        CameraSpot.entries.forEach { spot ->
            listOf(floatArrayOf(0f, 0f, 0f), screen).forEach { p ->
                val ndc = project(spot.preset(), p[0], p[1], p[2], vfov = 70f) // ultra-wide lens
                assertTrue("$spot sees (${p.toList()}): ${ndc.toList()}", abs(ndc[0]) < 0.9f && abs(ndc[1]) < 0.9f && ndc[2] > 0f)
            }
        }
    }

    @Test fun angleGoesAroundYou_behindRightFrontLeft() {
        fun pos(a: Float) = CameraRig.position(RigPose(angleDeg = a, distanceM = 1.5f, heightM = 0f))
        pos(0f).let { assertEquals(0f, it[0], 1e-5f); assertEquals(1.5f, it[2], 1e-5f) }   // behind you
        pos(90f).let { assertEquals(1.5f, it[0], 1e-5f); assertEquals(0f, it[2], 1e-5f) }  // your right
        pos(180f).let { assertEquals(-1.5f, it[2], 1e-5f) }                               // in front
        pos(-90f).let { assertEquals(-1.5f, it[0], 1e-5f) }                               // your left
    }

    @Test fun fromBehind_youAreInFrontOfTheScreens_fromTheFront_theScreensAre() {
        val back = CameraSpot.BEHIND.preset()
        assertTrue(project(back, 0f, 0f, 0f)[2] < project(back, screen[0], screen[1], screen[2])[2])
        val front = CameraSpot.FRONT_RIGHT.preset()
        assertTrue(project(front, 0f, 0f, 0f)[2] > project(front, screen[0], screen[1], screen[2])[2])
    }

    @Test fun personDepth_isYourDistanceAlongTheView() {
        val p = CameraSpot.RIGHT_SIDE.preset()
        assertEquals(project(p, 0f, 0f, 0f)[2], CameraRig.personDepth(p), 1e-3f)
    }

    @Test fun worldRight_appearsRightInTheImage_fromBehind() {
        assertTrue(project(CameraSpot.BEHIND.preset(), 0.4f, 0f, -1.5f)[0] > 0f)
        assertTrue(project(CameraSpot.BEHIND.preset(), 0f, 0.3f, -1.5f)[1] > 0f)
    }

    @Test fun mirrorSpots_areMirrored() {
        val r = CameraSpot.RIGHT_SHOULDER.preset(); val l = CameraSpot.LEFT_SHOULDER.preset()
        assertEquals(-r.angleDeg, l.angleDeg, 1e-6f)
    }

    @Test fun calibration_leftRightOrbitsAroundYou_shiftAims() {
        val p = CameraSpot.BEHIND.preset()
        val q = Calibration.apply(p, CalKey.RIGHT, shift = false)!!
        assertEquals(p.angleDeg + Calibration.ORBIT_DEG, q.angleDeg, 1e-5f)
        val aimed = Calibration.apply(p, CalKey.RIGHT, shift = true)!!
        assertTrue(aimed.aimYawDeg > p.aimYawDeg)
        assertTrue(project(aimed, 0f, CameraRig.LOOK_Y, -CameraRig.LOOK_M)[0] < 0f) // aiming right moves the scene left
    }

    @Test fun calibration_heightDistanceZoom_areClamped() {
        var p = CameraSpot.BEHIND.preset()
        repeat(500) { p = Calibration.apply(p, CalKey.UP, false)!! }
        assertEquals(Calibration.MAX_HEIGHT_M, p.heightM, 1e-4f)
        repeat(500) { p = Calibration.apply(p, CalKey.FORWARD, false)!! }
        assertEquals(Calibration.MIN_DISTANCE_M, p.distanceM, 1e-4f)
        repeat(500) { p = Calibration.apply(p, CalKey.ZOOM_IN, false)!! }
        assertEquals(Calibration.MAX_ZOOM, p.zoom, 1e-4f)
    }

    @Test fun calibration_angleWraps() {
        var p = CameraSpot.BEHIND.preset()
        repeat(400) { p = Calibration.apply(p, CalKey.LEFT, false)!! }
        assertTrue(p.angleDeg in -180f..180f)
    }

    @Test fun calibration_resetDoneAndSpots() {
        val moved = CameraSpot.RIGHT_SIDE.preset().copy(heightM = 0.5f, aimYawDeg = 4f)
        assertEquals(CameraSpot.RIGHT_SIDE.preset(), Calibration.apply(moved, CalKey.RESET, false, CameraSpot.RIGHT_SIDE))
        assertEquals(null, Calibration.apply(moved, CalKey.DONE, false))
        assertEquals(CameraSpot.entries[2].preset(), Calibration.apply(moved, CalKey.SPOT_3, false))
    }

    @Test fun placementHint_tellsYouWhereThePhoneGoes() {
        assertEquals("Behind you, 1.4 m away, 15 cm above eye level", CameraRig.placement(CameraSpot.BEHIND.preset()))
        assertEquals("Front right (140°), 1.4 m away, 15 cm above eye level", CameraRig.placement(CameraSpot.FRONT_RIGHT.preset()))
        assertEquals("Left (-90°), 1.4 m away, at eye level", CameraRig.placement(RigPose(-90f, 1.4f, 0f)))
    }

    @Test fun pose_roundTripsThroughPrefs() {
        val p = CameraSpot.FRONT_LEFT.preset().copy(zoom = 1.1f, aimPitchDeg = -2f)
        assertEquals(p, RigPose.decode(p.encode()))
        assertEquals(null, RigPose.decode("junk"))
    }
}
