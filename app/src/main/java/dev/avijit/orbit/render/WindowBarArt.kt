package dev.avijit.orbit.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import dev.avijit.orbit.layout.WindowBar

/** Draws the focused screen's title bar: drag handle with the app name, then the layout buttons. */
object WindowBarArt {
    private const val H = 84
    private val W = (H * WindowBar.ASPECT).toInt()

    fun render(title: String, hover: WindowBar.Button?, dragging: Boolean): Bitmap {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val accent = Color.rgb(124, 156, 255)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dragging) Color.argb(235, 40, 52, 92) else Color.argb(215, 14, 18, 27) }
        c.drawRoundRect(RectF(0f, 0f, W.toFloat(), H.toFloat()), H / 2f, H / 2f, bg)
        val bw = H * WindowBar.BUTTON_W
        val firstX = W - WindowBar.BUTTONS.size * bw
        if (hover == WindowBar.Button.MOVE && !dragging) {
            c.drawRoundRect(RectF(4f, 4f, firstX - 4f, H - 4f), H / 2f, H / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 124, 156, 255) })
        }
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(225, 230, 242); textSize = H * 0.42f }
        val dim = TextPaint(text).apply { color = Color.rgb(140, 150, 175); textSize = H * 0.34f }
        // Grip dots, then the app name, then a hint.
        val grip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dragging) accent else Color.rgb(150, 160, 185) }
        for (r in 0..2) for (k in 0..1) c.drawCircle(H * 0.55f + k * H * 0.2f, H * 0.3f + r * H * 0.2f, H * 0.05f, grip)
        val baseline = H / 2f - (text.descent() + text.ascent()) / 2
        val name = TextUtils.ellipsize(title, text, firstX * 0.45f, TextUtils.TruncateAt.END).toString()
        c.drawText(name, H * 1.1f, baseline, text)
        val hint = if (dragging) "Release to place it here" else "Drag to move · wheel = closer/further"
        c.drawText(hint, H * 1.1f + text.measureText(name) + H * 0.5f, H / 2f - (dim.descent() + dim.ascent()) / 2, dim)
        val glyph = TextPaint(text).apply { textAlign = Paint.Align.CENTER; textSize = H * 0.5f }
        WindowBar.BUTTONS.forEachIndexed { i, btn ->
            val x0 = firstX + i * bw
            val rect = RectF(x0 + 6, 8f, x0 + bw - 6, H - 8f)
            val hot = btn == hover
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = when {
                    hot && btn == WindowBar.Button.CLOSE -> Color.rgb(220, 70, 80)
                    hot -> accent
                    else -> Color.argb(255, 30, 36, 50)
                }
            }
            c.drawRoundRect(rect, H / 3f, H / 3f, fill)
            val label = when (btn) {
                WindowBar.Button.SMALLER -> "−"
                WindowBar.Button.BIGGER -> "+"
                WindowBar.Button.CLOSER -> "◂▸"
                WindowBar.Button.FURTHER -> "▸◂"
                WindowBar.Button.RESET -> "↺"
                WindowBar.Button.CLOSE -> "✕"
                WindowBar.Button.MOVE -> ""
            }
            c.drawText(label, rect.centerX(), rect.centerY() - (glyph.descent() + glyph.ascent()) / 2, glyph)
        }
        return b
    }
}
