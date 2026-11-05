package dev.avijit.orbit.render

import dev.avijit.orbit.layout.ScreenPose

/** What fills the space around the screens. */
enum class BackgroundMode(val label: String) { ORBIT("Orbit"), SPACE("Space"), LOGO("Logo"), BLACK("Pure black") }

/** One screen to draw this frame. */
data class ScreenDraw(val slot: Int, val pose: ScreenPose, val focused: Boolean, val content: ContentMode = ContentMode.FLAT)

/** Everything the glasses scene needs for one frame; immutable, swapped atomically by the session. */
data class SceneSnapshot(
    val screens: List<ScreenDraw> = emptyList(),
    /** A tiny pose where the cursor dot sits (facing the user), or null to hide it. */
    val cursor: ScreenPose? = null,
    /** Spike A grid panel: shown when no screen is open. */
    val showGrid: Boolean = true,
    /** Green head-locked centre dot: hidden while screens are open (the cursor does that job). */
    val showHeadDot: Boolean = true,
    /** The pointer is over a screen (full size) rather than in empty space (smaller, dimmer). */
    val cursorOverScreen: Boolean = false,
    /** Milliseconds since the last click (drives the ripple); negative = none recent. */
    val clickAgeMs: Long = -1,
    val background: BackgroundMode = BackgroundMode.ORBIT,
    /** Orbit background: show the family's apps as labelled planets. */
    val orbitApps: Boolean = true,
    /** Orbit background frozen (comfort option): planets and labels don't move. */
    val orbitStill: Boolean = false,
    /** 0..1 brightness of the background (dimmed while app screens are open). */
    val backgroundLevel: Float = 1f,
    /** Alignment aids from the head-tracking tests (grid, ring, L/R markers). */
    val showMarkers: Boolean = false,
    /** 3D desk: render one view per eye, side by side. */
    val stereo: Boolean = false,
    val ipdM: Float = Stereo.DEFAULT_IPD_M,
    /** OrbitWalker keyboard under the focused screen, and its current picture. */
    val keyboard: ScreenPose? = null,
    val keyboardArt: android.graphics.Bitmap? = null,
    /** Floating dock of recent apps under the centre screen, and its current picture. */
    val dock: ScreenPose? = null,
    val dockArt: android.graphics.Bitmap? = null,
    /** Title bar above the focused screen (move / resize / close with the mouse). */
    val windowBar: ScreenPose? = null,
    val windowBarArt: android.graphics.Bitmap? = null,
    /** Unsharp-mask strength for app screens (0 = off). */
    val sharpen: Float = 0.35f,
)
