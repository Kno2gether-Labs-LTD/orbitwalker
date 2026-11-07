package dev.avijit.orbit

import android.content.ActivityNotFoundException
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import dev.avijit.orbit.helper.PairingService
import dev.avijit.orbit.helper.HelperSetup
import android.provider.Settings
import android.os.Build
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.avijit.orbit.cinema.CinemaController
import dev.avijit.orbit.diag.DiagLog
import dev.avijit.orbit.glasses.GlassesState
import dev.avijit.orbit.input.KeyRoute
import dev.avijit.orbit.input.KeyRouter
import dev.avijit.orbit.input.MouseInterpreter
import dev.avijit.orbit.input.MouseOut
import dev.avijit.orbit.input.TrackpadAction
import dev.avijit.orbit.layout.LayoutParams
import dev.avijit.orbit.layout.ScreenLayout
import dev.avijit.orbit.mirror.MirrorService
import dev.avijit.orbit.render.AnchorMode
import dev.avijit.orbit.render.AxisFix
import dev.avijit.orbit.render.GlassesPresentation
import dev.avijit.orbit.shell.HelperState
import dev.avijit.orbit.tracking.Quat
import dev.avijit.orbit.ui.AppEntry
import dev.avijit.orbit.ui.CaptureFrame
import dev.avijit.orbit.ui.OrbitActions
import dev.avijit.orbit.ui.OrbitApp
import dev.avijit.orbit.ui.ScreenCard
import dev.avijit.orbit.ui.UiState
import dev.avijit.orbit.ui.theme.OrbitTheme

/** Phone controller (Compose) plus the glasses presentation. */
class MainActivity : ComponentActivity(), OrbitActions, dev.avijit.orbit.render.GlassesInput {

    private lateinit var session: OrbitSession
    private lateinit var screens: ScreensSession
    private val status get() = session.status
    private val filter get() = session.filter
    private val source get() = session.source
    private lateinit var displayManager: DisplayManager
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var mode = AnchorMode.PINNED
    @Volatile private var axisFix = AxisFix()
    private var presentation: GlassesPresentation? = null
    private val state = mutableStateOf(UiState())
    private val mouse = MouseInterpreter()
    private lateinit var captureRoot: CaptureFrame
    /** Phone and glasses windows can each hold the mouse; see [MouseOwner]. */
    private var owner = dev.avijit.orbit.input.MouseOwner()
    private val mouseReleasedByUser get() = owner.userReleased
    private val wheel = MouseInterpreter()
    private val cinemaWorker = Handler(android.os.HandlerThread("orbit-cinema").apply { start() }.looper)
    private lateinit var cinema: CinemaController
    private lateinit var rawInput: dev.avijit.orbit.input.RawInputController
    /** The user handed the BT mouse/keyboard back to the phone (Ctrl+Esc or the bar) until they reclaim it. */
    private var rawReleased = false
    private var lastLocked = false
    private val mouseCaptured get() = owner.captured

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refreshPresentation()
        override fun onDisplayRemoved(displayId: Int) = refreshPresentation()
        override fun onDisplayChanged(displayId: Int) = Unit
    }

    /**
     * Keeps the raw mouse/keyboard grab in line with what's on screen, even while the phone app
     * isn't in front. Paused while the phone is securely locked.
     */
    private val rawTick = object : Runnable {
        override fun run() {
            syncRawInput()
            ui.postDelayed(this, RAW_SYNC_MS)
        }
    }

    /** Eye-care reminders while the glasses are on (counted only while they stream). */
    private val eyeBreak = EyeBreak(20)
    private val eyeTick = object : Runnable {
        override fun run() {
            val wearing = status.state is GlassesState.Streaming
            // Never during a film: Cinema mode, or any app playing audio/video (YouTube, Netflix, …).
            val watching = cinema.active || getSystemService(android.media.AudioManager::class.java).isMusicActive
            when (eyeBreak.tick(android.os.SystemClock.elapsedRealtime(), wearing, busy = watching)) {
                EyeBreak.Reminder.LOOK_AWAY -> showEyeCard("Eye break", "Look at something far away for 20 seconds. Blink a few times.", 20_000L)
                EyeBreak.Reminder.TAKE_OFF -> showEyeCard("Time for a real break", "You've had the glasses on for a while. Take them off for 5–10 minutes.", 30_000L)
                null -> Unit
            }
            ui.postDelayed(this, 1_000L)
        }
    }

    private fun showEyeCard(title: String, text: String, showMs: Long) {
        val art = runCatching { dev.avijit.orbit.notify.CardArt.render("OrbitWalker · eye care", title, text, null) }.getOrNull() ?: return
        dev.avijit.orbit.notify.NotificationFeed.postSystem("eyebreak", art, showMs)
        DiagLog.i(TAG, "eye break reminder: $title")
    }

    private val tick = object : Runnable {
        override fun run() {
            state.value = buildState()
            ui.postDelayed(this, UI_REFRESH_MS)
        }
    }

    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) MirrorService.start(this, result.resultCode, data)
        else DiagLog.i(TAG, "screen capture not allowed")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        session = OrbitSession.get(this)
        screens = ScreensSession.get(this)
        screens.labeler = ::appLabel
        screens.stereoSource = { dev.avijit.orbit.render.Stereo.isSbsMode(source.displayMode) }
        cinema = CinemaController(this)
        rawInput = dev.avijit.orbit.input.RawInputController(this, { screens.gate.shell() }, rawSink)
        ui.post(rawTick)
        ui.post(eyeTick)
        dev.avijit.orbit.diag.TestHarness.handler = harness
        dev.avijit.orbit.record.Recorder.init(this)
        dev.avijit.orbit.record.Recorder.onReport = { msg, error -> screens.notice(msg, error) }
        loadPrefs()
        DiagLog.i(TAG, "Orbit ${BuildConfig.VERSION_NAME} starting")

        displayManager = getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(displayListener, ui)
        source.stateListener = { ui.post { state.value = buildState() } }
        session.start()
        screens.listener = { state.value = buildState() }
        screens.start()
        refreshPresentation()

        // Captured mouse events go down the focus chain; this root sits above Compose and takes them.
        captureRoot = CaptureFrame(this, ::onCapturedMouse).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            addView(androidx.compose.ui.platform.ComposeView(this@MainActivity).apply {
                setContent { OrbitTheme { OrbitApp(state.value, this@MainActivity) } }
            })
        }
        setContentView(captureRoot)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) source.start()
    }

    /** Read once per resume (a Settings lookup), not on every UI tick. */
    private var notificationAccessCached = false

    override fun onResume() {
        super.onResume()
        notificationAccessCached = dev.avijit.orbit.notify.OrbitNotificationListener.isEnabled(this)
        ui.post(tick)
    }

    override fun onPause() {
        if (mouseCaptured) captureRoot.releasePointerCapture()
        ui.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacks(rawTick)
        ui.removeCallbacks(eyeTick)
        if (dev.avijit.orbit.diag.TestHarness.handler === harness) dev.avijit.orbit.diag.TestHarness.handler = null
        if (isFinishing) {
            rawInput.sync(false)
            cinemaWorker.looper.quitSafely()
            if (screens.stereo) set3D(false, null)
        }
        displayManager.unregisterDisplayListener(displayListener)
        presentation?.dismiss()
        source.stateListener = null
        screens.listener = null
        if (isFinishing) {
            screens.closeAll()
            MirrorService.stop(this)
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Bluetooth keyboard and mouse

    /** Where hardware input should go right now. */
    private fun cinemaInput() = cinema.active && screens.gate.shell() != null

    private fun screenInput() = !cinema.active && !screens.mirrorOn &&
        screens.focused?.let { screens.panels[it]?.displayId }?.let { it >= 0 } == true &&
        screens.gate.state == HelperState.READY

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = handleKey(event) || super.dispatchKeyEvent(event)

    override fun onGlassesKey(event: KeyEvent): Boolean = handleKey(event)

    /** Routes a hardware key from either display. True = consumed. */
    private fun handleKey(event: KeyEvent): Boolean {
        val device = event.device
        val external = device != null && !device.isVirtual && device.isExternal &&
            event.isFromSource(InputDevice.SOURCE_KEYBOARD)
        // While a phone-side text field has focus (phone typing sheet), keys stay on the phone.
        val editing = currentFocus?.onCheckIsTextEditor() == true
        val target = cinemaInput() || screenInput()
        return when (KeyRouter.route(external && !editing, event.keyCode, mouseCaptured, target)) {
            KeyRoute.FORWARD -> {
                if (cinemaInput()) cinemaKey(event) else screens.forwardKey(event)
                true
            }
            KeyRoute.RELEASE_MOUSE -> { if (event.action == KeyEvent.ACTION_UP) releaseMouse(); true }
            KeyRoute.LOCAL -> false
        }
    }

    // ---------------------------------------------------------------- raw BT mouse/keyboard (Shizuku)

    /** Take over the mouse and keyboard while there are screens (or Cinema) to drive. */
    private fun syncRawInput() {
        // Securely locked phone → stop driving the screens (they keep running while locked, so typing
        // into them would bypass the lock). Picks up again on unlock. OrbitWalker keeps the phone
        // awake while it's open, so a timeout lock is rare.
        val locked = getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked
        if (locked != lastLocked) {
            lastLocked = locked
            DiagLog.i(TAG, if (locked) "phone locked: keyboard/mouse paused until unlock" else "phone unlocked: keyboard/mouse back on the glasses")
        }
        val wanted = !rawReleased && !locked && screens.gate.state == HelperState.READY &&
            ((screens.panels.isNotEmpty() && !screens.mirrorOn) || cinema.active)
        rawInput.sync(wanted)
    }

    private val rawSink = object : dev.avijit.orbit.input.RawInputController.Sink {
        override fun onPointerMove(dx: Float, dy: Float) = screens.onTrackpad(TrackpadAction.Move(dx, dy))
        override fun onClick() = screens.clickAtPointer()
        override fun onPointerDown() = if (cinema.active) Unit else screens.pointerDown()
        override fun onPointerDrag() = screens.pointerDrag()
        override fun onPointerUp() = if (cinema.active) cinemaTapCentre() else screens.pointerUp()
        override fun onBack() = if (cinema.active) cinemaNav(KeyEvent.KEYCODE_BACK) else screens.back()
        override fun onScroll(notches: Int) = screens.onTrackpad(TrackpadAction.Scroll(-notches * SCROLL_PX_PER_NOTCH))
        override fun keyTarget(): Int = when {
            cinema.active -> glassesDisplay()?.displayId ?: -1
            else -> screens.focused?.let { screens.panels[it]?.displayId } ?: -1
        }.also { if (it < 0) logKeyProblem("key dropped: no focused screen (tap a screen first)") }
        override fun injectKey(displayId: Int, action: Int, keyCode: Int, meta: Int, repeat: Int, downTime: Long, eventTime: Long, scanCode: Int, deviceId: Int) {
            val shell = screens.gate.shell() ?: return logKeyProblem("helper not running")
            cinemaWorker.post {
                val ok = runCatching { shell.keyEventFull(displayId, action, keyCode, meta, repeat, downTime, eventTime, scanCode, deviceId) }
                    .getOrElse { logKeyProblem("helper call failed: ${it.javaClass.simpleName}"); return@post }
                if (!ok) logKeyProblem("injection refused on display $displayId: ${runCatching { shell.lastError() }.getOrNull()}")
                else lastKeyAtMs = android.os.SystemClock.uptimeMillis()
                keysSentCount.incrementAndGet()
            }
        }
        override fun onReleasedByShortcut() = releaseRawInput()
        override fun onFindCursor() {
            val (yaw, pitch) = dev.avijit.orbit.layout.Gaze.cursorAngles(headPose(), mode)
            screens.findCursor(yaw, pitch)
            DiagLog.i(TAG, "shortcut: find cursor")
        }
        override fun onRecordShortcut() = runOnUiThread { dev.avijit.orbit.record.Recorder.toggle() }
        override fun onCameraMonitorShortcut() = runOnUiThread { dev.avijit.orbit.record.Recorder.toggleMonitor() }
        override fun calibrating(): Boolean = dev.avijit.orbit.record.Recorder.monitorOn
        override fun onCalibrationKey(keyCode: Int, shift: Boolean) = dev.avijit.orbit.record.Recorder.calibrate(keyCode, shift)
        override fun onRecenterShortcut() {
            runOnUiThread {
                filter.recenter()
                screens.findCursor(0f, 0f)
                DiagLog.i(TAG, "shortcut: recenter")
            }
        }
        override fun onChanged() { if (!isDestroyed) state.value = buildState() }
    }

    private fun inputStatusNow(): dev.avijit.orbit.input.InputStatus {
        val ext = ArrayList<InputDevice>()
        for (id in InputDevice.getDeviceIds()) {
            val d: InputDevice = InputDevice.getDevice(id) ?: continue
            if (d.isExternal && !d.isVirtual) ext.add(d)
        }
        return dev.avijit.orbit.input.inputStatus(dev.avijit.orbit.input.InputFacts(
            grabbed = rawInput.devices,
            phoneKeyboards = ext.filter { d: InputDevice -> d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC }.map { d: InputDevice -> d.name },
            phoneMice = ext.filter { d: InputDevice -> d.supportsSource(InputDevice.SOURCE_MOUSE) }.map { d: InputDevice -> d.name },
            helperReady = screens.gate.state == HelperState.READY,
            locked = lastLocked,
            released = rawReleased,
            hasScreens = screens.panels.isNotEmpty(),
            mirror = screens.mirrorOn,
            cinema = cinema.active,
            focusedScreen = screens.focused?.let { screens.panels[it]?.displayId }?.let { it >= 0 } == true,
            lastKeyAtMs = lastKeyAtMs,
            lastProblem = lastKeyProblem,
            lastProblemAtMs = lastKeyProblemAtMs,
            nowMs = android.os.SystemClock.uptimeMillis(),
        ))
    }

    /** Key-path problems, rate-limited so Share logs stays readable. Key contents are never logged. */
    private val keyProblemCount = java.util.concurrent.atomic.AtomicInteger()
    private val keysSentCount = java.util.concurrent.atomic.AtomicLong()
    private val keyProblems get() = keyProblemCount.get()
    private val keysSent get() = keysSentCount.get()

    @Volatile private var lastKeyAtMs = 0L
    @Volatile private var lastKeyProblem: String? = null
    @Volatile private var lastKeyProblemAtMs = 0L

    private fun logKeyProblem(what: String) {
        lastKeyProblem = what.substringBefore(" (").substringBefore(":").replaceFirstChar { it.uppercase() }
        lastKeyProblemAtMs = android.os.SystemClock.uptimeMillis()
        val n = keyProblemCount.incrementAndGet()
        if (n <= 20 || n % 100 == 0) DiagLog.e("rawinput", "$what (problem #$n, keys sent so far $keysSent)")
    }

    private val harness = object : dev.avijit.orbit.diag.TestHarness.Handler {
        override fun status(): String = buildString {
            append("screens=").append(screens.panels.mapValues { "${it.value.component?.substringBefore('/')}@${it.value.displayId} live=${it.value.active}" })
            append(" focused=").append(screens.focused)
            append(" helper=").append(screens.gate.state)
            append(" raw=").append(if (rawInput.active) "on" else "off").append(" [").append(rawInput.devices).append("]")
            append(" keysSent=").append(keysSent).append(" keyProblems=").append(keyProblems)
            append(" kb=").append(screens.keyboardVisible)
            append(" cursor=").append("%.1f,%.1f".format(screens.cursor.yawDeg, screens.cursor.pitchDeg))
            append(" stereo=").append(screens.stereo)
            append(" last=").append(screens.status)
        }
        override fun open(component: String) = screens.openApp(component)
        override fun type(text: String) {
            val target = rawSink.keyTarget()
            if (target < 0) return
            val kcm = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
            kcm.getEvents(text.toCharArray())?.forEach { e ->
                rawSink.injectKey(target, e.action, e.keyCode, e.metaState, 0, e.downTime, e.eventTime,
                    dev.avijit.orbit.input.LinuxKeys.toLinux(e.keyCode), android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
            }
        }
        override fun key(code: Int) {
            val target = rawSink.keyTarget()
            if (target < 0) return
            val t = android.os.SystemClock.uptimeMillis()
            val scan = dev.avijit.orbit.input.LinuxKeys.toLinux(code)
            rawSink.injectKey(target, KeyEvent.ACTION_DOWN, code, 0, 0, t, t, scan, -1)
            rawSink.injectKey(target, KeyEvent.ACTION_UP, code, 0, 0, t, t + 20, scan, -1)
        }
        override fun point(yawDeg: Float, pitchDeg: Float) = screens.cursor.aimAt(yawDeg, pitchDeg)
        override fun aim(slot: Int, u: Float, v: Float) {
            // slot -1 = the in-glasses keyboard, -2 = the app dock
            // slot -3 = the focused screen's title bar
            val pose = (when (slot) {
                -1 -> screens.keyboardPose()
                -2 -> screens.dockPose()
                -3 -> screens.windowBarPose()
                in 0 until ScreenLayout.SLOTS -> screens.pose(slot)
                else -> null
            }) ?: return DiagLog.e("test", "nothing at slot $slot")
            val p = pose.pointAt(u, v)
            val len = kotlin.math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])
            screens.cursor.aimAt(
                Math.toDegrees(kotlin.math.atan2(p[0].toDouble(), -p[2].toDouble())).toFloat(),
                Math.toDegrees(kotlin.math.asin((p[1] / len).toDouble())).toFloat(),
            )
        }
        override fun look(yawDeg: Float, pitchDeg: Float) {
            // World frame: yaw right = rotation about +y by -yaw; pitch up = rotation about +x.
            val y = Quat.fromAxisAngle(0f, 1f, 0f, Math.toRadians(-yawDeg.toDouble()).toFloat())
            val p = Quat.fromAxisAngle(1f, 0f, 0f, Math.toRadians(pitchDeg.toDouble()).toFloat())
            testLook = y * p
        }
        override fun click() = screens.clickAtPointer()
        override fun background(mode: String) {
            val m = runCatching { dev.avijit.orbit.render.BackgroundMode.valueOf(mode) }.getOrNull() ?: return
            setBackground(m, screens.dimWithApps, screens.dimNoApps, screens.showMarkers)
        }
        override fun autoBlocker() = openAutoBlocker()
        override fun eyeBreak() = showEyeCard("Eye break", "Look at something far away for 20 seconds. Blink a few times.", 20_000L)
        override fun press(down: Boolean) = if (down) screens.pointerDown() else screens.pointerUp()
        override fun drag() = screens.pointerDrag()
        override fun aimPlanet(name: String) {
            val sky = dev.avijit.orbit.render.OrbitSky
            val p = sky.PLANETS.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return
            val c = sky.centre(p, sky.skyTime(sky.clockS(), screens.orbitStill))
            screens.cursor.aimAt(
                Math.toDegrees(kotlin.math.atan2(c[0].toDouble(), -c[2].toDouble())).toFloat(),
                Math.toDegrees(kotlin.math.asin(c[1].toDouble())).toFloat(),
            )
        }
        override fun showKeyboard(on: Boolean) = screens.setKeyboardVisible(on)
        override fun rawAnyBus(on: Boolean) {
            screens.gate.shell()?.let { s -> cinemaWorker.post { runCatching { s.setRawAnyBus(on) } } }
        }
        override fun closeAll() = screens.closeAll()
        override fun reclaim() = useRawInput()
        override fun forceStereo(on: Boolean) {
            screens.stereoSource = if (on) ({ true }) else ({ dev.avijit.orbit.render.Stereo.isSbsMode(source.displayMode) })
        }
        override fun pixelPerfect() = this@MainActivity.pixelPerfect()
        override fun cinema(component: String) = startCinema(component)
        override fun cinemaExit() = exitCinema()
        override fun frame() { presentation?.captureFrame(java.io.File(getExternalFilesDir(null), "frame.png")) }
    }

    private fun releaseRawInput() {
        rawReleased = true
        syncRawInput()
        state.value = buildState()
    }

    override fun releaseRawInputToPhone() = releaseRawInput()

    override fun pixelPerfect() {
        val l = screens.layout
        val pct = ScreenLayout.pixelPerfectSizePct(l.distanceM, screens.quality.width, ScreenLayout.GLASSES_PX_PER_DEG)
        screens.setLayout(l.copy(sizePct = pct))
        DiagLog.i(TAG, "pixel-perfect size ${"%.0f".format(pct)}% at ${l.distanceM} m, ${screens.quality.width} px")
        state.value = buildState()
    }

    override fun setSharpen(v: Float) {
        screens.sharpen = v.coerceIn(0f, 0.45f)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat("sharpen", screens.sharpen).apply()
        state.value = buildState()
    }

    override fun setKeyboardInGlasses(on: Boolean) {
        screens.setKeyboardVisible(on)
        state.value = buildState()
    }

    override fun useRawInput() {
        rawReleased = false
        syncRawInput()
        state.value = buildState()
    }

    // GlassesInput: the mouse on the glasses display (where Android puts it on the Fold).
    // When the raw route is active, Android never sees the mouse, so these paths stay idle.
    override fun wantsMouse(): Boolean = !rawInput.active && owner.wants(screenInput())

    override fun onGlassesCaptureChanged(hasCapture: Boolean) {
        owner = owner.glasses(hasCapture)
        DiagLog.i(TAG, if (hasCapture) "mouse captured (glasses)" else "mouse released (glasses)")
        state.value = buildState()
    }

    override fun onMouseDelta(dx: Float, dy: Float) = screens.onTrackpad(TrackpadAction.Move(dx, dy))

    override fun onMouseButton(secondary: Boolean) = if (secondary) screens.back() else screens.clickAtPointer()

    override fun onMouseWheel(vscroll: Float) {
        // Separate interpreter: the captured path's button state must not be reset by the wheel.
        wheel.onCaptured(0f, 0f, 0, vscroll).filterIsInstance<MouseOut.Scroll>().forEach {
            screens.onTrackpad(TrackpadAction.Scroll(-it.notches * SCROLL_PX_PER_NOTCH))
        }
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        // A Bluetooth mouse moving over the phone: capture it so it drives the glasses pointer,
        // unless the user released it (then it works on the phone until they ask again).
        if (!mouseCaptured && !mouseReleasedByUser && ev.isFromSource(InputDevice.SOURCE_MOUSE) &&
            screenInput() && hasWindowFocus()
        ) {
            capturePhone()
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onPointerCaptureChanged(hasCapture: Boolean) {
        super.onPointerCaptureChanged(hasCapture)
        owner = owner.phone(hasCapture)
        DiagLog.i(TAG, if (hasCapture) "mouse captured (phone)" else "mouse released (phone)")
        state.value = buildState()
    }

    /** Captured events follow view focus, so the root must hold focus before capture is requested. */
    private fun capturePhone() {
        captureRoot.requestFocus()
        captureRoot.requestPointerCapture()
    }

    override fun onCapturedMouse(e: MotionEvent): Boolean {
        val scroll = e.actionMasked == MotionEvent.ACTION_SCROLL
        val vscroll = if (scroll) e.getAxisValue(MotionEvent.AXIS_VSCROLL) else 0f
        var dx = 0f
        var dy = 0f
        if (!scroll) {
            // Relative events can batch several samples; sum them all so fast flicks aren't lost.
            for (i in 0 until e.historySize) { dx += e.getHistoricalX(i); dy += e.getHistoricalY(i) }
            dx += e.x; dy += e.y
        }
        mouse.onCaptured(dx, dy, e.buttonState, vscroll).forEach { out ->
            when (out) {
                is MouseOut.Move -> screens.onTrackpad(TrackpadAction.Move(out.dx, out.dy))
                MouseOut.Click -> screens.clickAtPointer()
                MouseOut.Back -> screens.back()
                // Wheel up = +notches = content moves down (page scrolls up); trackpad px have the opposite sign.
                is MouseOut.Scroll -> screens.onTrackpad(TrackpadAction.Scroll(-out.notches * SCROLL_PX_PER_NOTCH))
            }
        }
        return true
    }

    private fun releaseMouse() {
        owner = owner.release()
        if (owner.phoneCaptured) captureRoot.releasePointerCapture()
        presentation?.releaseMouse()
        state.value = buildState()
    }

    override fun useMouseForGlasses() {
        owner = owner.reclaim()
        if (screenInput() && hasWindowFocus()) capturePhone()
        state.value = buildState()
    }

    private fun mouseConnected(): Boolean = InputDevice.getDeviceIds().any { id ->
        InputDevice.getDevice(id)?.let { it.isExternal && !it.isVirtual && it.supportsSource(InputDevice.SOURCE_MOUSE) } == true
    }

    /** Cinema: a Bluetooth keyboard drives the app on the glasses (arrows, Enter, Space...). */
    private fun cinemaKey(e: KeyEvent) {
        val display = glassesDisplay() ?: return
        val shell = screens.gate.shell() ?: return
        val action = e.action; val code = e.keyCode; val meta = e.metaState; val repeat = e.repeatCount
        val down = e.downTime; val at = e.eventTime
        cinemaWorker.post { runCatching { shell.keyEventTimed(display.displayId, action, code, meta, repeat, down, at) } }
    }

    // ---------------------------------------------------------------- glasses display

    private fun headPose(): Quat = testLook?.let { it * axisFix.apply(filter.predicted(PREDICT_AHEAD_NS)) }
        ?: axisFix.apply(filter.predicted(PREDICT_AHEAD_NS))

    /** Test harness only: an extra head turn (no glasses on the emulator). */
    @Volatile private var testLook: Quat? = null

    /** GL thread only: the pose the glasses draw with, held still through sub-pixel jitter. */
    private val stabilizer = dev.avijit.orbit.tracking.PoseStabilizer()
    private fun renderPose(): Quat = stabilizer.filter(headPose())

    private fun glassesDisplay(): Display? =
        displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()

    private fun refreshPresentation() {
        val display = glassesDisplay()
        if (cinema.active) {
            // Cinema: the app owns the glasses display; our scene stays hidden. Unplug ends Cinema.
            presentation?.dismiss()
            presentation = null
            if (display == null) exitCinema()
            return
        }
        val current = presentation
        if (current != null && current.display.displayId == display?.displayId) return
        current?.dismiss()
        presentation = null
        if (display != null) {
            DiagLog.i(TAG, "glasses display: ${display.name} ${display.mode.physicalWidth}x${display.mode.physicalHeight} @${display.mode.refreshRate}Hz")
            presentation = GlassesPresentation(
                this, display, ::renderPose,
                modeProvider = { mode },
                panelHost = screens,
                snapshotProvider = { screens.snapshot() },
                input = this,
            ).also { p ->
                p.setOnDismissListener {
                    if (presentation === p) presentation = null
                    owner = owner.presentationGone()
                }
            }.also {
                try {
                    it.show()
                } catch (e: WindowManager.InvalidDisplayException) {
                    DiagLog.e(TAG, "cannot show on glasses display", e)
                    presentation = null
                }
            }
        } else {
            DiagLog.i(TAG, "no presentation display")
        }
    }

    // ---------------------------------------------------------------- UI state

    private fun buildState(): UiState {
        val s = status.state
        val hasDisplay = glassesDisplay() != null
        val (title, detail, problem) = when (s) {
            GlassesState.NoGlasses -> Triple("Plug in your VITURE Pro 2", "Connect the glasses to the phone's USB-C port.", false)
            GlassesState.AwaitingPermission -> Triple("Allow USB access", "Tap OK on the prompt to let Orbit read the glasses' motion sensor.", false)
            GlassesState.PermissionDenied -> Triple("USB access denied", "Open Settings → Glasses → Retry, then tap OK.", true)
            is GlassesState.Initializing -> Triple("Starting the glasses…", s.step.lowercase().replace('_', ' '), false)
            is GlassesState.SdkFailed -> Triple("Glasses didn't start", "Failed at ${s.step} (code ${s.code}). Share logs from Settings.", true)
            is GlassesState.Streaming -> Triple(
                "VITURE Pro 2 connected",
                if (hasDisplay) "Screens are pinned. Look around freely." else "Head tracking works, but no glasses picture: turn off Samsung DeX.",
                !hasDisplay,
            )
        }
        val cards = screens.panels.toSortedMap().mapNotNull { (slot, p) ->
            val comp = p.component ?: return@mapNotNull null
            val pkg = comp.substringBefore('/')
            ScreenCard(slot, pkg, appLabel(pkg), POSITION_NAMES[slot], screens.focused == slot, p.active)
        }
        val gate = screens.gate
        return UiState(
            glassesConnected = s is GlassesState.Streaming,
            glassesTitle = title,
            glassesDetail = detail,
            glassesProblem = problem,
            imuHz = (s as? GlassesState.Streaming)?.hz?.toInt(),
            pinned = mode == AnchorMode.PINNED,
            hasGlassesDisplay = hasDisplay,
            helper = gate.state,
            helperVia = gate.via,
            setupPhase = HelperSetup.phase,
            setupMessage = HelperSetup.message,
            devOptionsOn = devOptionsOn(),
            isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true),
            screens = cards,
            maxScreens = ScreenLayout.SLOTS,
            status = screens.status,
            layout = screens.layout,
            moveMode = screens.moveMode,
            mirrorOn = screens.mirrorOn,
            canReopen = screens.lastScreens.isNotEmpty() && gate.state == HelperState.READY,
            reopenLabels = screens.lastScreens.map { appLabel(it.substringBefore('/')) },
            compass = filter.useMag,
            invertYaw = axisFix.invertYaw,
            invertPitch = axisFix.invertPitch,
            invertRoll = axisFix.invertRoll,
            diagnostics = diagnostics(),
            mouseCaptured = mouseCaptured,
            cinemaApp = cinema.component?.let { appLabel(it.substringBefore('/')) },
            mediaAccess = if (cinema.active) cinema.hasMediaAccess() else false,
            cinemaPlaying = if (cinema.active) cinema.isPlaying() else null,
            cinemaError = cinema.error,
            cinemaKeys = screens.gate.shell() != null,
            mouseConnected = mouseConnected(),
            mouseReleasedByUser = mouseReleasedByUser,
            quality = screens.quality,
            rawInputActive = rawInput.active,
            rawInputDevices = rawInput.devices,
            rawReleased = rawReleased,
            input = inputStatusNow(),
            keyboardInGlasses = screens.keyboardVisible,
            sharpen = screens.sharpen,
            background = screens.background,
            dimWithApps = screens.dimWithApps,
            dimNoApps = screens.dimNoApps,
            showMarkers = screens.showMarkers,
            notificationsOn = dev.avijit.orbit.notify.NotificationFeed.enabled,
            notificationsHideText = dev.avijit.orbit.notify.NotificationFeed.hideText,
            notificationAccess = notificationAccessCached,
            stereo = screens.stereo,
            ipdMm = screens.ipdM * 1000f,
            focusedContent = screens.focused?.let { screens.content[it] } ?: dev.avijit.orbit.render.ContentMode.FLAT,
            pointerVisible = screens.pointerVisible,
            recentApps = screens.panels.values.mapNotNull { it.component }.toSet().let { open ->
                screens.recentApps.filter { it !in open }.map { entryFor(it) }
            },
            dockEnabled = screens.dockEnabled,
            widgetsEnabled = screens.widgetsEnabled,
            calendarEnabled = screens.calendarEnabled,
            windowBarEnabled = screens.windowBarEnabled,
            orbitApps = screens.orbitApps,
            orbitStill = screens.orbitStill,
            eyeBreakMin = eyeBreak.intervalMin,
            batteryPct = screens.batteryPct,
            focusedImeLocal = screens.focused?.let { screens.imeLocal[it] },
            version = BuildConfig.VERSION_NAME,
        )
    }

    private val labels = HashMap<String, String>()

    private fun appLabel(pkg: String): String = labels.getOrPut(pkg) {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    private fun diagnostics(): String = buildString {
        val s = status.state
        val ypr = headPose().yawPitchRollDeg()
        appendLine("state: $s")
        appendLine("calibrated: ${filter.isCalibrated}   mode: $mode")
        appendLine("yaw %.1f°  pitch %.1f°  roll %.1f°".format(ypr[0], ypr[1], ypr[2]))
        appendLine("raw: ${source.lastRaw?.joinToString(", ") { "%.3f".format(it) } ?: "—"}")
        appendLine("display: ${glassesDisplay()?.let { "${it.name} ${it.mode.physicalWidth}x${it.mode.physicalHeight}" } ?: "none"}")
        appendLine("gyro unit: ${if (source.gyroInDegrees) "deg/s" else "rad/s"}   firmware: ${source.glassesVersion ?: "—"}")
        appendLine("helper: ${screens.gate.state} (${screens.gate.via ?: "none"})   mirror: ${screens.mirrorOn}")
        appendLine("keys sent: $keysSent   key problems: $keyProblems")
        appendLine("raw input: ${if (rawInput.active) "on" else "off"}${if (rawReleased) " (released)" else ""}  devices: ${rawInput.devices.ifEmpty { "—" }}")
        screens.panels.toSortedMap().forEach { (slot, p) -> appendLine("slot $slot: display ${p.displayId} ${p.component ?: "—"} live=${p.active}") }
        append("cursor yaw %.1f° pitch %.1f°".format(screens.cursor.yawDeg, screens.cursor.pitchDeg))
    }

    // ---------------------------------------------------------------- actions

    override fun recenter() {
        filter.recenter()
        screens.focused?.let { screens.focusSlot(it) } ?: screens.cursor.aimAt(0f, 0f)
        DiagLog.i(TAG, "recenter")
    }

    override fun togglePinned() {
        mode = if (mode == AnchorMode.PINNED) AnchorMode.FOLLOW else AnchorMode.PINNED
        DiagLog.i(TAG, "mode -> $mode")
        state.value = buildState()
    }

    override fun onTrackpad(action: TrackpadAction) = screens.onTrackpad(action)
    override fun back() = screens.back()
    override fun switchScreen() = screens.switchScreen()
    override fun typeText(text: String) = screens.typeText(text)
    override fun enter() = screens.enter()
    override fun backspace() = screens.backspace()
    override fun focus(slot: Int) = screens.focusSlot(slot)
    override fun closeScreen(slot: Int) = screens.closeScreen(slot)

    override fun moveScreen(slot: Int) {
        screens.focusSlot(slot)
        screens.setMoveMode(true)
    }

    override fun setMoveMode(on: Boolean) = screens.setMoveMode(on)
    override fun resetScreen() = screens.resetScreen()
    override fun resetLayout() = screens.resetLayout()
    override fun setLayout(p: LayoutParams) = screens.setLayout(p)
    override fun openApp(component: String) = screens.openApp(component)

    /** Orbit Browser: a screen in the glasses when screens can open, otherwise on the phone. */
    override fun openBrowser() {
        val screensReady = screens.gate.state == HelperState.READY && !screens.mirrorOn && glassesDisplay() != null
        if (screensReady) screens.openBrowser() else dev.avijit.orbit.browser.BrowserHub.openOnPhone(this)
    }

    private fun browserEntry() = AppEntry(dev.avijit.orbit.browser.BrowserHub.LABEL, dev.avijit.orbit.browser.BrowserHub.COMPONENT, packageName)

    private fun entryFor(component: String) =
        if (component == dev.avijit.orbit.browser.BrowserHub.COMPONENT) browserEntry()
        else AppEntry(appLabel(component.substringBefore('/')), component, component.substringBefore('/'))
    override fun reopenLast() = screens.reopenLast()

    override fun helperAction() {
        when (screens.gate.state) {
            HelperState.STOPPED -> screens.gate.startHelper()
            HelperState.NEEDS_SETUP, HelperState.STARTING, HelperState.READY -> Unit
        }
    }

    override fun startHelper() = screens.gate.startHelper()

    override fun beginPairing() {
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // The code is typed into a notification: ask first, continue in onRequestPermissionsResult.
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            return
        }
        startPairingNow()
    }

    private fun startPairingNow() {
        PairingService.begin(this)
        openWirelessDebugging()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CALENDAR) {
            screens.setCalendarEnabled(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            state.value = buildState()
            return
        }
        if (requestCode != REQ_NOTIFY) return
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            // Still possible: split screen with Settings and type the code on the Setup screen.
            android.widget.Toast.makeText(this, "Without notifications, use split screen and type the code on the Setup screen", android.widget.Toast.LENGTH_LONG).show()
        }
        startPairingNow()
    }

    override fun submitPairingCode(code: String) = HelperSetup.submitCode(this, code)

    override fun cancelPairing() {
        HelperSetup.cancel()
        PairingService.stop(this)
    }

    override fun openWirelessDebugging() {
        // Opens Developer options with "Wireless debugging" highlighted (as Shizuku does).
        val args = Bundle().apply { putString(":settings:fragment_args_key", "toggle_adb_wireless") }
        val dev = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            .putExtra(":settings:show_fragment_args", args)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(dev)
        } catch (e: ActivityNotFoundException) {
            DiagLog.e(TAG, "cannot open Developer options", e)
            openAboutPhone()
        }
    }

    override fun openAutoBlocker() {
        // Auto Blocker's own page first; on Samsung models without it, Security and privacy, where
        // Auto Blocker is listed (a missing screen throws and the next one is tried).
        val candidates = listOf(
            // Auto Blocker's own page (Samsung "rampart" settings, One UI 6 and later).
            Intent("com.samsung.android.rampart.action.MAIN_SETTING_ACTIVITY"),
            Intent(Settings.ACTION_SECURITY_SETTINGS).putExtra(":settings:fragment_args_key", "auto_blocker"),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in candidates) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(i)
                DiagLog.i(TAG, "auto blocker: opened ${i.action ?: i.component?.className}")
                return
            } catch (e: Exception) {
                DiagLog.i(TAG, "auto blocker: ${i.action ?: i.component?.className} not on this phone")
            }
        }
    }

    override fun openAboutPhone() {
        try {
            startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            DiagLog.e(TAG, "cannot open About phone", e)
        }
    }

    private fun devOptionsOn(): Boolean =
        Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    override fun setMirror(on: Boolean) {
        if (on) capture.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        else MirrorService.stop(this)
    }

    override fun setCompass(on: Boolean) {
        filter.useMag = on
        savePrefs()
    }

    override fun setInvert(yaw: Boolean, pitch: Boolean, roll: Boolean) {
        axisFix = AxisFix(yaw, pitch, roll)
        savePrefs()
    }

    override fun retryGlasses() = source.retry()

    override fun hideKeyboard() = screens.hideKeyboard()

    override fun setDockEnabled(on: Boolean) = screens.setDockEnabled(on)
    override fun setWidgetsEnabled(on: Boolean) = screens.setWidgetsEnabled(on)
    override fun setWindowBarEnabled(on: Boolean) = screens.setWindowBarEnabled(on)
    override fun setOrbitApps(on: Boolean) = screens.setOrbitApps(on)
    override fun setOrbitStill(on: Boolean) = screens.setOrbitStill(on)
    override fun setEyeBreakMinutes(min: Int) {
        eyeBreak.intervalMin = min
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("eyeBreakMin", min).apply()
        state.value = buildState()
    }
    override fun setCalendarEnabled(on: Boolean) {
        if (on && checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.READ_CALENDAR), REQ_CALENDAR)
            return
        }
        screens.setCalendarEnabled(on)
        state.value = buildState()
    }

    override fun setPointerVisible(on: Boolean) {
        screens.pointerVisible = on
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("pointer", on).apply()
        state.value = buildState()
    }

    override fun releaseMouseCapture() = releaseMouse()

    override fun setBackground(mode: dev.avijit.orbit.render.BackgroundMode, withApps: Float, noApps: Float, markers: Boolean) {
        screens.background = mode
        screens.dimWithApps = withApps.coerceIn(0f, 1f)
        screens.dimNoApps = noApps.coerceIn(0f, 1f)
        screens.showMarkers = markers
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("bg", mode.name).putFloat("bgApps", screens.dimWithApps)
            .putFloat("bgIdle", screens.dimNoApps).putBoolean("markers", markers).apply()
        state.value = buildState()
    }

    override fun set3D(on: Boolean) = set3D(on, null)

    /** Asks the glasses to change mode; rendering follows what they report back, not the request. */
    private fun set3D(on: Boolean, then: (() -> Unit)?) {
        if (on && cinema.active) return
        val mode = if (on) dev.avijit.orbit.glasses.VitureImuSource.MODE_3D_SBS else dev.avijit.orbit.glasses.VitureImuSource.MODE_2D
        val started = source.setDisplayMode(mode) { result ->
            ui.post {
                if (result != 0) DiagLog.e(TAG, "glasses refused display mode 0x${mode.toString(16)} (code $result)")
                if (!isDestroyed) state.value = buildState()
                // The mode switch can remove/re-add the external display: let it settle first.
                then?.let { ui.postDelayed(it, MODE_SETTLE_MS) }
            }
        }
        if (!started) {
            DiagLog.e(TAG, "3D needs the glasses connected and head tracking running")
            then?.invoke()
        }
        state.value = buildState()
    }

    override fun setIpd(mm: Float) {
        screens.ipdM = (mm / 1000f).coerceIn(0.055f, 0.075f)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat("ipd", screens.ipdM).apply()
        state.value = buildState()
    }

    override fun setContent(slot: Int, mode: dev.avijit.orbit.render.ContentMode) {
        screens.setContent(slot, mode)
        state.value = buildState()
    }

    override fun setNotifications(on: Boolean, hideText: Boolean) {
        dev.avijit.orbit.notify.NotificationFeed.enabled = on
        dev.avijit.orbit.notify.NotificationFeed.hideText = hideText
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("notify", on).putBoolean("notifyHide", hideText).apply()
        state.value = buildState()
    }

    override fun openVideoGuides() {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dev.avijit.orbit.ui.VIDEO_GUIDES_URL))) }
            .onFailure { DiagLog.e(TAG, "cannot open video guides", it) }
    }

    override fun setQuality(q: ScreenQuality) {
        screens.quality = q
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("quality", q.name).apply()
        state.value = buildState()
    }

    override fun startCinema(component: String) {
        val display = glassesDisplay() ?: run {
            DiagLog.e(TAG, "cinema: no glasses display")
            return
        }
        val shellLaunch: ((Int, String) -> String?)? = screens.gate.shell()?.let { shell ->
            { id, comp ->
                val out = runCatching { shell.launch(id, comp) }.getOrElse { "Error: ${it.message}" }
                if (out.startsWith("exit 0")) null else "Couldn't open it on the glasses: ${out.substringAfter('\n').take(120)}"
            }
        }
        screens.pointerUp() // never leave a finger down on a screen when Cinema takes over
        // The app can't be on an OrbitWalker screen and the glasses display at once: close it here first.
        val pkg = component.substringBefore('/')
        screens.panels.filterValues { it.component?.substringBefore('/') == pkg }.keys.forEach { screens.closeScreen(it) }
        if (screens.stereo) {
            // Cinema is a plain 2D screen: switch back first, then launch once the display settles.
            set3D(false) { startCinema(component) }
            return
        }
        presentation?.dismiss()
        presentation = null
        cinema.start(this, component, display.displayId, shellLaunch, cinemaWorker) {
            cinema.error?.let { DiagLog.e(TAG, it); refreshPresentation() }
            state.value = buildState()
        }
    }

    override fun cinemaNav(keyCode: Int) {
        val display = glassesDisplay() ?: return
        val shell = screens.gate.shell() ?: return
        cinemaWorker.post {
            runCatching {
                shell.keyEvent(display.displayId, KeyEvent.ACTION_DOWN, keyCode, 0, 0)
                shell.keyEvent(display.displayId, KeyEvent.ACTION_UP, keyCode, 0, 0)
            }
        }
    }

    override fun cinemaTapCentre() {
        val display = glassesDisplay() ?: return
        val shell = screens.gate.shell() ?: return
        val w = display.mode.physicalWidth / 2f; val h = display.mode.physicalHeight / 2f
        cinemaWorker.post { runCatching { shell.tap(display.displayId, w, h) } }
    }

    override fun exitCinema() {
        if (!cinema.active) return
        cinema.stop()
        refreshPresentation()
        state.value = buildState()
    }

    override fun cinemaPlayPause() = cinema.playPause()
    override fun cinemaSeek(deltaMs: Long) = cinema.seekBy(deltaMs)
    override fun cinemaVolume(up: Boolean) = cinema.volume(up)

    override fun openNotificationAccess() {
        runCatching { startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            .onFailure { DiagLog.e(TAG, "cannot open notification access settings", it) }
    }

    override fun launchableAppsAll(): List<AppEntry> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcher, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { AppEntry(it.loadLabel(packageManager).toString(), "${it.activityInfo.packageName}/${it.activityInfo.name}", it.activityInfo.packageName) }
            .sortedBy { it.label.lowercase() }
            .let { listOf(browserEntry()) + it }
    }

    override fun shareLogs() = startActivity(DiagLog.shareIntent(this))

    override fun setFullscreen(on: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun launchableApps(): List<AppEntry> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val open = screens.openComponents()
        return packageManager.queryIntentActivities(launcher, 0)
            .filter { it.activityInfo.packageName != packageName }
            .filter { "${it.activityInfo.packageName}/${it.activityInfo.name}" !in open }
            .map { AppEntry(it.loadLabel(packageManager).toString(), "${it.activityInfo.packageName}/${it.activityInfo.name}", it.activityInfo.packageName) }
            .sortedBy { it.label.lowercase() }
            .let { listOf(browserEntry()) + it }
    }

    // ---------------------------------------------------------------- prefs

    private fun loadPrefs() {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (p.getInt("axisMapping", 1) < AXIS_MAPPING_VERSION) {
            // The Pro 2 axis fix is built in; old Invert switches would undo it.
            p.edit().remove("invertYaw").remove("invertPitch").remove("invertRoll")
                .putInt("axisMapping", AXIS_MAPPING_VERSION).apply()
            DiagLog.i(TAG, "axis mapping v$AXIS_MAPPING_VERSION: invert switches reset")
        }
        axisFix = AxisFix(p.getBoolean("invertYaw", false), p.getBoolean("invertPitch", false), p.getBoolean("invertRoll", false))
        filter.useMag = p.getBoolean("useMag", true)
        screens.pointerVisible = p.getBoolean("pointer", true)
        screens.background = runCatching { dev.avijit.orbit.render.BackgroundMode.valueOf(p.getString("bg", null)!!) }
            .getOrDefault(dev.avijit.orbit.render.BackgroundMode.ORBIT)
        screens.dimWithApps = p.getFloat("bgApps", 0.25f)
        screens.dimNoApps = p.getFloat("bgIdle", 1f)
        screens.showMarkers = p.getBoolean("markers", false)
        screens.ipdM = p.getFloat("ipd", dev.avijit.orbit.render.Stereo.DEFAULT_IPD_M)
        screens.sharpen = p.getFloat("sharpen", 0.3f).coerceAtMost(0.45f)
        dev.avijit.orbit.notify.NotificationFeed.enabled = p.getBoolean("notify", true)
        dev.avijit.orbit.notify.NotificationFeed.hideText = p.getBoolean("notifyHide", false)
        screens.quality = runCatching { ScreenQuality.valueOf(p.getString("quality", null)!!) }.getOrDefault(ScreenQuality.SHARP)
        eyeBreak.intervalMin = p.getInt("eyeBreakMin", 20)
    }

    private fun savePrefs() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean("invertYaw", axisFix.invertYaw)
            .putBoolean("invertPitch", axisFix.invertPitch)
            .putBoolean("invertRoll", axisFix.invertRoll)
            .putBoolean("useMag", filter.useMag)
            .apply()
    }

    private companion object {
        const val TAG = "main"
        const val REQ_NOTIFY = 41
        const val REQ_CALENDAR = 42
        const val PREFS = "orbit"
        const val PREDICT_AHEAD_NS = 20_000_000L
        const val AXIS_MAPPING_VERSION = 2
        const val UI_REFRESH_MS = 150L
        const val SCROLL_PX_PER_NOTCH = 40f
        const val MODE_SETTLE_MS = 800L
        const val RAW_SYNC_MS = 500L
        val POSITION_NAMES = arrayOf(
            "Centre", "Right", "Left", "Far right", "Far left",
            "Top centre", "Top right", "Top left", "Top far right", "Top far left",
        )
    }
}
