package dev.avijit.orbit.ui

import dev.avijit.orbit.input.TrackpadAction
import dev.avijit.orbit.layout.LayoutParams
import dev.avijit.orbit.helper.HelperSetup
import dev.avijit.orbit.shell.HelperState

/** One app screen as shown on the Home grid. */
data class ScreenCard(val slot: Int, val packageName: String, val label: String, val position: String, val focused: Boolean, val live: Boolean)

/** Everything the phone UI renders; rebuilt a few times per second from the sessions. */
data class UiState(
    val glassesConnected: Boolean = false,
    val glassesTitle: String = "Plug in your VITURE Pro 2",
    val glassesDetail: String = "",
    val glassesProblem: Boolean = false,
    val imuHz: Int? = null,
    val pinned: Boolean = true,
    val hasGlassesDisplay: Boolean = false,
    /** App screens need OrbitWalker's helper (started once per phone restart). */
    val helper: HelperState = HelperState.NEEDS_SETUP,
    /** "built-in" or "Shizuku" while the helper is ready. */
    val helperVia: String? = null,
    val setupPhase: HelperSetup.Phase = HelperSetup.Phase.IDLE,
    val setupMessage: String? = null,
    val devOptionsOn: Boolean = false,
    /** Samsung phone: show the Auto Blocker step. */
    val isSamsung: Boolean = false,
    val screens: List<ScreenCard> = emptyList(),
    val maxScreens: Int = 4,
    val status: String = "",
    val layout: LayoutParams = LayoutParams(),
    val moveMode: Boolean = false,
    val mirrorOn: Boolean = false,
    val canReopen: Boolean = false,
    val reopenLabels: List<String> = emptyList(),
    val compass: Boolean = false,
    val invertYaw: Boolean = false,
    val invertPitch: Boolean = false,
    val invertRoll: Boolean = false,
    val diagnostics: String = "",
    val version: String = "",
    val mouseCaptured: Boolean = false,
    val pointerVisible: Boolean = true,
    /** null = no focused screen; false = keyboard can't show inside that screen (use phone typing). */
    val focusedImeLocal: Boolean? = null,
    /** Label of the app running in Cinema mode, or null when Cinema is off. */
    val cinemaApp: String? = null,
    val mediaAccess: Boolean = false,
    val cinemaPlaying: Boolean? = null,
    /** Why the last Cinema launch failed, shown on Home. */
    val cinemaError: String? = null,
    /** The helper is ready, so Cinema can send arrows/OK/Back to the app on the glasses. */
    val cinemaKeys: Boolean = false,
    val mouseConnected: Boolean = false,
    val mouseReleasedByUser: Boolean = false,
    val quality: dev.avijit.orbit.ScreenQuality = dev.avijit.orbit.ScreenQuality.SHARP,
    val background: dev.avijit.orbit.render.BackgroundMode = dev.avijit.orbit.render.BackgroundMode.ORBIT,
    val dimWithApps: Float = 0.25f,
    val dimNoApps: Float = 1f,
    val showMarkers: Boolean = false,
    val notificationsOn: Boolean = true,
    val notificationsHideText: Boolean = false,
    /** Notification access granted (cards and Cinema media controls). */
    val notificationAccess: Boolean = false,
    val stereo: Boolean = false,
    /** BT mouse/keyboard are read directly and driven by OrbitWalker. */
    val rawInputActive: Boolean = false,
    val rawInputDevices: String = "",
    val rawReleased: Boolean = false,
    /** Bluetooth/USB keyboard and mouse: where each is going and why (Home + Trackpad card). */
    val input: dev.avijit.orbit.input.InputStatus? = null,
    /** Recently opened apps not on a screen right now (Home quick row and the glasses dock). */
    val recentApps: List<AppEntry> = emptyList(),
    val dockEnabled: Boolean = true,
    val widgetsEnabled: Boolean = true,
    val calendarEnabled: Boolean = false,
    val windowBarEnabled: Boolean = true,
    val orbitApps: Boolean = true,
    val orbitStill: Boolean = false,
    /** Eye-break reminder interval in minutes; 0 = off. */
    val eyeBreakMin: Int = 20,
    val batteryPct: Int = -1,
    val keyboardInGlasses: Boolean = false,
    val sharpen: Float = 0.35f,
    val ipdMm: Float = 63f,
    /** 3D content layout of the focused screen. */
    val focusedContent: dev.avijit.orbit.render.ContentMode = dev.avijit.orbit.render.ContentMode.FLAT,
)

/** What the UI can ask the app to do. Implemented by MainActivity. */
interface OrbitActions {
    fun recenter()
    fun togglePinned()
    fun onTrackpad(action: TrackpadAction)
    fun back()
    fun switchScreen()
    fun typeText(text: String)
    fun enter()
    fun backspace()
    fun focus(slot: Int)
    fun closeScreen(slot: Int)
    fun moveScreen(slot: Int)
    fun setMoveMode(on: Boolean)
    fun resetScreen()
    fun resetLayout()
    fun setLayout(p: LayoutParams)
    fun openApp(component: String)
    fun openBrowser()
    /** Camera studio screen in the glasses (recording controls with the live camera picture). */
    fun openStudio()
    fun reopenLast()
    /** Home/Settings button: Start when paired. */
    fun helperAction()
    fun startHelper()
    /** Watches for the pairing dialog, shows the code notification and opens Wireless debugging. */
    fun beginPairing()
    fun submitPairingCode(code: String)
    fun cancelPairing()
    fun openWirelessDebugging()
    fun openAboutPhone()
    /** Samsung: opens Auto Blocker (or Security and privacy, where it lives). */
    fun openAutoBlocker()
    fun setMirror(on: Boolean)
    fun setCompass(on: Boolean)
    fun setInvert(yaw: Boolean, pitch: Boolean, roll: Boolean)
    fun retryGlasses()
    fun shareLogs()
    fun setFullscreen(on: Boolean)
    fun launchableApps(): List<AppEntry>
    fun hideKeyboard()
    fun setPointerVisible(on: Boolean)
    fun setDockEnabled(on: Boolean)
    fun setWidgetsEnabled(on: Boolean)
    fun setCalendarEnabled(on: Boolean)
    fun setWindowBarEnabled(on: Boolean)
    fun setOrbitApps(on: Boolean)
    fun setOrbitStill(on: Boolean)
    fun setEyeBreakMinutes(min: Int)
    fun releaseMouseCapture()
    fun startCinema(component: String)
    fun exitCinema()
    fun cinemaPlayPause()
    fun cinemaSeek(deltaMs: Long)
    fun cinemaVolume(up: Boolean)
    fun openNotificationAccess()
    fun launchableAppsAll(): List<AppEntry>
    fun cinemaNav(keyCode: Int)
    fun cinemaTapCentre()
    fun useMouseForGlasses()
    fun setQuality(q: dev.avijit.orbit.ScreenQuality)
    fun setBackground(mode: dev.avijit.orbit.render.BackgroundMode, withApps: Float, noApps: Float, markers: Boolean)
    fun openVideoGuides()
    fun setNotifications(on: Boolean, hideText: Boolean)
    fun set3D(on: Boolean)
    fun useRawInput()
    fun setKeyboardInGlasses(on: Boolean)
    fun pixelPerfect()
    fun setSharpen(v: Float)
    fun releaseRawInputToPhone()
    fun setIpd(mm: Float)
    fun setContent(slot: Int, mode: dev.avijit.orbit.render.ContentMode)
}

data class AppEntry(val label: String, val component: String, val packageName: String)

/**
 * Apps whose name (or package) contains [query], best matches first: name starts with it, then a
 * word in the name does, then anywhere in the name, then the package name. Empty query = all.
 */
fun filterApps(apps: List<AppEntry>, query: String): List<AppEntry> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return apps
    fun rank(a: AppEntry): Int {
        val label = a.label.lowercase()
        return when {
            label.startsWith(q) -> 0
            label.split(' ', '-', '_', '.').any { it.startsWith(q) } -> 1
            label.contains(q) -> 2
            a.packageName.lowercase().contains(q) -> 3
            else -> -1
        }
    }
    return apps.map { it to rank(it) }.filter { it.second >= 0 }.sortedBy { it.second }.map { it.first }
}
