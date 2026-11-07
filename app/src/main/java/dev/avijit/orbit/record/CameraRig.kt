package dev.avijit.orbit.record

import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Where the recording phone stands, around you. You sit at the origin (eye level, y = 0), facing
 * −z where the screens are. [angleDeg] goes around you: 0 behind, +90 your right, ±180 in front.
 * The camera aims between you and your screens, then [aimYawDeg]/[aimPitchDeg] fine-tune it.
 */
data class RigPose(
    val angleDeg: Float,
    val distanceM: Float,
    val heightM: Float,
    val aimYawDeg: Float = 0f,
    val aimPitchDeg: Float = 0f,
    /** > 1 narrows the lens's field of view (for phones that crop the picture). */
    val zoom: Float = 1f,
) {
    fun encode(): String = listOf(angleDeg, distanceM, heightM, aimYawDeg, aimPitchDeg, zoom).joinToString(",")

    companion object {
        fun decode(s: String?): RigPose? {
            val v = s?.split(',')?.mapNotNull { it.toFloatOrNull() } ?: return null
            return if (v.size == 6) RigPose(v[0], v[1], v[2], v[3], v[4], v[5]) else null
        }
    }
}

/** Ready-made places for the phone. Number keys 1–7 pick them while calibrating. */
enum class CameraSpot(val label: String, private val angle: Float) {
    BEHIND("Behind you", 0f),
    RIGHT_SHOULDER("Over your right shoulder", 35f),
    LEFT_SHOULDER("Over your left shoulder", -35f),
    RIGHT_SIDE("Your right side", 90f),
    LEFT_SIDE("Your left side", -90f),
    FRONT_RIGHT("Front right", 140f),
    FRONT_LEFT("Front left", -140f),
    ;

    fun preset() = RigPose(angle, CameraRig.DEFAULT_DISTANCE_M, CameraRig.DEFAULT_HEIGHT_M)
}

object CameraRig {
    /** The camera looks at a point between you and your screens, so both are in the shot. */
    const val LOOK_M = 0.75f
    const val LOOK_Y = -0.05f
    const val DEFAULT_DISTANCE_M = 1.4f
    const val DEFAULT_HEIGHT_M = 0.15f

    fun position(p: RigPose): FloatArray {
        val a = Math.toRadians(p.angleDeg.toDouble())
        return floatArrayOf((sin(a) * p.distanceM).toFloat(), p.heightM, (cos(a) * p.distanceM).toFloat())
    }

    /** Camera yaw/pitch in degrees (yaw 0 looks along −z, right positive; pitch up positive). */
    fun aim(p: RigPose): Pair<Float, Float> {
        val c = position(p)
        val dx = 0f - c[0]; val dy = LOOK_Y - c[1]; val dz = -LOOK_M - c[2]
        val yaw = Math.toDegrees(atan2(dx.toDouble(), (-dz).toDouble())).toFloat()
        val pitch = Math.toDegrees(atan2(dy.toDouble(), hypot(dx.toDouble(), dz.toDouble()))).toFloat()
        return (yaw + p.aimYawDeg) to (pitch + p.aimPitchDeg)
    }

    private fun basis(p: RigPose): Array<FloatArray> {
        val (yawD, pitchD) = aim(p)
        val y = Math.toRadians(yawD.toDouble()); val pt = Math.toRadians(pitchD.toDouble())
        val f = floatArrayOf((sin(y) * cos(pt)).toFloat(), sin(pt).toFloat(), (-cos(y) * cos(pt)).toFloat())
        val r = floatArrayOf(cos(y).toFloat(), 0f, sin(y).toFloat())
        val u = floatArrayOf(r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0])
        return arrayOf(r, u, f)
    }

    /** World → camera view matrix (column-major). */
    fun viewMatrix(p: RigPose): FloatArray {
        val (r, u, f) = basis(p)
        val c = position(p)
        fun dot(a: FloatArray) = a[0] * c[0] + a[1] * c[1] + a[2] * c[2]
        return floatArrayOf(
            r[0], u[0], -f[0], 0f,
            r[1], u[1], -f[1], 0f,
            r[2], u[2], -f[2], 0f,
            -dot(r), -dot(u), dot(f), 1f,
        )
    }

    /** Your head's distance from the camera along its view (what's nearer is drawn over you). */
    fun personDepth(p: RigPose): Float {
        val f = basis(p)[2]; val c = position(p)
        return -(f[0] * c[0] + f[1] * c[1] + f[2] * c[2])
    }

    /** OpenGL perspective projection (column-major), like android.opengl.Matrix.perspectiveM. */
    fun projection(vfovDeg: Float, aspect: Float, near: Float = NEAR_M, far: Float = FAR_M): FloatArray {
        val f = 1f / tan(Math.toRadians(vfovDeg / 2.0)).toFloat()
        val nf = 1f / (near - far)
        return floatArrayOf(
            f / aspect, 0f, 0f, 0f,
            0f, f, 0f, 0f,
            0f, 0f, (far + near) * nf, -1f,
            0f, 0f, 2f * far * near * nf, 0f,
        )
    }

    /** The lens's vertical field of view after [zoom]. */
    fun zoomed(vfovDeg: Float, zoom: Float): Float =
        Math.toDegrees(2.0 * atan(tan(Math.toRadians(vfovDeg / 2.0)) / zoom)).toFloat()

    /** Where to put the phone, in words. */
    fun placement(p: RigPose): String {
        val a = p.angleDeg
        val side = if (a > 0) "right" else "left"
        val where = when {
            kotlin.math.abs(a) < 10f -> "Behind you"
            kotlin.math.abs(a) > 170f -> "In front of you"
            kotlin.math.abs(a) < 60f -> "Behind $side (${a.roundToInt()}°)"
            kotlin.math.abs(a) <= 120f -> "${side.replaceFirstChar { it.uppercase() }} (${a.roundToInt()}°)"
            else -> "Front $side (${a.roundToInt()}°)"
        }
        val cm = (p.heightM * 100).roundToInt()
        val height = when {
            cm == 0 -> "at eye level"
            cm > 0 -> "$cm cm above eye level"
            else -> "${-cm} cm below eye level"
        }
        return "$where, ${"%.1f".format(java.util.Locale.ROOT, p.distanceM)} m away, $height"
    }

    const val NEAR_M = 0.05f
    const val FAR_M = 30f
}

/** Keys while the camera monitor is open (you're lining up the overlay with the real camera). */
enum class CalKey {
    LEFT, RIGHT, UP, DOWN, FORWARD, BACK, ZOOM_IN, ZOOM_OUT, RESET, DONE,
    SPOT_1, SPOT_2, SPOT_3, SPOT_4, SPOT_5, SPOT_6, SPOT_7;

    companion object {
        fun of(keyCode: Int): CalKey? = when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
            android.view.KeyEvent.KEYCODE_DPAD_UP -> UP
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
            android.view.KeyEvent.KEYCODE_PAGE_UP, android.view.KeyEvent.KEYCODE_EQUALS, android.view.KeyEvent.KEYCODE_PLUS -> FORWARD
            android.view.KeyEvent.KEYCODE_PAGE_DOWN, android.view.KeyEvent.KEYCODE_MINUS -> BACK
            android.view.KeyEvent.KEYCODE_RIGHT_BRACKET -> ZOOM_IN
            android.view.KeyEvent.KEYCODE_LEFT_BRACKET -> ZOOM_OUT
            android.view.KeyEvent.KEYCODE_R -> RESET
            android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_ESCAPE -> DONE
            in android.view.KeyEvent.KEYCODE_1..android.view.KeyEvent.KEYCODE_7 -> entries[SPOT_1.ordinal + keyCode - android.view.KeyEvent.KEYCODE_1]
            else -> null
        }
    }
}

object Calibration {
    const val ORBIT_DEG = 2f
    const val AIM_DEG = 0.5f
    const val MOVE_M = 0.03f
    const val MIN_DISTANCE_M = 0.5f
    const val MAX_DISTANCE_M = 4f
    const val MIN_HEIGHT_M = -1f
    const val MAX_HEIGHT_M = 1.2f
    const val MIN_ZOOM = 0.6f
    const val MAX_ZOOM = 1.8f
    private const val ZOOM_STEP = 1.02f

    /**
     * The pose after one key: arrows move the phone around you and up/down, Shift+arrows aim it,
     * PgUp/PgDn (or + / −) bring it closer or further, [ ] zoom, R resets to [spot], 1–7 pick a spot.
     * Null means calibration is done.
     */
    fun apply(p: RigPose, key: CalKey, shift: Boolean, spot: CameraSpot = CameraSpot.BEHIND): RigPose? = when (key) {
        CalKey.LEFT -> if (shift) p.copy(aimYawDeg = p.aimYawDeg - AIM_DEG) else p.copy(angleDeg = wrap(p.angleDeg - ORBIT_DEG))
        CalKey.RIGHT -> if (shift) p.copy(aimYawDeg = p.aimYawDeg + AIM_DEG) else p.copy(angleDeg = wrap(p.angleDeg + ORBIT_DEG))
        CalKey.UP -> if (shift) p.copy(aimPitchDeg = p.aimPitchDeg + AIM_DEG) else p.copy(heightM = (p.heightM + MOVE_M).coerceIn(MIN_HEIGHT_M, MAX_HEIGHT_M))
        CalKey.DOWN -> if (shift) p.copy(aimPitchDeg = p.aimPitchDeg - AIM_DEG) else p.copy(heightM = (p.heightM - MOVE_M).coerceIn(MIN_HEIGHT_M, MAX_HEIGHT_M))
        CalKey.FORWARD -> p.copy(distanceM = (p.distanceM - MOVE_M).coerceIn(MIN_DISTANCE_M, MAX_DISTANCE_M))
        CalKey.BACK -> p.copy(distanceM = (p.distanceM + MOVE_M).coerceIn(MIN_DISTANCE_M, MAX_DISTANCE_M))
        CalKey.ZOOM_IN -> p.copy(zoom = (p.zoom * ZOOM_STEP).coerceIn(MIN_ZOOM, MAX_ZOOM))
        CalKey.ZOOM_OUT -> p.copy(zoom = (p.zoom / ZOOM_STEP).coerceIn(MIN_ZOOM, MAX_ZOOM))
        CalKey.RESET -> spot.preset()
        CalKey.DONE -> null
        else -> CameraSpot.entries.getOrNull(key.ordinal - CalKey.SPOT_1.ordinal)?.preset() ?: p
    }

    private fun wrap(a: Float): Float = ((a + 540f) % 360f) - 180f
}
