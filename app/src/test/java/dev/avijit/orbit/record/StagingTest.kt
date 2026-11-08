package dev.avijit.orbit.record

import dev.avijit.orbit.layout.ScreenPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class StagingTest {
    private val screen = ScreenPose(yawDeg = 0f, pitchDeg = 0f, distanceM = 1.5f, widthM = 0.8f, heightM = 0.5f)
    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun len(a: FloatArray) = sqrt(dot(a, a))

    /** Camera-space (x/z, y/z) of a world point, and its depth. */
    private fun project(rig: RigPose, p: FloatArray): Triple<Float, Float, Float> {
        val cam = CameraRig.position(rig); val (r, u, f) = CameraRig.axes(rig)
        val d = FloatArray(3) { p[it] - cam[it] }
        val z = dot(d, f)
        return Triple(dot(d, r) / z, dot(d, u) / z, z)
    }

    @Test fun framed_standsBehindYou_inTheShot_atTheRequestedWidth() {
        val rig = CameraSpot.RIGHT_SHOULDER.preset()
        val vfov = 60f; val aspect = 16f / 9f
        val p = Staging.framed(screen, rig, vfov, aspect, fill = 0.5f, side = HeroSide.CENTRE)
        val (x, y, z) = project(rig, p.c)
        assertTrue("behind you", z > CameraRig.personDepth(rig) + 0.5f)
        val tanV = kotlin.math.tan(Math.toRadians(vfov / 2.0)).toFloat(); val tanH = tanV * aspect
        assertEquals(0f, x / tanH, 0.01f) // centred across
        assertTrue("a little above the middle", y > 0f && y < tanV)
        assertEquals(0.5f * 2f * z * tanH, p.w, 0.01f)
        assertEquals(p.w / p.h, 0.8f / 0.5f, 0.01f) // the screen keeps its shape
    }

    @Test fun framed_isUpright_andFacesTheCameraMoreThanAway() {
        val rig = CameraSpot.RIGHT_SIDE.preset()
        val p = Staging.framed(screen, rig, 60f, 16f / 9f, 0.75f, HeroSide.AUTO)
        assertEquals(1f, p.u[1], 1e-4f)
        assertEquals(1f, len(p.f), 1e-4f)
        val cam = CameraRig.position(rig)
        val toCam = FloatArray(3) { cam[it] - p.c[it] }
        assertTrue("front faces the camera", dot(p.f, toCam) < 0f)
    }

    @Test fun framed_sideMovesTheScreenAcross() {
        val rig = CameraSpot.BEHIND.preset()
        val left = project(rig, Staging.framed(screen, rig, 60f, 16f / 9f, 0.6f, HeroSide.LEFT).c).first
        val right = project(rig, Staging.framed(screen, rig, 60f, 16f / 9f, 0.6f, HeroSide.RIGHT).c).first
        assertTrue(left < 0f && right > 0f)
    }

    @Test fun framed_portraitLimitsTheHeight() {
        val rig = CameraSpot.BEHIND.preset()
        val p = Staging.framed(screen, rig, 70f, 9f / 16f, 1f, HeroSide.CENTRE)
        val z = project(rig, p.c).third
        val visibleH = 2f * z * kotlin.math.tan(Math.toRadians(35.0)).toFloat()
        assertTrue(p.h <= 0.5f * visibleH + 1e-3f)
    }

    @Test fun bigger_isLargerFurther_andTurnsTowardsTheCamera() {
        val side = ScreenPose(yawDeg = 40f, pitchDeg = 0f, distanceM = 1.5f, widthM = 0.8f, heightM = 0.5f)
        val cam = CameraRig.position(CameraSpot.RIGHT_SIDE.preset())
        val p = Staging.bigger(side, cam)
        assertEquals(1.6f, p.w, 1e-4f)
        assertEquals(1.5f * Staging.BIG_DISTANCE, len(p.c), 1e-3f)
        val toCam = FloatArray(3) { cam[it] - p.c[it] }.let { v -> FloatArray(3) { v[it] / len(v) } }
        val before = -dot(Placement.of(side).f, toCam); val after = -dot(p.f, toCam)
        assertTrue("turned towards the camera", after > before)
    }

    @Test fun attached_followsItsScreen() {
        val bar = ScreenPose(yawDeg = 0f, pitchDeg = 12f, distanceM = 1.495f, widthM = 0.8f, heightM = 0.05f)
        val to = Staging.framed(screen, CameraSpot.BEHIND.preset(), 60f, 16f / 9f, 0.75f, HeroSide.CENTRE)
        val moved = Staging.attached(bar, screen, to)
        val k = to.w / screen.widthM
        assertEquals(bar.widthM * k, moved.w, 1e-4f)
        // Still above the screen's centre, along its up axis.
        val d = FloatArray(3) { moved.c[it] - to.c[it] }
        assertTrue(dot(d, to.u) > to.h / 2)
        assertEquals(0f, dot(d, to.r), 1e-3f)
    }

    @Test fun attached_toItselfIsTheSame() {
        val same = Staging.attached(screen, screen, Placement.of(screen))
        val orig = Placement.of(screen)
        for (i in 0..2) assertEquals(orig.c[i], same.c[i], 1e-5f)
        assertEquals(orig.w, same.w, 1e-5f)
    }

    @Test fun onScreen_findsPointsOnTheSurfaceOnly() {
        assertTrue(Staging.onScreen(screen.pointAt(0.3f, 0.6f), screen))
        assertFalse(Staging.onScreen(screen.pointAt(1.3f, 0.6f), screen))
        assertFalse(Staging.onScreen(floatArrayOf(0f, 0f, -0.5f), screen))
    }

    @Test fun lerp_endsAtTheTarget() {
        val a = Placement.of(screen)
        val b = Staging.bigger(ScreenPose(30f, 0f, 1.5f, 0.8f, 0.5f), floatArrayOf(0.5f, 0f, 1f))
        val m = Staging.lerp(a, b, 1f)
        for (i in 0..2) assertTrue(abs(m.c[i] - b.c[i]) < 1e-5f)
        assertEquals(b.w, m.w, 1e-5f)
    }

    @Test fun framed_bigScreenTurnsOnlyALittle_soItsNearEdgeStaysBack() {
        val rig = CameraSpot.RIGHT_SHOULDER.preset()
        val p = Staging.framed(screen, rig, 71.5f, 16f / 9f, 1f, HeroSide.AUTO)
        val depth = project(rig, p.c).third
        for (sgn in listOf(-1f, 1f)) {
            val edge = FloatArray(3) { p.c[it] + sgn * p.r[it] * p.w / 2 }
            assertTrue("edge depth ${project(rig, edge).third} vs $depth", project(rig, edge).third >= depth * 0.8f)
        }
    }

    @Test fun framed_staysInsideThePicture() {
        val rig = CameraSpot.RIGHT_SHOULDER.preset()
        val vfov = 60f; val aspect = 16f / 9f
        val tanH = kotlin.math.tan(Math.toRadians(vfov / 2.0)).toFloat() * aspect
        for (side in HeroSide.entries) {
            val p = Staging.framed(screen, rig, vfov, aspect, 0.65f, side)
            for (sgn in listOf(-1f, 1f)) {
                val edge = FloatArray(3) { p.c[it] + sgn * p.r[it] * p.w / 2 }
                val (x, _, _) = project(rig, edge)
                assertTrue("$side edge at ${x / tanH}", abs(x / tanH) <= 1f)
            }
        }
    }
}
