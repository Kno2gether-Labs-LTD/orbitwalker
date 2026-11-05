package dev.avijit.orbit.notify

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils

/** The glasses' card look (notifications and OrbitWalker's own reminders). */
object CardArt {
    /** Draws a card like the phone UI: dark rounded panel, app icon, app name, title, one line of text. */
    fun render(app: String, title: String, text: String, icon: Bitmap?): Bitmap {
        val w = 1000; val h = 230
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(236, 21, 26, 35) }
        c.drawRoundRect(RectF(4f, 4f, w - 4f, h - 4f), 44f, 44f, bg)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 124, 156, 255); style = Paint.Style.STROKE; strokeWidth = 3f }
        c.drawRoundRect(RectF(4f, 4f, w - 4f, h - 4f), 44f, 44f, edge)
        icon?.let { c.drawBitmap(it, 40f, (h - 120) / 2f, Paint(Paint.FILTER_BITMAP_FLAG)) }
        val x = 190f
        val appP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(124, 156, 255); textSize = 32f; typeface = Typeface.DEFAULT_BOLD }
        val titleP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 44f; typeface = Typeface.DEFAULT_BOLD }
        val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(170, 180, 200); textSize = 36f }
        val maxW = w - x - 40f
        c.drawText(TextUtils.ellipsize(app.uppercase(), android.text.TextPaint(appP), maxW, TextUtils.TruncateAt.END).toString(), x, 70f, appP)
        c.drawText(TextUtils.ellipsize(title, android.text.TextPaint(titleP), maxW, TextUtils.TruncateAt.END).toString(), x, 128f, titleP)
        c.drawText(TextUtils.ellipsize(text.replace('\n', ' '), android.text.TextPaint(textP), maxW, TextUtils.TruncateAt.END).toString(), x, 182f, textP)
        icon?.recycle()
        return b
    }

}
