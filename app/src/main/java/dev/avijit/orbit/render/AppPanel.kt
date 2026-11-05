package dev.avijit.orbit.render

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/** External-OES texture + SurfaceTexture + Surface that a virtual display renders into. GL thread only. */
class AppPanel private constructor(val textureId: Int, val surfaceTexture: SurfaceTexture, val surface: Surface) {
    private val frameAvailable = AtomicBoolean(false)
    val transform = FloatArray(16)
    var hasFrame = false
        private set
    /** Buffer size the producer renders at (for texel-sized sharpening). */
    @Volatile var width = 1280
    @Volatile var height = 800

    init {
        surfaceTexture.setOnFrameAvailableListener { frameAvailable.set(true) }
        android.opengl.Matrix.setIdentityM(transform, 0)
    }

    /** Latches the newest frame if one arrived. */
    fun update() {
        if (frameAvailable.getAndSet(false)) {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(transform)
            hasFrame = true
        }
    }

    fun release() {
        surface.release()
        surfaceTexture.release()
        GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
    }

    companion object {
        fun create(width: Int, height: Int): AppPanel {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            val st = SurfaceTexture(ids[0]).apply { setDefaultBufferSize(width, height) }
            return AppPanel(ids[0], st, Surface(st))
        }
    }
}

/** Receives the panel Surfaces from the GL thread (callbacks arrive on the main thread). */
interface PanelHost {
    /** A fresh surface for [slot]; [setBufferSize] may be called from any thread. */
    fun onPanelSurface(slot: Int, surface: Surface, setBufferSize: (Int, Int) -> Unit)

    /** Every surface handed out so far is dead (GL context lost or the glasses display went away). */
    fun onPanelSurfacesGone()
}
