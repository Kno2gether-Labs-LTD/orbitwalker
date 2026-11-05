package dev.avijit.orbit.render

/** Which eye a pass renders for. MONO = normal 2D output. */
enum class Eye { MONO, LEFT, RIGHT }

/** How a screen's image is laid out: plain, or already-3D side-by-side / top-bottom. */
enum class ContentMode(val label: String) { FLAT("2D"), SBS("Side-by-side 3D"), TB("Top-bottom 3D") }

/** Pure stereo maths for the 3D desk (unit-tested). */
object Stereo {
    const val DEFAULT_IPD_M = 0.063f

    /** Viewport (x, y, w, h) for [eye] within an output of [w]×[h]. SBS: left eye on the left half. */
    fun viewport(eye: Eye, w: Int, h: Int): IntArray = when (eye) {
        Eye.MONO -> intArrayOf(0, 0, w, h)
        Eye.LEFT -> intArrayOf(0, 0, w / 2, h)
        Eye.RIGHT -> intArrayOf(w / 2, 0, w - w / 2, h)
    }

    /** View-space x translation for [eye]: the world moves opposite to the eye. */
    fun eyeShiftX(eye: Eye, ipdM: Float): Float = when (eye) {
        Eye.MONO -> 0f
        Eye.LEFT -> ipdM / 2
        Eye.RIGHT -> -ipdM / 2
    }

    /**
     * Part of a screen's image an eye should see, as (offsetU, offsetV, scaleU, scaleV) in
     * SurfaceTexture UV space (v = 0 at the bottom). On a 2D desk, 3D content shows its left view.
     */
    fun contentUv(mode: ContentMode, eye: Eye, bar: Float = 0f): FloatArray {
        val right = eye == Eye.RIGHT
        val h = 1f - 2f * bar
        return when (mode) {
            ContentMode.FLAT -> floatArrayOf(0f, 0f, 1f, 1f)
            ContentMode.SBS -> floatArrayOf(if (right) 0.5f else 0f, bar, 0.5f, h)
            ContentMode.TB -> floatArrayOf(0f, if (right) bar else bar + h / 2f, 1f, h / 2f)
        }
    }

    /** Height of each black bar (fraction of the screen) when a [videoAspect] video fills a [panelAspect] screen. */
    fun letterbox(panelAspect: Float, videoAspect: Float): Float =
        if (videoAspect <= panelAspect) 0f else (1f - panelAspect / videoAspect) / 2f

    /** Bars for the usual case: a 16:9 3D video full screen on OrbitWalker's 16:10 screens. */
    val VIDEO_BAR: Float = letterbox(1.6f, 16f / 9f)

    /** Glasses display modes 0x37–0x3C are the 3D side-by-side family. */
    fun isSbsMode(mode: Int): Boolean = mode in 0x37..0x3C

    /** Each eye's panel is 1920×1080 in the glasses, whatever the signal width. */
    fun eyeAspect(): Float = 16f / 9f
}
