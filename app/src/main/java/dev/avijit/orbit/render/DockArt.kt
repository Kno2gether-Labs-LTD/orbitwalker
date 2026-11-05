package dev.avijit.orbit.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.text.TextPaint
import dev.avijit.orbit.layout.AppDock

/** Clock, date, phone battery and next event, shown at the left of the dock. */
data class DockWidget(val time: String, val date: String, val batteryPct: Int, val charging: Boolean, val event: String?)

/** Draws the floating app dock: widgets, then app icons over their names, the hovered one lit (OrbitWalker look). */
object DockArt {
    private const val ITEM_W = 240
    private val ITEM_H = (ITEM_W / AppDock.ITEM_ASPECT).toInt()

    fun render(widget: DockWidget?, icons: List<Bitmap?>, labels: List<String>, hover: Int?): Bitmap {
        val wx = if (widget != null) (ITEM_W * AppDock.WIDGET_SLOTS).toInt() else 0
        val b = Bitmap.createBitmap((wx + ITEM_W * labels.size).coerceAtLeast(ITEM_W), ITEM_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 12, 15, 22) }
        c.drawRoundRect(RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()), 44f, 44f, panel)
        if (widget != null) drawWidget(c, widget, wx)
        c.translate(wx.toFloat(), 0f)
        val hot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 124, 156, 255) }
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 226, 240); textSize = 34f; textAlign = Paint.Align.CENTER }
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        labels.forEachIndexed { i, label ->
            val x0 = i * ITEM_W.toFloat()
            if (i == hover) c.drawRoundRect(RectF(x0 + 10, 10f, x0 + ITEM_W - 10, ITEM_H - 10f), 32f, 32f, hot)
            val size = ITEM_W * 0.56f
            val top = ITEM_H * 0.14f
            icons.getOrNull(i)?.let { c.drawBitmap(it, null, RectF(x0 + (ITEM_W - size) / 2, top, x0 + (ITEM_W + size) / 2, top + size), iconPaint) }
            val name = TextUtils.ellipsize(label, text, ITEM_W - 24f, TextUtils.TruncateAt.END).toString()
            c.drawText(name, x0 + ITEM_W / 2f, top + size + 58f, text)
        }
        return b
    }

    private fun drawWidget(c: Canvas, w: DockWidget, width: Int) {
        val pad = 34f
        val time = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 96f; isFakeBoldText = true }
        val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(170, 180, 205); textSize = 34f }
        val event = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(124, 156, 255); textSize = 32f }
        c.drawText(w.time, pad, 118f, time)
        c.drawText(w.date, pad, 168f, small)
        // Battery: outline, fill (green / amber / red), and the percentage.
        val bx = pad; val by = 196f; val bwid = 70f; val bh = 34f
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(170, 180, 205); style = Paint.Style.STROKE; strokeWidth = 3f }
        c.drawRoundRect(RectF(bx, by, bx + bwid, by + bh), 7f, 7f, outline)
        c.drawRect(RectF(bx + bwid, by + 10, bx + bwid + 6, by + bh - 10), Paint(outline).apply { style = Paint.Style.FILL })
        val level = w.batteryPct.coerceIn(0, 100) / 100f
        val fillColor = when { w.charging -> Color.rgb(90, 210, 150); w.batteryPct <= 15 -> Color.rgb(235, 80, 80); w.batteryPct <= 30 -> Color.rgb(240, 180, 70); else -> Color.rgb(90, 210, 150) }
        c.drawRoundRect(RectF(bx + 4, by + 4, bx + 4 + (bwid - 8) * level, by + bh - 4), 4f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor })
        c.drawText("${w.batteryPct}%" + if (w.charging) " ⚡" else "", bx + bwid + 20, by + bh - 6, small)
        w.event?.let { e ->
            val line = TextUtils.ellipsize(e, event, width - 2 * pad, TextUtils.TruncateAt.END).toString()
            c.drawText(line, pad, 270f, event)
        }
        // Divider between the widgets and the apps.
        c.drawRect(RectF(width - 2f, 30f, width.toFloat(), ITEM_H - 30f), Paint().apply { color = Color.argb(60, 255, 255, 255) })
    }
}
