package dev.avijit.orbit.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/** A planet's caption in the Orbit sky: "Orbit <App>", its promise, and "Coming soon". */
object OrbitLabelArt {
    private const val W = 900
    private val H = (W / OrbitSky.LABEL_ASPECT).toInt()

    fun render(p: OrbitSky.Planet): Bitmap {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 10, 8, 32) }
        c.drawRoundRect(RectF(0f, 0f, W.toFloat(), H.toFloat()), H / 2f, H / 2f, bg)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (p.accent and 0x00FFFFFF) or (0x90 shl 24); style = Paint.Style.STROKE; strokeWidth = 3f }
        c.drawRoundRect(RectF(2f, 2f, W - 2f, H - 2f), H / 2f, H / 2f, edge)
        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 70f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val light = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.accent; textSize = 70f; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 196, 235); textSize = 38f }
        val x = 60f
        c.drawText("Orbit", x, 100f, bold)
        c.drawText(" " + p.name, x + bold.measureText("Orbit"), 100f, light)
        c.drawText(p.tagline, x, 170f, small)
        // "COMING SOON" pill on the right.
        val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.accent }
        val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(10, 8, 32); textSize = 30f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); letterSpacing = 0.08f }
        val label = "COMING SOON"
        val tw = tag.measureText(label)
        val r = RectF(W - 60f - tw - 40f, 52f, W - 60f, 104f)
        c.drawRoundRect(r, 26f, 26f, pill)
        c.drawText(label, r.left + 20f, r.centerY() - (tag.descent() + tag.ascent()) / 2, tag)
        return b
    }
}
