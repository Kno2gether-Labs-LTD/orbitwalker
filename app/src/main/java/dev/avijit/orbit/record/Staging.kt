package dev.avijit.orbit.record

import dev.avijit.orbit.layout.ScreenPose
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/** How screens appear in the mixed reality video. The glasses never change. */
enum class ScreenStaging(val label: String) {
    /** The screen you're using, big behind you, framed for the camera. Others stay out of the shot. */
    FRAMED("Framed for the camera"),
    /** Every screen twice the size, further back, turned towards the camera. */
    BIGGER("All screens, bigger"),
    /** Exactly where they float around you. */
    REAL("Exactly where they are"),
}

/** Where the framed screen sits across the picture. */
enum class HeroSide(val label: String, val x: Float?) { AUTO("Auto", null), LEFT("Left", -0.3f), CENTRE("Centre", 0f), RIGHT("Right", 0.3f) }

/**
 * A flat panel in the world: centre, right/up/forward axes (forward points away from whoever
 * looks at its front, like [ScreenPose.basis]) and size in metres.
 */
class Placement(val c: FloatArray, val r: FloatArray, val u: FloatArray, val f: FloatArray, val w: Float, val h: Float) {
    companion object {
        fun of(p: ScreenPose): Placement {
            val (r, u, f) = p.basis()
            return Placement(p.centre(), r, u, f, p.widthM, p.heightM)
        }
    }
}

/** Pure geometry for staging screens in the mixed reality view. */
object Staging {
    /** Bigger: size × [BIG_SIZE], distance × [BIG_DISTANCE] (further, so they stay behind you). */
    const val BIG_SIZE = 2f
    const val BIG_DISTANCE = 1.6f
    /** How far the screens turn towards the camera, and the most they turn. */
    private const val TURN_SHARE = 0.5f
    private const val MAX_TURN_DEG = 35f
    /** The framed screen stands this far behind you (from the camera's point of view). */
    const val BEHIND_M = 0.9f
    /** Turning may bring the framed screen's near edge at most this share of its depth closer to the camera. */
    private const val NEAR_EDGE_SHARE = 0.18f
    /** Gap kept between the framed screen and the picture's sides (share of half the width). */
    private const val EDGE_MARGIN = 0.1f
    const val MIN_FILL = 0.4f
    const val MAX_FILL = 1.0f
    const val DEFAULT_FILL = 0.65f

    fun bigger(p: ScreenPose, camPos: FloatArray): Placement {
        val c = p.centre().let { floatArrayOf(it[0] * BIG_DISTANCE, it[1] * BIG_DISTANCE, it[2] * BIG_DISTANCE) }
        val (r, u, f) = p.basis()
        val turn = turnTowards(f, c, camPos)
        return Placement(c, rotY(r, turn), rotY(u, turn), rotY(f, turn), p.widthM * BIG_SIZE, p.heightM * BIG_SIZE)
    }

    /**
     * The screen you're using, standing behind you where the camera frames it: [fill] of the
     * picture's width (0.4–1), on [side], upright and turned between you and the camera.
     */
    fun framed(p: ScreenPose, rig: RigPose, vfovDeg: Float, aspect: Float, fill: Float, side: HeroSide): Placement {
        val cam = CameraRig.position(rig)
        val (cr, cu, cf) = CameraRig.axes(rig)
        val pd = CameraRig.personDepth(rig)
        val depth = maxOf(pd + BEHIND_M, 1.2f)
        val tanV = tan(Math.toRadians(vfovDeg / 2.0)).toFloat()
        val tanH = tanV * aspect
        val hw = depth * tanH; val hh = depth * tanV
        // Where you are across the picture; the screen goes the other way, so neither hides the other much.
        val personX = -dot(cam, cr) / (pd.coerceAtLeast(0.1f) * tanH)
        val tx = side.x ?: if (abs(personX) < 0.12f) 0f else (-personX * 0.8f).coerceIn(-0.3f, 0.3f)
        val ty = if (aspect < 1f) 0.22f else 0.12f
        val shape = p.widthM / p.heightM
        var w = fill.coerceIn(MIN_FILL, MAX_FILL) * 2f * hw
        var h = w / shape
        val maxH = (if (aspect < 1f) 0.5f else 0.75f) * 2f * hh
        if (h > maxH) { h = maxH; w = h * shape }
        // Keep the whole screen in the picture: slide it in from the edge if needed.
        val room = (1f - w / (2f * hw) - EDGE_MARGIN).coerceAtLeast(0f)
        val roomY = (1f - h / (2f * hh) - EDGE_MARGIN).coerceAtLeast(0f)
        val x = tx.coerceIn(-room, room) * hw; val y = ty.coerceIn(-roomY, roomY) * hh
        val c = FloatArray(3) { cam[it] + cr[it] * x + cu[it] * y + cf[it] * depth }
        // Upright, facing half-way between you and the camera (at most MAX_TURN_DEG off the camera).
        // Square to the camera's view (like a display on the wall facing the lens), then turned a little towards you.
        val toCam = yawOf(-cf[0], -cf[2])
        val toYou = yawOf(-c[0], -c[2])
        // A big screen can only turn a little: its near edge must stay well behind you, not swing at the lens.
        val geomMax = Math.toDegrees(kotlin.math.asin((NEAR_EDGE_SHARE * depth / (w / 2f)).coerceAtMost(1f).toDouble())).toFloat()
        val limit = minOf(MAX_TURN_DEG, geomMax)
        val face = toCam + (wrap(toYou - toCam) * TURN_SHARE).coerceIn(-limit, limit)
        // Facing direction (sin, 0, −cos) of `face`; forward is the opposite.
        val fy = Math.toRadians(face.toDouble())
        val f = floatArrayOf(-sin(fy).toFloat(), 0f, cos(fy).toFloat())
        val r = floatArrayOf(-f[2], 0f, f[0])
        // Turned, the near edge looks bigger: if an edge now leaves the picture, slide the screen back in.
        fun edgeX(sgn: Float): Float {
            val e = FloatArray(3) { c[it] + sgn * r[it] * w / 2f - cam[it] }
            return dot(e, cr) / (dot(e, cf) * tanH)
        }
        val over = maxOf(edgeX(1f), edgeX(-1f), 0f) - (1f - EDGE_MARGIN / 2f)
        val under = minOf(edgeX(1f), edgeX(-1f), 0f) + (1f - EDGE_MARGIN / 2f)
        val shift = (if (over > 0f) -over else 0f) + (if (under < 0f) -under else 0f)
        if (shift != 0f) for (i in 0..2) c[i] += cr[i] * shift * hw
        return Placement(c, r, floatArrayOf(0f, 1f, 0f), f, w, h)
    }

    /** [x] (a panel attached to [from]: its title bar, the pointer on it) moved along with [from] → [to]. */
    fun attached(x: ScreenPose, from: ScreenPose, to: Placement): Placement = attached(Placement.of(x), Placement.of(from), to)

    fun attached(x: Placement, from: Placement, to: Placement): Placement {
        val k = to.w / from.w
        val d = FloatArray(3) { x.c[it] - from.c[it] }
        val lx = dot(d, from.r); val ly = dot(d, from.u); val lz = dot(d, from.f)
        val c = FloatArray(3) { to.c[it] + k * (lx * to.r[it] + ly * to.u[it] + lz * to.f[it]) }
        fun map(v: FloatArray): FloatArray {
            val a = dot(v, from.r); val b = dot(v, from.u); val e = dot(v, from.f)
            return FloatArray(3) { a * to.r[it] + b * to.u[it] + e * to.f[it] }
        }
        return Placement(c, map(x.r), map(x.u), map(x.f), x.w * k, x.h * k)
    }

    /** True if [point] lies on [screen] (within its edges, close to its surface). */
    fun onScreen(point: FloatArray, screen: ScreenPose): Boolean {
        val s = Placement.of(screen)
        val d = FloatArray(3) { point[it] - s.c[it] }
        return abs(dot(d, s.r)) <= s.w / 2 + 0.01f && abs(dot(d, s.u)) <= s.h / 2 + 0.01f && abs(dot(d, s.f)) < 0.1f
    }

    /** Eases [from] towards [to] by [t] (0..1): smooth moves when the camera or the screen changes. */
    fun lerp(from: Placement, to: Placement, t: Float): Placement {
        fun mix(a: FloatArray, b: FloatArray) = FloatArray(3) { a[it] + (b[it] - a[it]) * t }
        fun unit(v: FloatArray): FloatArray { val l = kotlin.math.sqrt(dot(v, v)).coerceAtLeast(1e-6f); return FloatArray(3) { v[it] / l } }
        return Placement(mix(from.c, to.c), unit(mix(from.r, to.r)), unit(mix(from.u, to.u)), unit(mix(from.f, to.f)),
            from.w + (to.w - from.w) * t, from.h + (to.h - from.h) * t)
    }

    /** [p] smaller and further back: where a newly framed screen flies in from. */
    fun entrance(p: Placement): Placement =
        Placement(FloatArray(3) { p.c[it] + p.f[it] * 0.4f }, p.r, p.u, p.f, p.w * 0.85f, p.h * 0.85f)

    /** Degrees to turn a screen (about the vertical) so it faces partly towards the camera. */
    private fun turnTowards(f: FloatArray, c: FloatArray, cam: FloatArray): Float {
        val facing = yawOf(-f[0], -f[2])
        val toCam = yawOf(cam[0] - c[0], cam[2] - c[2])
        return (wrap(toCam - facing) * TURN_SHARE).coerceIn(-MAX_TURN_DEG, MAX_TURN_DEG)
    }

    /** Compass yaw of a horizontal direction, matching the screens' convention (sin, −cos). */
    private fun yawOf(x: Float, z: Float): Float = if (hypot(x, z) < 1e-5f) 0f else Math.toDegrees(atan2(x.toDouble(), (-z).toDouble())).toFloat()

    /** Turns [v] by [deg] in the same sense as yaw (towards +x from −z). */
    internal fun rotY(v: FloatArray, deg: Float): FloatArray {
        val a = Math.toRadians(deg.toDouble()); val s = sin(a).toFloat(); val co = cos(a).toFloat()
        return floatArrayOf(v[0] * co - v[2] * s, v[1], v[0] * s + v[2] * co)
    }

    private fun wrap(a: Float): Float = ((a + 540f) % 360f) - 180f
    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}
