package dev.avijit.orbit.render

import android.app.Presentation
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.WindowManager
import dev.avijit.orbit.tracking.Quat
import dev.avijit.orbit.ui.CaptureFrame

/** Hardware input that arrives on the glasses display (Android moves a BT mouse there, and focus with it). */
interface GlassesInput {
    /** True if the key was used; false lets the system handle it. */
    fun onGlassesKey(event: KeyEvent): Boolean
    /** Relative, captured mouse event. */
    fun onCapturedMouse(event: MotionEvent): Boolean
    /** The mouse should drive OrbitWalker's pointer right now. */
    fun wantsMouse(): Boolean
    fun onGlassesCaptureChanged(hasCapture: Boolean)
    /** Uncaptured fallback: pointer moved by (dx, dy) px, or a button was clicked. */
    fun onMouseDelta(dx: Float, dy: Float)
    fun onMouseButton(secondary: Boolean)
    fun onMouseWheel(vscroll: Float)
}

/** Full-screen GL scene on the glasses (external display). */
class GlassesPresentation(
    context: Context,
    display: Display,
    private val poseProvider: () -> Quat,
    private val modeProvider: () -> AnchorMode,
    private val panelHost: PanelHost? = null,
    private val snapshotProvider: () -> SceneSnapshot = { SceneSnapshot() },
    private val input: GlassesInput? = null,
) : Presentation(context, display) {

    private lateinit var surface: GLSurfaceView
    private lateinit var renderer: SceneRenderer
    private lateinit var root: CaptureFrame
    private var downButtons = 0
    private var lastX = Float.NaN
    private var lastY = Float.NaN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Back/Esc on a Bluetooth keyboard must never close the glasses scene.
        setCancelable(false)
        surface = GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(MsaaConfigChooser())
            renderer = SceneRenderer(poseProvider, modeProvider, panelHost, snapshotProvider) { LogoArt.create(context) }
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        root = CaptureFrame(context) { e -> input?.onCapturedMouse(e) ?: false }.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            addView(surface)
        }
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        surface.onResume()
    }

    override fun onStop() {
        // The GL context (and the panel texture) goes away with the pause; the host must stop
        // using the old Surface. A fresh one is handed out when the context is recreated.
        panelHost?.onPanelSurfacesGone()
        surface.queueEvent { renderer.release() }
        surface.onPause()
        super.onStop()
    }

    // ---------------------------------------------------------------- input on the glasses display

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (input?.onGlassesKey(event) == true) return true
        // Never let Back/Esc reach Dialog handling (it would cancel or dismiss the presentation).
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) return true
        return super.dispatchKeyEvent(event)
    }

    /** Hide Android's black pointer only while OrbitWalker draws its own. */
    private fun updatePointerIcon() {
        val icon = if (input?.wantsMouse() == true) PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL) else null
        surface.pointerIcon = icon
        root.pointerIcon = icon
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && input?.wantsMouse() == true) {
            root.requestFocus()
            root.requestPointerCapture()
        }
    }

    override fun onPointerCaptureChanged(hasCapture: Boolean) {
        super.onPointerCaptureChanged(hasCapture)
        input?.onGlassesCaptureChanged(hasCapture)
    }

    fun captureFrame(file: java.io.File) { renderer.captureRequest = file }

    fun releaseMouse() {
        if (root.hasPointerCapture()) root.releasePointerCapture()
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        val sink = input ?: return super.dispatchGenericMotionEvent(ev)
        if (ev.isFromSource(InputDevice.SOURCE_MOUSE)) updatePointerIcon()
        if (!ev.isFromSource(InputDevice.SOURCE_MOUSE) || !sink.wantsMouse()) return super.dispatchGenericMotionEvent(ev)
        if (window?.decorView?.hasWindowFocus() == true) {
            root.requestFocus()
            root.requestPointerCapture()
            return true
        }
        // Not focused yet (the first click focuses this display): follow the hidden system pointer.
        when (ev.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER -> { lastX = ev.x; lastY = ev.y }
            MotionEvent.ACTION_HOVER_MOVE -> {
                if (!lastX.isNaN()) sink.onMouseDelta(ev.x - lastX, ev.y - lastY)
                lastX = ev.x; lastY = ev.y
            }
            MotionEvent.ACTION_SCROLL -> sink.onMouseWheel(ev.getAxisValue(MotionEvent.AXIS_VSCROLL))
        }
        return true
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val sink = input
        if (sink != null && ev.isFromSource(InputDevice.SOURCE_MOUSE) && sink.wantsMouse()) {
            // actionButton is undefined on UP; remember which button went down instead.
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) downButtons = ev.buttonState
            if (ev.actionMasked == MotionEvent.ACTION_UP) {
                sink.onMouseButton(secondary = downButtons and MotionEvent.BUTTON_SECONDARY != 0)
                downButtons = 0
                root.requestFocus()
                root.requestPointerCapture()
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }
}

/** 4× multisampling for smooth screen edges and pointer; falls back to no MSAA. */
private class MsaaConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: javax.microedition.khronos.egl.EGL10, display: javax.microedition.khronos.egl.EGLDisplay): javax.microedition.khronos.egl.EGLConfig {
        val base = intArrayOf(
            javax.microedition.khronos.egl.EGL10.EGL_RED_SIZE, 8, javax.microedition.khronos.egl.EGL10.EGL_GREEN_SIZE, 8,
            javax.microedition.khronos.egl.EGL10.EGL_BLUE_SIZE, 8, javax.microedition.khronos.egl.EGL10.EGL_DEPTH_SIZE, 16,
            javax.microedition.khronos.egl.EGL10.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
        )
        val msaa = base + intArrayOf(javax.microedition.khronos.egl.EGL10.EGL_SAMPLE_BUFFERS, 1, javax.microedition.khronos.egl.EGL10.EGL_SAMPLES, 4)
        // Recordable first, so the same context can draw into the video encoders.
        val recordable = intArrayOf(EGL_RECORDABLE_ANDROID, 1)
        for (attrs in listOf(msaa + recordable, base + recordable, msaa, base)) {
            val list = attrs + javax.microedition.khronos.egl.EGL10.EGL_NONE
            val count = IntArray(1)
            if (!egl.eglChooseConfig(display, list, null, 0, count) || count[0] <= 0) continue
            val configs = arrayOfNulls<javax.microedition.khronos.egl.EGLConfig>(count[0])
            egl.eglChooseConfig(display, list, configs, count[0], count)
            // EGL sorts deeper colour first (e.g. 10-bit); pick an exact RGBA8888 config.
            val value = IntArray(1)
            fun attr(c: javax.microedition.khronos.egl.EGLConfig, a: Int) = if (egl.eglGetConfigAttrib(display, c, a, value)) value[0] else -1
            configs.filterNotNull().firstOrNull { c ->
                attr(c, javax.microedition.khronos.egl.EGL10.EGL_RED_SIZE) == 8 && attr(c, javax.microedition.khronos.egl.EGL10.EGL_GREEN_SIZE) == 8 &&
                    attr(c, javax.microedition.khronos.egl.EGL10.EGL_BLUE_SIZE) == 8
            }?.let { return it }
        }
        throw IllegalStateException("no EGL config")
    }

    private companion object { const val EGL_RECORDABLE_ANDROID = 0x3142 }
}
