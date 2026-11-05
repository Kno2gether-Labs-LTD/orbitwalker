package dev.avijit.orbit

/** What the single panel is showing. Immutable; AppSession swaps it on its worker thread. */
data class PanelState(
    val displayId: Int = -1,
    val launched: Boolean = false,
    val component: String? = null,
    /** Pixel size of this screen's virtual display (taps map onto it). */
    val width: Int = 1280,
    val height: Int = 800,
) {
    val active: Boolean get() = displayId >= 0 && launched
    val wantsReopen: Boolean get() = component != null && !launched

    /** The shell process died: its displays are gone. Keep the app so it reopens when Shizuku is back. */
    fun onShellLost() = copy(displayId = -1, launched = false)

    /** The app refused to open: forget it so it isn't retried on every reconnect. */
    fun onLaunchFailed() = copy(launched = false, component = null)
}
