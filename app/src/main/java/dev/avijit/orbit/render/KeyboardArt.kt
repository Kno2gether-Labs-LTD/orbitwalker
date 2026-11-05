package dev.avijit.orbit.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import dev.avijit.orbit.layout.VrKeyboard

/** Draws the in-glasses keyboard (OrbitWalker look: dark keys, indigo accents). */
object KeyboardArt {
    private const val W = 1440
    private val H = (W / VrKeyboard.ASPECT).toInt()

    fun render(page: VrKeyboard.Page, shift: Boolean, pressed: String?): Bitmap {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 12, 15, 22) }
        c.drawRoundRect(RectF(0f, 0f, W.toFloat(), H.toFloat()), 36f, 36f, panel)
        val keyBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(32, 38, 52) }
        val special = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(24, 29, 40) }
        val hot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(124, 156, 255) }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 48f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT }
        val small = Paint(text).apply { textSize = 30f; color = Color.rgb(170, 180, 200) }
        val rowH = H / VrKeyboard.ROWS.toFloat()
        val pad = 8f
        VrKeyboard.rows(page).forEachIndexed { r, row ->
            val total = row.sumOf { it.weight.toDouble() }.toFloat()
            var x = 0f
            row.forEach { k ->
                val w = W * k.weight / total
                if (k.label.isNotEmpty()) {
                    val rect = RectF(x + pad, r * rowH + pad, x + w - pad, (r + 1) * rowH - pad)
                    val isSpecial = k.action != VrKeyboard.Action.TYPE || k.label.length > 1
                    val paint = when {
                        k.label == pressed -> hot
                        k.action == VrKeyboard.Action.SHIFT && shift -> hot
                        isSpecial -> special
                        else -> keyBg
                    }
                    c.drawRoundRect(rect, 20f, 20f, paint)
                    val label = if (shift && k.action == VrKeyboard.Action.TYPE && k.label.length == 1 && k.label[0].isLetter()) k.label.uppercase() else k.label
                    val p = if (label.length > 1 && label != "⏎" && label != "⌫") small else text
                    c.drawText(label, rect.centerX(), rect.centerY() - (p.descent() + p.ascent()) / 2, p)
                }
                x += w
            }
        }
        return b
    }
}
