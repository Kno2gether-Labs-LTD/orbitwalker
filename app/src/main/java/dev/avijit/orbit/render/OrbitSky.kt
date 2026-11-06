package dev.avijit.orbit.render

import dev.avijit.orbit.layout.ScreenPose
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Orbit background's solar system: each planet is an app of the Orbit family, slowly circling
 * the viewer, with a floating label. One source of truth for the shader (positions, colours) and
 * the labels, so a label always stays with its planet. Pure.
 */
object OrbitSky {
    /** kind: 0 gas giant (bands), 1 rocky, 2 ocean world with clouds. Colours are linear RGB 0..1. */
    class Planet(
        val name: String, val tagline: String,
        val phase: Float, val speed: Float, val elev: Float, val tilt: Float, val radius: Float,
        val kind: Float, val bands: Float, val base: FloatArray, val alt: FloatArray,
        /** Label accent (ARGB) in the app's family colour. */
        val accent: Int,
        val rings: Boolean = false,
        /** Opened in Orbit Browser when the planet is clicked. One page per app later. */
        val url: String = SITE_APPS,
    )

    const val SITE_APPS = "https://orbitapps.space/#apps"

    /** One clock for the renderer and for clicks, so a click finds the planet where it's drawn. */
    private val startNs = System.nanoTime()
    fun clockS(): Float = ((System.nanoTime() - startNs) / 1e9).toFloat() % 100_000f

    /** The planet (or its label) along [dir] at sky time [t], or null for empty sky. */
    fun planetAt(dir: FloatArray, t: Float): Planet? {
        var best: Planet? = null
        var bestAngle = Float.MAX_VALUE
        PLANETS.forEachIndexed { i, p ->
            val c = centre(p, t)
            val dot = (dir[0] * c[0] + dir[1] * c[1] + dir[2] * c[2]).coerceIn(-1f, 1f)
            val angle = kotlin.math.acos(dot)
            val reach = maxOf(p.radius * (if (p.rings) 1.7f else 1.3f), MIN_CLICK_RAD)
            val onLabel = dev.avijit.orbit.layout.hitTest(dir, mapOf(i to labelPose(p, t))) != null
            if ((angle <= reach || onLabel) && angle < bestAngle) { best = p; bestAngle = angle }
        }
        return best
    }

    /** Small planets still get a comfortable click target (2°). */
    private const val MIN_CLICK_RAD = 0.035f

    val PLANETS = listOf(
        Planet("Pass", "Passwords you own", 0.4f, 0.010f, -0.05f, 0.03f, 0.014f, 1f, 0f,
            floatArrayOf(0.82f, 0.85f, 0.92f), floatArrayOf(0.52f, 0.58f, 0.72f), 0xFFDFE5F5.toInt()),
        Planet("Energy", "Your solar and power, locally", 2.1f, 0.0075f, 0.10f, 0.04f, 0.020f, 0f, 6f,
            floatArrayOf(0.88f, 0.97f, 0.42f), floatArrayOf(0.40f, 0.78f, 0.28f), 0xFFD6FF7A.toInt()),
        Planet("Home", "Smart home, no cloud", -1.0f, 0.006f, -0.20f, 0.05f, 0.027f, 2f, 0f,
            floatArrayOf(0.08f, 0.26f, 0.72f), floatArrayOf(0.30f, 0.70f, 0.95f), 0xFF8BE0FF.toInt()),
        Planet("Secure", "Your cameras, your footage", 3.4f, 0.0048f, 0.30f, 0.06f, 0.020f, 1f, 0f,
            floatArrayOf(0.12f, 0.72f, 0.48f), floatArrayOf(0.05f, 0.42f, 0.55f), 0xFF7DFFB8.toInt()),
        Planet("Health", "Your ring, your data", 1.2f, 0.0027f, 0.46f, 0.05f, 0.070f, 0f, 16f,
            floatArrayOf(0.62f, 0.50f, 1.0f), floatArrayOf(1.0f, 0.50f, 0.72f), 0xFFC3A6FF.toInt()),
        Planet("Server", "Your own private cloud", -2.4f, 0.0019f, -0.38f, 0.08f, 0.052f, 0f, 10f,
            floatArrayOf(1.0f, 0.82f, 0.48f), floatArrayOf(0.95f, 0.56f, 0.30f), 0xFFFFC27A.toInt(), rings = true),
        Planet("Agents", "Your own AI, on your hardware", 4.6f, 0.0012f, 0.64f, 0.04f, 0.030f, 0f, 4f,
            floatArrayOf(0.55f, 0.46f, 0.96f), floatArrayOf(0.82f, 0.74f, 1.0f), 0xFFB9A6FF.toInt()),
    )

    /**
     * Comfort cap: a planet may drift at most this many degrees per second. Slower than clouds,
     * so the sky never makes the view feel like it's moving (no vection, no motion sickness).
     */
    const val MAX_DEG_PER_S = 0.2
    /** All orbits run at this fraction of the table's speeds (keeps every planet under the cap). */
    private const val SPEED_SCALE = 0.33f
    /** The moment the sky is frozen at when "Still sky" is on: planets nicely spread around you. */
    private const val STILL_T = 0f

    /** Time to draw the sky at: the frame time, or a fixed moment for a still sky. */
    fun skyTime(frameTimeS: Float, still: Boolean): Float = if (still) STILL_T else frameTimeS

    /** Direction from the viewer to the planet at time [t] seconds (unit vector, world frame). */
    fun centre(p: Planet, t: Float): FloatArray {
        val a = p.phase + t * p.speed * SPEED_SCALE
        val x = sin(a); val y = p.elev + p.tilt * cos(a); val z = -cos(a)
        val n = sqrt(x * x + y * y + z * z)
        return floatArrayOf(x / n, y / n, z / n)
    }

    const val LABEL_DISTANCE_M = 12f
    /** Label width as an angle: readable but small, like a caption in the sky. */
    private const val LABEL_WIDTH_RAD = 0.18f
    const val LABEL_ASPECT = 3.6f
    private const val LABEL_GAP_RAD = 0.012f

    /** Where the planet's label floats: just under it, facing the viewer. */
    fun labelPose(p: Planet, t: Float): ScreenPose {
        val c = centre(p, t)
        val yaw = Math.toDegrees(atan2(c[0].toDouble(), -c[2].toDouble())).toFloat()
        val pitch = Math.toDegrees(asin(c[1].coerceIn(-1f, 1f).toDouble())).toFloat()
        val w = LABEL_DISTANCE_M * LABEL_WIDTH_RAD
        val h = w / LABEL_ASPECT
        val drop = p.radius + (if (p.rings) p.radius * 0.6f else 0f) + LABEL_GAP_RAD + (h / LABEL_DISTANCE_M) / 2
        return ScreenPose(yaw, pitch - Math.toDegrees(drop.toDouble()).toFloat(), LABEL_DISTANCE_M, w, h)
    }
}
