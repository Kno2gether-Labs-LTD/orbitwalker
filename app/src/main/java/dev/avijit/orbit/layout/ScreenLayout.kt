package dev.avijit.orbit.layout

import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin

/** Whole-arrangement settings (Layout tab sliders). */
data class LayoutParams(
    val distanceM: Float = 1.5f,
    val sizePct: Float = 100f,
    val gapDeg: Float = 6f,
    val heightDeg: Float = 0f,
    /** Gap between the first row and the row above it. */
    val rowGapDeg: Float = 4f,
) {
    fun clamped() = LayoutParams(
        distanceM.coerceIn(ScreenLayout.MIN_DISTANCE_M, ScreenLayout.MAX_DISTANCE_M),
        sizePct.coerceIn(50f, 200f),
        gapDeg.coerceIn(0f, 20f),
        heightDeg.coerceIn(-20f, 20f),
        rowGapDeg.coerceIn(0f, 20f),
    )
}

/** Per-screen adjustment from Move mode, relative to the screen's slot in the arc. */
data class SlotOffset(
    val yawDeg: Float = 0f,
    val pitchDeg: Float = 0f,
    val distanceM: Float = 0f,
    val scale: Float = 1f,
)

/** Where one screen sits. World frame: x right, y up, −z forward; yaw > 0 = to the right. */
data class ScreenPose(
    val yawDeg: Float,
    val pitchDeg: Float,
    val distanceM: Float,
    val widthM: Float,
    val heightM: Float,
) {
    /** Unit vectors (right, up, forward). Forward points from the user to the screen centre. */
    fun basis(): Array<FloatArray> {
        val y = Math.toRadians(yawDeg.toDouble()); val p = Math.toRadians(pitchDeg.toDouble())
        val f = floatArrayOf((sin(y) * cos(p)).toFloat(), sin(p).toFloat(), (-cos(y) * cos(p)).toFloat())
        val r = floatArrayOf(cos(y).toFloat(), 0f, sin(y).toFloat())
        val u = floatArrayOf(r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0])
        return arrayOf(r, u, f)
    }

    /** World point at (u, v) on the screen; (0, 0) = top-left. */
    fun pointAt(u: Float, v: Float): FloatArray {
        val (r, up, _) = basis()
        val c = centre()
        val a = (u - 0.5f) * widthM; val b = (0.5f - v) * heightM
        return floatArrayOf(c[0] + r[0] * a + up[0] * b, c[1] + r[1] * a + up[1] * b, c[2] + r[2] * a + up[2] * b)
    }

    fun centre(): FloatArray {
        val y = Math.toRadians(yawDeg.toDouble()); val p = Math.toRadians(pitchDeg.toDouble())
        return floatArrayOf(
            (sin(y) * cos(p) * distanceM).toFloat(),
            (sin(p) * distanceM).toFloat(),
            (-cos(y) * cos(p) * distanceM).toFloat(),
        )
    }
}

object ScreenLayout {
    const val SLOTS = 10
    private const val PER_ROW = 5
    /** Column order within a row: centre, right, left, far right, far left. */
    private val COLS = intArrayOf(0, 1, -1, 2, -2)

    /** Column of [slot] in the arc (0 = straight ahead, + = right). */
    fun col(slot: Int) = COLS[slot % PER_ROW]

    /** Row of [slot]: 0 = eye height, 1 = the row above. */
    fun row(slot: Int) = slot / PER_ROW
    const val BASE_WIDTH_M = 1.6f
    const val MIN_DISTANCE_M = 0.8f
    const val MAX_DISTANCE_M = 3.0f
    const val MAX_YAW = 150f
    const val MAX_PITCH = 60f

    fun pose(slot: Int, params: LayoutParams, offset: SlotOffset, aspect: Float = 1.6f): ScreenPose {
        val p = params.clamped()
        val baseWidth = BASE_WIDTH_M * p.sizePct / 100f
        val angular = Math.toDegrees(2.0 * atan(baseWidth / 2.0 / p.distanceM)).toFloat()
        val slotYaw = col(slot) * (angular + p.gapDeg)
        val angularH = Math.toDegrees(2.0 * atan(baseWidth / aspect / 2.0 / p.distanceM)).toFloat()
        val rowPitch = row(slot) * (angularH + p.rowGapDeg)
        val width = baseWidth * offset.scale.coerceIn(0.5f, 2f)
        return ScreenPose(
            yawDeg = (slotYaw + offset.yawDeg).coerceIn(-MAX_YAW, MAX_YAW),
            pitchDeg = (p.heightDeg + rowPitch + offset.pitchDeg).coerceIn(-MAX_PITCH, MAX_PITCH),
            distanceM = (p.distanceM + offset.distanceM).coerceIn(MIN_DISTANCE_M, MAX_DISTANCE_M),
            widthM = width,
            heightM = width / aspect,
        )
    }

    /**
     * Screen size (% of [BASE_WIDTH_M]) at which one app pixel covers one glasses pixel:
     * the sharpest possible picture, like a directly connected display.
     */
    fun pixelPerfectSizePct(distanceM: Float, widthPx: Int, glassesPxPerDeg: Float): Float {
        val angle = Math.toRadians((widthPx / glassesPxPerDeg).toDouble())
        val widthM = 2.0 * distanceM * kotlin.math.tan(angle / 2)
        return (widthM / BASE_WIDTH_M * 100).toFloat()
    }

    /** Glasses resolution per degree: 1080 px over the renderer's 24° vertical field of view. */
    const val GLASSES_PX_PER_DEG = 1080f / 24f

    /** Limits a Move offset so the screen's pose stays inside the allowed ranges (no "dead zone"). */
    fun clampOffset(slot: Int, params: LayoutParams, o: SlotOffset): SlotOffset {
        val base = pose(slot, params, SlotOffset())
        return SlotOffset(
            yawDeg = o.yawDeg.coerceIn(-MAX_YAW - base.yawDeg, MAX_YAW - base.yawDeg),
            pitchDeg = o.pitchDeg.coerceIn(-MAX_PITCH - base.pitchDeg, MAX_PITCH - base.pitchDeg),
            distanceM = o.distanceM.coerceIn(MIN_DISTANCE_M - base.distanceM, MAX_DISTANCE_M - base.distanceM),
            scale = o.scale.coerceIn(0.5f, 2f),
        )
    }

    fun encode(p: LayoutParams, offsets: List<SlotOffset>): String =
        (listOf("v2", p.distanceM, p.sizePct, p.gapDeg, p.heightDeg, p.rowGapDeg) +
            offsets.flatMap { listOf(it.yawDeg, it.pitchDeg, it.distanceM, it.scale) }).joinToString(";")

    /** Reads v2, and v1 (4 slots, from v0.7) padded to [SLOTS]. */
    fun decode(s: String): Pair<LayoutParams, List<SlotOffset>>? {
        val parts = s.split(';')
        val (header, slots) = when (parts.firstOrNull()) {
            "v1" -> 4 to 4
            "v2" -> 5 to SLOTS
            else -> return null
        }
        if (parts.size != 1 + header + slots * 4) return null
        val f = parts.drop(1).map { it.toFloatOrNull() ?: return null }
        val params = LayoutParams(f[0], f[1], f[2], f[3], if (header == 5) f[4] else LayoutParams().rowGapDeg).clamped()
        val offsets = (0 until SLOTS).map { i ->
            if (i >= slots) SlotOffset() else { val b = header + i * 4; SlotOffset(f[b], f[b + 1], f[b + 2], f[b + 3]) }
        }
        return params to offsets
    }
}
