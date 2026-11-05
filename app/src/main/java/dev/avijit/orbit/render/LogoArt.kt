package dev.avijit.orbit.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import dev.avijit.orbit.R

/** The OrbitWalker logo + wordmark, drawn once for the glasses' Logo background. */
object LogoArt {
    fun create(context: Context): Bitmap? = runCatching {
        val w = 1400; val h = 900
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val icon = ContextCompat.getDrawable(context, R.mipmap.ic_launcher_round)!!.toBitmap(460, 460)
        c.drawBitmap(icon, (w - 460) / 2f, 40f, Paint(Paint.ANTI_ALIAS_FLAG))
        val orbit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 150f; typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val walker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 150f; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        val wOrbit = orbit.measureText("Orbit"); val wWalker = walker.measureText("Walker")
        val x = (w - wOrbit - wWalker) / 2f; val y = 700f
        walker.shader = LinearGradient(x + wOrbit, 0f, x + wOrbit + wWalker, 0f, Color.rgb(124, 156, 255), Color.rgb(94, 234, 212), Shader.TileMode.CLAMP)
        c.drawText("Orbit", x, y, orbit)
        c.drawText("Walker", x + wOrbit, y, walker)
        val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 154, 166, 186); textSize = 48f; textAlign = Paint.Align.CENTER; letterSpacing = 0.25f
        }
        c.drawText("YOUR DESK IN ORBIT", w / 2f, 800f, tag)
        icon.recycle()
        b
    }.getOrNull()
}
