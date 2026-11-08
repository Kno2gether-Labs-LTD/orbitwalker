package dev.avijit.orbit.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Orbit, the little planet who lives on the dock. It wanders along the dock and naps when nobody needs it, pops up
 * when you call it, perks up while it listens, spins its ring while it thinks and chatters while it talks.
 *
 * The renderer calls [frame] every frame (render thread); the bitmap is redrawn about 30 times a second.
 */
object OrbitBuddy {
    /** Where to draw this frame: [x] across the dock (-1 left end … 1 right end), and the picture. */
    class Frame(val x: Float, val art: Bitmap, val fresh: Boolean)

    const val PX = 256
    /** Fraction of the picture below the buddy's feet (its shadow line sits on the dock's top edge). */
    const val FOOT_LINE = 0.86f

    @Volatile var enabled = true
        private set

    fun init(context: Context) {
        enabled = context.getSharedPreferences("orbit_agent", Context.MODE_PRIVATE).getBoolean(KEY, true)
    }

    fun setEnabled(context: Context, on: Boolean) {
        enabled = on
        context.getSharedPreferences("orbit_agent", Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }

    // ---------------------------------------------------------------- motion (render thread)

    private val art = Bitmap.createBitmap(PX, PX, Bitmap.Config.ARGB_8888)
    private val c = Canvas(art)
    private var lastDrawMs = 0L
    private var lastMs = 0L
    private val rnd = Random(7)

    private var x = 0.82f
    private var targetX = 0.82f
    private var facing = 0f          // -1 looking left … 1 right (eases)
    private var stepPhase = 0f
    private var nextIdeaMs = 0L
    private var hopStartMs = -10_000L
    private var blinkAtMs = 0L
    private var lookX = 0f; private var lookY = 0f
    private var lookTx = 0f; private var lookTy = 0f
    private var nextLookMs = 0L
    private var grow = 1f
    private var ringSpin = 0f
    private var seenCall = 0L
    private var mouthOpen = 0f
    private var heardLevel = 0f
    private var activeSinceMs = 0L
    private var wasActive = false

    fun frame(nowMs: Long): Frame {
        val dt = if (lastMs == 0L) 0.016f else ((nowMs - lastMs).coerceIn(0, 100)) / 1000f
        lastMs = nowMs
        val phase = OrbitAgent.phase
        val active = OrbitAgent.inSession || phase != OrbitAgent.Phase.IDLE
        val dozing = !active && WakeWord.state != WakeWord.State.LISTENING && WakeWord.state != WakeWord.State.PAUSED

        // Called: hop up and come to the middle of the dock.
        if (OrbitAgent.calledAt != seenCall) { seenCall = OrbitAgent.calledAt; hopStartMs = nowMs }
        if (active && !wasActive) activeSinceMs = nowMs
        if (!active && wasActive) { nextIdeaMs = nowMs + 2_500; targetX = if (x >= 0f) 0.82f else -0.82f }
        wasActive = active

        if (active) targetX = 0f
        else if (nowMs >= nextIdeaMs) {
            // Idle: now and then stroll somewhere new, or do a little hop. Mostly rest near the ends of the dock.
            nextIdeaMs = nowMs + 5_000 + rnd.nextLong(7_000)
            if (!dozing) when (rnd.nextInt(5)) {
                0, 1 -> targetX = (if (rnd.nextBoolean()) 1 else -1) * (0.55f + rnd.nextFloat() * 0.35f)
                2 -> targetX = (rnd.nextFloat() * 2f - 1f) * 0.9f
                3 -> hopStartMs = nowMs
                else -> Unit
            }
        }
        val dx = targetX - x
        val walking = abs(dx) > 0.01f
        if (walking) {
            val speed = if (active) 1.6f else 0.32f
            x += dx.coerceIn(-speed * dt, speed * dt)
            stepPhase += dt * (if (active) 16f else 9f)
            facing += ((if (dx > 0) 1f else -1f) - facing) * min(1f, dt * 6f)
        } else {
            facing += (0f - facing) * min(1f, dt * 3f)
        }
        grow += ((if (active) 1.22f else 1f) - grow) * min(1f, dt * 5f)

        // Eyes: wander when idle, at you when talking, up and aside while thinking.
        if (nowMs >= nextLookMs) {
            nextLookMs = nowMs + 1_200 + rnd.nextLong(2_500)
            lookTx = rnd.nextFloat() * 2f - 1f; lookTy = rnd.nextFloat() * 0.8f - 0.3f
        }
        val (tx, ty) = when {
            phase == OrbitAgent.Phase.THINKING -> 0.7f to -0.8f
            active -> 0f to 0.15f
            walking -> facing to 0f
            else -> lookTx to lookTy
        }
        lookX += (tx - lookX) * min(1f, dt * 8f); lookY += (ty - lookY) * min(1f, dt * 8f)
        if (nowMs > blinkAtMs + 140) blinkAtMs = nowMs + 2_200 + rnd.nextLong(3_300)

        ringSpin += dt * when (phase) { OrbitAgent.Phase.THINKING -> 5.5f; OrbitAgent.Phase.LISTENING -> 2f; else -> 0.7f }
        val talking = Voices.speaking
        mouthOpen += ((if (talking) 0.35f + 0.65f * abs(sin(nowMs / 85.0)).toFloat() * abs(sin(nowMs / 210.0)).toFloat() else 0f) - mouthOpen) * min(1f, dt * 18f)
        heardLevel += (OrbitAgent.micLevel - heardLevel) * min(1f, dt * 10f)

        val fresh = nowMs - lastDrawMs >= 33
        if (fresh) {
            lastDrawMs = nowMs
            val hopT = (nowMs - hopStartMs) / (if (active) 650f else 520f)
            val hop = if (hopT in 0f..1f) sin(PI * hopT).toFloat() * (if (active) 1f else 0.45f) else 0f
            val squash = when {
                hopT in 0f..0.12f -> 1f - 0.18f * sin(PI * hopT / 0.12f).toFloat()
                hopT in 0.12f..0.75f -> 1f + 0.08f * sin(PI * (hopT - 0.12f) / 0.63f).toFloat()
                else -> 1f
            }
            val step = if (walking) abs(sin(stepPhase)) * 0.1f else 0f
            val breathe = 1f + 0.025f * sin(nowMs / 520.0).toFloat()
            val blink = nowMs in blinkAtMs..(blinkAtMs + 140)
            draw(
                phase = phase, active = active, dozing = dozing, nowMs = nowMs,
                lift = hop + step, squash = squash * breathe, tilt = if (walking) sin(stepPhase) * 6f * (if (active) 0.5f else 1f) else 0f,
                blink = blink, sinceActive = nowMs - activeSinceMs,
            )
        }
        return Frame(x, art, fresh)
    }

    // ---------------------------------------------------------------- drawing

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val oval = RectF()
    private val path = Path()

    private const val CX = PX / 2f
    private const val R = 52f
    private val FEET = PX * FOOT_LINE

    private fun draw(phase: OrbitAgent.Phase, active: Boolean, dozing: Boolean, nowMs: Long, lift: Float, squash: Float, tilt: Float, blink: Boolean, sinceActive: Long) {
        art.eraseColor(Color.TRANSPARENT)
        val s = grow
        val r = R * s

        // Shadow on the dock: smaller and fainter the higher Orbit is.
        val shadowK = 1f / (1f + lift * 1.6f)
        p.shader = null
        p.color = Color.argb((90 * shadowK).toInt(), 10, 8, 30)
        oval.set(CX - r * 0.85f * shadowK, FEET - 7f, CX + r * 0.85f * shadowK, FEET + 7f)
        c.drawOval(oval, p)

        c.save()
        c.rotate(tilt, CX, FEET)
        c.scale(1f / squash.coerceAtLeast(0.7f), squash, CX, FEET)

        val cyU = FEET - r - lift * R * 0.9f / squash
        // Listening: soft rings breathe out with your voice.
        if (phase == OrbitAgent.Phase.LISTENING) {
            for (i in 0 until 3) {
                val t = ((nowMs / 1100f) + i / 3f) % 1f
                val rr = r * (1.15f + t * (0.35f + heardLevel * 0.3f))
                stroke.strokeWidth = 4f
                stroke.color = Color.argb((150 * (1f - t) * (0.5f + heardLevel)).toInt().coerceIn(0, 200), 125, 245, 214)
                c.drawCircle(CX, cyU, rr, stroke)
            }
        }

        val ringTilt = -16f
        val ringW = r * 1.75f; val ringH = r * 0.46f
        // Back half of the ring (behind the body).
        drawRing(cyU, ringW, ringH, ringTilt, front = false, phase = phase)

        // Body: a glossy little planet.
        p.color = Color.WHITE // full opacity: the shader paints the colour
        p.shader = RadialGradient(CX - r * 0.35f, cyU - r * 0.45f, r * 1.5f,
            intArrayOf(Color.rgb(222, 214, 255), Color.rgb(146, 128, 255), Color.rgb(78, 60, 200), Color.rgb(44, 32, 130)),
            floatArrayOf(0f, 0.35f, 0.75f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(CX, cyU, r, p)
        // Teal rim light, bottom right.
        p.color = Color.WHITE // full opacity: the shader paints the colour
        p.shader = RadialGradient(CX + r * 0.55f, cyU + r * 0.6f, r * 0.9f,
            intArrayOf(Color.argb(if (active) 150 else 90, 125, 245, 214), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawCircle(CX, cyU, r, p)
        // Gloss.
        p.shader = null
        p.color = Color.argb(120, 255, 255, 255)
        oval.set(CX - r * 0.62f, cyU - r * 0.78f, CX - r * 0.12f, cyU - r * 0.5f)
        c.save(); c.rotate(-24f, oval.centerX(), oval.centerY()); c.drawOval(oval, p); c.restore()

        drawFace(phase, active, dozing, nowMs, r, cyU, blink)

        // Front half of the ring, with its little moon.
        drawRing(cyU, ringW, ringH, ringTilt, front = true, phase = phase)

        // Thinking: three dots circle over its head.
        if (phase == OrbitAgent.Phase.THINKING) {
            for (i in 0 until 3) {
                val a = ringSpin * 0.9f + i * (2 * PI / 3).toFloat()
                p.color = Color.argb(230, 124 + i * 20, 156 + i * 25, 255)
                c.drawCircle(CX + cos(a) * r * 0.7f, cyU - r * 1.45f + sin(a) * r * 0.18f, 6f + i, p)
            }
        }
        // Dozing (no "Hey Orbit" listening): little z's float up.
        if (dozing) {
            p.color = Color.argb(200, 200, 190, 255)
            p.textSize = 22f; p.isFakeBoldText = true
            for (i in 0 until 2) {
                val t = ((nowMs / 2600f) + i * 0.5f) % 1f
                p.alpha = (220 * (1f - t)).toInt()
                p.textSize = 16f + t * 14f
                c.drawText("z", CX + r * 0.7f + t * 22f, cyU - r * 0.7f - t * 50f, p)
            }
            p.alpha = 255; p.isFakeBoldText = false
        }
        // Just called: a sparkle burst.
        if (active && sinceActive < 700) {
            val t = sinceActive / 700f
            stroke.strokeWidth = 4f
            stroke.color = Color.argb((230 * (1f - t)).toInt(), 255, 236, 160)
            for (i in 0 until 8) {
                val a = (i * PI / 4).toFloat()
                val r0 = r * (1.2f + t * 0.5f); val r1 = r0 + 12f * (1f - t)
                c.drawLine(CX + cos(a) * r0, cyU + sin(a) * r0, CX + cos(a) * r1, cyU + sin(a) * r1, stroke)
            }
        }
        c.restore()
    }

    private fun drawRing(cy: Float, w: Float, h: Float, tiltDeg: Float, front: Boolean, phase: OrbitAgent.Phase) {
        c.save()
        c.rotate(tiltDeg, CX, cy)
        // Front half = lower half of the ellipse.
        if (front) c.clipRect(CX - w - 20, cy, CX + w + 20, cy + h + 20) else c.clipRect(CX - w - 20, cy - h - 20, CX + w + 20, cy)
        val glow = phase == OrbitAgent.Phase.LISTENING || phase == OrbitAgent.Phase.THINKING
        stroke.strokeWidth = if (front) 9f else 7f
        stroke.shader = LinearGradient(CX - w, cy, CX + w, cy,
            intArrayOf(Color.rgb(125, 245, 214), Color.rgb(124, 156, 255), Color.rgb(200, 150, 255)), null, Shader.TileMode.CLAMP)
        stroke.alpha = if (front) 255 else if (glow) 200 else 150
        oval.set(CX - w, cy - h, CX + w, cy + h)
        c.drawOval(oval, stroke)
        stroke.shader = null; stroke.alpha = 255
        // The moon, travelling round the ring.
        val a = ringSpin
        val mx = CX + cos(a) * w; val my = cy + sin(a) * h
        if ((sin(a) >= 0f) == front) {
            p.shader = null
            p.color = Color.rgb(255, 236, 160)
            c.drawCircle(mx, my, if (front) 7f else 5.5f, p)
        }
        c.restore()
    }

    private fun drawFace(phase: OrbitAgent.Phase, active: Boolean, dozing: Boolean, nowMs: Long, r: Float, cy: Float, blink: Boolean) {
        val eyeDx = r * 0.36f
        val eyeY = cy - r * 0.08f + lookY * r * 0.08f
        val gx = lookX * r * 0.1f
        val surprised = phase == OrbitAgent.Phase.LISTENING
        val worried = phase == OrbitAgent.Phase.ERROR
        val ew = r * (if (surprised) 0.2f else 0.18f)
        val eh = r * (if (surprised) 0.3f else 0.26f)
        for (side in intArrayOf(-1, 1)) {
            val ex = CX + side * eyeDx + gx
            if (dozing || blink) {
                // Closed: a soft smile-curve.
                stroke.strokeWidth = 4.5f; stroke.color = Color.rgb(30, 22, 80)
                path.reset(); path.moveTo(ex - ew, eyeY); path.quadTo(ex, eyeY + eh * 0.6f, ex + ew, eyeY)
                c.drawPath(path, stroke)
                continue
            }
            if (phase == OrbitAgent.Phase.SPEAKING && !worried) {
                // Happy eyes while talking: little arches.
                stroke.strokeWidth = 5f; stroke.color = Color.rgb(30, 22, 80)
                path.reset(); path.moveTo(ex - ew, eyeY + eh * 0.2f); path.quadTo(ex, eyeY - eh * 0.9f, ex + ew, eyeY + eh * 0.2f)
                c.drawPath(path, stroke)
                continue
            }
            p.shader = null
            p.color = Color.rgb(250, 250, 255)
            oval.set(ex - ew, eyeY - eh, ex + ew, eyeY + eh); c.drawOval(oval, p)
            p.color = Color.rgb(26, 20, 70)
            val px = ex + lookX * ew * 0.35f; val py = eyeY + lookY * eh * 0.3f
            oval.set(px - ew * 0.68f, py - eh * 0.7f, px + ew * 0.68f, py + eh * 0.7f); c.drawOval(oval, p)
            p.color = Color.WHITE
            c.drawCircle(px - ew * 0.25f, py - eh * 0.35f, ew * 0.26f, p)
            if (worried) {
                stroke.strokeWidth = 4f; stroke.color = Color.rgb(30, 22, 80)
                c.drawLine(ex - ew * side * -0.9f, eyeY - eh * 1.45f, ex + ew * side * 0.9f, eyeY - eh * 1.15f, stroke)
            }
        }
        // Cheeks.
        p.color = Color.argb(if (active) 110 else 80, 255, 140, 190)
        c.drawCircle(CX - eyeDx * 1.45f + gx, cy + r * 0.2f, r * 0.12f, p)
        c.drawCircle(CX + eyeDx * 1.45f + gx, cy + r * 0.2f, r * 0.12f, p)
        // Mouth.
        val my = cy + r * 0.3f
        val mx = CX + gx * 0.8f
        stroke.color = Color.rgb(30, 22, 80); stroke.strokeWidth = 4f
        when {
            mouthOpen > 0.05f -> {
                p.color = Color.rgb(40, 20, 70)
                oval.set(mx - r * 0.13f, my - r * 0.05f, mx + r * 0.13f, my + r * (0.05f + 0.2f * mouthOpen)); c.drawOval(oval, p)
                p.color = Color.rgb(255, 130, 160)
                oval.set(mx - r * 0.07f, my + r * (0.03f + 0.12f * mouthOpen), mx + r * 0.07f, my + r * (0.05f + 0.2f * mouthOpen)); c.drawOval(oval, p)
            }
            phase == OrbitAgent.Phase.LISTENING -> { p.color = Color.rgb(40, 20, 70); c.drawCircle(mx, my + r * 0.04f, r * 0.06f + heardLevel * r * 0.04f, p) }
            phase == OrbitAgent.Phase.THINKING -> {
                path.reset(); path.moveTo(mx - r * 0.1f, my + r * 0.05f)
                path.quadTo(mx - r * 0.05f, my, mx, my + r * 0.05f); path.quadTo(mx + r * 0.05f, my + r * 0.1f, mx + r * 0.1f, my + r * 0.05f)
                c.drawPath(path, stroke)
            }
            phase == OrbitAgent.Phase.ERROR -> { path.reset(); path.moveTo(mx - r * 0.12f, my + r * 0.1f); path.quadTo(mx, my - r * 0.02f, mx + r * 0.12f, my + r * 0.1f); c.drawPath(path, stroke) }
            dozing -> { p.color = Color.rgb(40, 20, 70); c.drawCircle(mx, my + r * 0.05f, r * 0.04f + 1.5f * max(0f, sin(nowMs / 1300.0).toFloat()), p) }
            else -> { path.reset(); path.moveTo(mx - r * 0.12f, my); path.quadTo(mx, my + r * 0.14f, mx + r * 0.12f, my); c.drawPath(path, stroke) }
        }
    }

    private const val KEY = "buddy"
}
