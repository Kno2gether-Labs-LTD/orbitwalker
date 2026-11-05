package dev.avijit.orbit.render

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.opengl.GLES30
import android.opengl.GLUtils

/**
 * The OrbitWalker pointer: a classic arrow with a white body, a dark outline for contrast on
 * light content, and a soft indigo glow for dark content. Drawn once into a texture.
 */
object CursorSprite {
    const val SIZE = 128
    /** Arrow tip inside the bitmap, as a fraction of [SIZE]. */
    const val TIP_X = 24f / SIZE
    const val TIP_Y = 22f / SIZE

    fun bitmap(): Bitmap {
        val b = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val arrow = Path().apply {
            moveTo(24f, 22f)
            lineTo(24f, 100f)
            lineTo(44f, 82f)
            lineTo(58f, 114f)
            lineTo(72f, 108f)
            lineTo(58f, 77f)
            lineTo(84f, 77f)
            close()
        }
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 124, 156, 255)
            style = Paint.Style.STROKE
            strokeWidth = 14f
            strokeJoin = Paint.Join.ROUND
            maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL)
        }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 8, 10, 16)
            style = Paint.Style.STROKE
            strokeWidth = 6f
            strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(24f, 22f, 72f, 114f, Color.WHITE, Color.rgb(222, 230, 255), android.graphics.Shader.TileMode.CLAMP)
        }
        c.drawPath(arrow, glow)
        c.drawPath(arrow, outline)
        c.drawPath(arrow, fill)
        return b
    }

    /** GL thread: uploads the sprite and returns the texture id. */
    fun upload(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val b = bitmap()
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        b.recycle()
        return ids[0]
    }
}
