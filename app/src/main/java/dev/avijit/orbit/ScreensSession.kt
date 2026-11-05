package dev.avijit.orbit

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.KeyEvent
import android.view.Surface
import dev.avijit.orbit.diag.DiagLog
import dev.avijit.orbit.input.ScrollAccumulator
import dev.avijit.orbit.input.TrackpadAction
import dev.avijit.orbit.layout.CursorRay
import dev.avijit.orbit.layout.LayoutParams
import dev.avijit.orbit.layout.ScreenLayout
import dev.avijit.orbit.layout.ScreenPose
import dev.avijit.orbit.layout.SlotOffset
import dev.avijit.orbit.layout.hitTest
import dev.avijit.orbit.layout.projectOnto
import dev.avijit.orbit.render.PanelHost
import dev.avijit.orbit.render.SceneSnapshot
import dev.avijit.orbit.render.ScreenDraw
import dev.avijit.orbit.shell.AmStart
import dev.avijit.orbit.shell.IOrbitShell
import dev.avijit.orbit.shell.HelperGate
import dev.avijit.orbit.shell.HelperState

/**
 * Process-wide state for up to four screens: which app is in which slot, the arrangement,
 * the shared cursor, Move mode and Mirror mode. Binder calls run on one worker thread.
 */
class ScreensSession private constructor(private val context: Context) : PanelHost {

    val gate = HelperGate(context)
    val cursor = CursorRay()

    /** Main thread; called whenever something the UI shows changes. */
    var listener: (() -> Unit)? = null

    @Volatile var status: String = "No screens open"
        private set
    @Volatile var panels: Map<Int, PanelState> = emptyMap()
        private set
    @Volatile var focused: Int? = null
        private set
    @Volatile var layout: LayoutParams = LayoutParams()
        private set
    @Volatile var offsets: List<SlotOffset> = List(ScreenLayout.SLOTS) { SlotOffset() }
        private set
    @Volatile var moveMode = false
        private set
    @Volatile var mirrorOn = false
        private set
    /** Width / height of the mirrored phone display. */
    @Volatile var mirrorAspect = 0.5f
    @Volatile var lastScreens: List<String> = emptyList()
        private set
    /** Per slot: true when the phone keyboard appears inside that screen. */
    @Volatile var imeLocal: Map<Int, Boolean> = emptyMap()
        private set
    @Volatile var pointerVisible: Boolean = true
    /** Resolution of new app screens. Higher = sharper text in the glasses, more GPU/battery. */
    @Volatile var quality: ScreenQuality = ScreenQuality.SHARP
    /** Uptime of the last click, for the pointer ripple. */
    @Volatile var lastClickMs: Long = 0L
        private set

    /** Turns a package name into the app's display name for status lines. */
    @Volatile var labeler: (String) -> String = { it }

    /** Mirror service hook: receives slot 0's surface whenever it changes (null = gone). */
    @Volatile var mirrorSink: ((Surface?, (Int, Int) -> Unit) -> Unit)? = null

    private val surfaces = arrayOfNulls<Surface>(ScreenLayout.SLOTS)
    private val sizers = arrayOfNulls<(Int, Int) -> Unit>(ScreenLayout.SLOTS)
    private val scroll = ScrollAccumulator(SCROLL_PX_PER_STEP)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Handler(HandlerThread("orbit-screens").apply { start() }.looper)
    private val prefs = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)
    private var started = false

    init {
        prefs.getString(KEY_LAYOUT, null)?.let(ScreenLayout::decode)?.let { (p, o) -> layout = p; offsets = o }
        lastScreens = prefs.getString(KEY_LAST, "")!!.split('|').filter { it.isNotBlank() }
    }

    /** Screens whose app closed itself (e.g. Back from its first page) close like a window. */
    private val watchApps = object : Runnable {
        override fun run() {
            val shell = gate.shell()
            if (shell != null) {
                val now = android.os.SystemClock.uptimeMillis()
                panels.filterValues { it.active }.forEach { (slot, p) ->
                    if (now - (launchedAt[slot] ?: now) < APP_GRACE_MS) return@forEach
                    val n = runCatching { shell.taskCount(p.displayId) }.getOrDefault(-1)
                    if (n == 0) {
                        val name = p.component?.let(::label) ?: "App"
                        DiagLog.i(TAG, "slot $slot: $name closed itself")
                        releaseDisplay(slot)
                        removeSlot(slot)
                        rememberScreens()
                        report("$name closed")
                    }
                }
            }
            worker.postDelayed(this, APP_WATCH_MS)
        }
    }
    private val launchedAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    fun start() {
        if (started) return
        started = true
        worker.postDelayed(watchApps, APP_WATCH_MS)
        worker.post(widgetTick)
        gate.onSwitchAway = { old ->
            val ids = panels.values.map { it.displayId }.filter { it >= 0 }
            work { ids.forEach { id -> runCatching { old.releaseDisplay(id) } } }
        }
        gate.listener = { state ->
            if (state == HelperState.READY) {
                work { panels.filterValues { it.wantsReopen }.keys.forEach { openOnPanel(it) } }
            } else {
                work {
                    if (panels.values.any { it.displayId >= 0 }) {
                        panels = panels.mapValues { it.value.onShellLost() }
                        report("The helper stopped. Your screens reopen when it's back (tap Start on Home after a phone restart).", error = true)
                    }
                }
            }
            notifyChanged()
        }
        gate.start()
    }

    // ---------------------------------------------------------------- screens

    fun openApp(component: String) {
        if (mirrorOn) return report("Turn off Mirror mode to open app screens", error = true)
        work {
            val open = panels.filterValues { it.component != null }.keys
            val slot = Slots.nextFree(open) ?: return@work report("All ${ScreenLayout.SLOTS} screens are in use. Close one first.", error = true)
            DiagLog.i(TAG, "open $component in slot $slot")
            panels = panels + (slot to PanelState(component = component))
            focusSlot(slot)
            openOnPanel(slot)
            rememberScreens()
            recentApps = dev.avijit.orbit.layout.AppDock.push(recentApps, component)
            prefs.edit().putString(KEY_RECENT, recentApps.joinToString("|")).apply()
        }
    }

    fun closeScreen(slot: Int) = work {
        releaseDisplay(slot)
        removeSlot(slot)
        rememberScreens()
        report(if (panels.isEmpty()) "No screens open" else "Closed screen")
    }

    fun closeAll() = work {
        panels.keys.toList().forEach { releaseDisplay(it) }
        panels = emptyMap()
        content = emptyMap()
        focused = null
        moveMode = false
        report("No screens open")
    }

    fun reopenLast() {
        val open = panels.values.mapNotNull { it.component }.toSet()
        lastScreens.filter { it !in open }.forEach { openApp(it) }
    }

    /** Worker thread. Drops [slot]; focus (and the cursor) move to the nearest remaining screen. */
    private fun removeSlot(slot: Int) {
        panels = panels - slot
        content = content - slot
        imeLocal = imeLocal - slot
        if (focused == slot) {
            val next = Slots.focusAfterClose(slot, panels.keys)
            if (next != null) focusSlot(next) else focused = null
        }
        if (focused == null) moveMode = false
        notifyChanged()
    }

    fun focusSlot(slot: Int) {
        if (slot !in panels.keys) return
        focused = slot
        pose(slot).let { cursor.aimAt(it.yawDeg, it.pitchDeg) }
        notifyChanged()
    }

    fun switchScreen() {
        val open = activeSlots()
        Slots.nextInArc(focused, open)?.let { focusSlot(it) }
    }

    // ---------------------------------------------------------------- input

    fun onTrackpad(a: TrackpadAction) {
        if (mirrorOn) return
        if (moveMode) return moveFocused(a)
        when (a) {
            is TrackpadAction.Move -> cursor.moveByPx(a.dx, a.dy)
            TrackpadAction.Tap -> {
                lastClickMs = android.os.SystemClock.uptimeMillis()
                if (keyboardTap()) return
                if (dockTap()) return
                if (barTap()) return
                val hit = currentHit() ?: return
                if (focused != hit.slot) { focused = hit.slot; notifyChanged() }
                val id = panels[hit.slot]?.displayId ?: return
                val p = panels[hit.slot] ?: return
                val x = hit.u * p.width; val y = hit.v * p.height
                work { call("Tap") { it.tap(id, x, y) } }
            }
            is TrackpadAction.Scroll -> {
                val notches = scroll.add(a.dy)
                if (notches == 0) return
                if (barScroll(notches)) return
                val hit = currentHit() ?: return
                val id = panels[hit.slot]?.displayId ?: return
                val p = panels[hit.slot] ?: return
                val x = hit.u * p.width; val y = hit.v * p.height
                work { call("Scroll") { it.scroll(id, x, y, -notches.toFloat()) } }
            }
            is TrackpadAction.Pinch -> Unit
        }
    }

    private fun moveFocused(a: TrackpadAction) {
        val f = focused ?: return
        val o = offsets[f]
        val next = when (a) {
            is TrackpadAction.Move -> o.copy(yawDeg = o.yawDeg + a.dx * MOVE_DEG_PER_PX, pitchDeg = o.pitchDeg - a.dy * MOVE_DEG_PER_PX)
            is TrackpadAction.Scroll -> o.copy(distanceM = o.distanceM - a.dy * PUSH_M_PER_PX)
            is TrackpadAction.Pinch -> o.copy(scale = (o.scale * a.scale).coerceIn(0.5f, 2f))
            TrackpadAction.Tap -> return
        }
        setOffset(f, ScreenLayout.clampOffset(f, layout, next))
        pose(f).let { cursor.aimAt(it.yawDeg, it.pitchDeg) }
    }

    /** Forwards a hardware key to the focused screen. Never logs the key. */
    fun forwardKey(e: KeyEvent) {
        val id = focused?.let { panels[it]?.displayId }?.takeIf { it >= 0 } ?: return
        val action = e.action; val code = e.keyCode; val meta = e.metaState; val repeat = e.repeatCount
        val down = e.downTime; val at = e.eventTime
        work { call("Key") { it.keyEventTimed(id, action, code, meta, repeat, down, at) } }
    }

    /** Clicks at the pointer (Bluetooth mouse or trackpad tap). */
    fun clickAtPointer() = onTrackpad(TrackpadAction.Tap)

    // ---------------------------------------------------------------- mouse press / drag / release

    private class Press(val slot: Int, val displayId: Int, val width: Int, val height: Int, val downTime: Long, var x: Float, var y: Float)
    private var press: Press? = null
    private var pressOnKeyboard = false
    private var pressOnDock = false
    private var pressOnBar = false
    private class BarDrag(val slot: Int, val startYaw: Float, val startPitch: Float, val start: SlotOffset)
    @Volatile private var barDrag: BarDrag? = null

    /** Mouse button down: presses the app under the pointer (so drag, select and long-press work). */
    fun pointerDown() {
        if (mirrorOn || moveMode) return
        lastClickMs = android.os.SystemClock.uptimeMillis()
        val all = activeSlots().associateWith { pose(it) }
        val hit = hitTest(cursor.direction(), all + overlays()) ?: return
        if (hit.slot == KEYBOARD_SLOT) { pressOnKeyboard = true; return }
        if (hit.slot == DOCK_SLOT) { pressOnDock = true; return }
        if (hit.slot == BAR_SLOT) {
            val f = focused ?: return
            if (dev.avijit.orbit.layout.WindowBar.buttonAt(hit.u) == dev.avijit.orbit.layout.WindowBar.Button.MOVE) {
                barDrag = BarDrag(f, cursor.yawDeg, cursor.pitchDeg, offsets[f])
            } else pressOnBar = true
            return
        }
        val p = panels[hit.slot] ?: return
        if (p.displayId < 0) return
        if (focused != hit.slot) { focused = hit.slot; notifyChanged() }
        val pr = Press(hit.slot, p.displayId, p.width, p.height, android.os.SystemClock.uptimeMillis(), hit.u * p.width, hit.v * p.height)
        press = pr
        work { call("Press") { it.touch(pr.displayId, 0, pr.x, pr.y, pr.downTime) } }
    }

    /** Pointer moved while pressed: drag on the pressed screen (tracks past its edges, clamped). */
    fun pointerDrag() {
        barDrag?.let { d ->
            // Moving a screen by its title bar: it follows the pointer; saved on release.
            val o = dev.avijit.orbit.layout.WindowBar.dragged(d.start, d.startYaw, d.startPitch, cursor.yawDeg, cursor.pitchDeg)
            setOffset(d.slot, ScreenLayout.clampOffset(d.slot, layout, o), save = false)
            return
        }
        val pr = press ?: return
        if (panels[pr.slot]?.displayId != pr.displayId) { press = null; return } // screen closed mid-drag
        val uv = projectOnto(cursor.direction(), pose(pr.slot)) ?: return
        pr.x = (uv.first * pr.width).coerceIn(0f, pr.width - 1f)
        pr.y = (uv.second * pr.height).coerceIn(0f, pr.height - 1f)
        val x = pr.x; val y = pr.y
        work { call("Drag") { it.touch(pr.displayId, 2, x, y, pr.downTime) } }
    }

    fun pointerUp() {
        barDrag?.let { barDrag = null; saveLayout(); report("Screen placed"); notifyChanged(); return }
        if (pressOnBar) { pressOnBar = false; barTap(); return }
        if (pressOnKeyboard) { pressOnKeyboard = false; keyboardTap(); return }
        if (pressOnDock) { pressOnDock = false; dockTap(); return }
        val pr = press ?: return
        press = null
        if (panels[pr.slot]?.displayId != pr.displayId) return
        val x = pr.x; val y = pr.y
        work { call("Release") { it.touch(pr.displayId, 1, x, y, pr.downTime) } }
    }

    fun hideKeyboard() = back()

    fun typeText(text: String) = withFocusedDisplay("Typing") { shell, id -> shell.text(id, text) }
    fun enter() = withFocusedDisplay("Enter") { shell, id -> shell.key(id, KeyEvent.KEYCODE_ENTER) }
    fun backspace() = withFocusedDisplay("Backspace") { shell, id -> shell.key(id, KeyEvent.KEYCODE_DEL) }
    fun back() = withFocusedDisplay("Back") { shell, id -> shell.key(id, KeyEvent.KEYCODE_BACK) }

    private fun withFocusedDisplay(what: String, block: (IOrbitShell, Int) -> Boolean) {
        val id = focused?.let { panels[it]?.displayId }?.takeIf { it >= 0 } ?: return report("$what: no screen focused", error = true)
        work { call(what) { block(it, id) } }
    }

    // ---------------------------------------------------------------- layout

    fun setLayout(p: LayoutParams) {
        layout = p.clamped()
        saveLayout()
        notifyChanged()
    }

    fun setMoveMode(on: Boolean) {
        moveMode = on && focused != null
        notifyChanged()
    }

    fun resetScreen() {
        focused?.let { setOffset(it, SlotOffset()) }
    }

    fun resetLayout() {
        layout = LayoutParams()
        offsets = List(ScreenLayout.SLOTS) { SlotOffset() }
        saveLayout()
        notifyChanged()
    }

    private fun setOffset(slot: Int, o: SlotOffset, save: Boolean = true) {
        offsets = offsets.toMutableList().also { it[slot] = o }
        if (save) saveLayout()
        notifyChanged()
    }

    fun pose(slot: Int): ScreenPose {
        val base = ScreenLayout.pose(slot, layout, offsets[slot])
        if (!(mirrorOn && slot == 0)) return base
        // Mirror keeps a 16:10 screen's height and takes the phone's shape.
        return base.copy(widthM = base.heightM * mirrorAspect)
    }

    // ---------------------------------------------------------------- mirror

    /** Called by the mirror controller when capture starts or stops. */
    fun setMirror(on: Boolean, ready: (() -> Unit)? = null) = work {
        if (on) {
            // Slot 0's Surface can have only one producer: release every app display first.
            panels.keys.toList().forEach { releaseDisplay(it) }
            panels = emptyMap()
            content = emptyMap()
            focused = null
            moveMode = false
        } else {
            sizers[0]?.invoke(quality.width, quality.height)
        }
        mirrorOn = on
        report(if (on) "Mirroring your phone screen" else "Mirror off")
        ready?.let { main.post(it) }
    }

    fun openComponents(): Set<String> = panels.values.mapNotNull { it.component }.toSet()

    fun mirrorSurface(): Pair<Surface?, ((Int, Int) -> Unit)?> = surfaces[0] to sizers[0]

    // ---------------------------------------------------------------- rendering

    @Volatile var background = dev.avijit.orbit.render.BackgroundMode.ORBIT
    /** Orbit background: the family's apps as labelled planets ("Coming soon"). */
    @Volatile var orbitApps = prefs.getBoolean(KEY_ORBIT_APPS, true)
        private set

    /** Comfort option: freeze the planets. */
    @Volatile var orbitStill = prefs.getBoolean(KEY_ORBIT_STILL, false)
        private set

    fun setOrbitStill(on: Boolean) { orbitStill = on; prefs.edit().putBoolean(KEY_ORBIT_STILL, on).apply(); notifyChanged() }

    fun setOrbitApps(on: Boolean) { orbitApps = on; prefs.edit().putBoolean(KEY_ORBIT_APPS, on).apply(); notifyChanged() }
    /** Background brightness with app screens open / with none (0..1). */
    @Volatile var dimWithApps = 0.25f
    @Volatile var dimNoApps = 1f
    @Volatile var showMarkers = false
    @Volatile var sharpen = 0.3f
    /** Reads whether the glasses are really in 3D side-by-side (the glasses are the source of truth). */
    @Volatile var stereoSource: () -> Boolean = { false }
    val stereo: Boolean get() = stereoSource()
    @Volatile var ipdM = dev.avijit.orbit.render.Stereo.DEFAULT_IPD_M
    /** Per-slot 3D content layout. */
    @Volatile var content: Map<Int, dev.avijit.orbit.render.ContentMode> = emptyMap()

    // ---------------------------------------------------------------- in-glasses keyboard

    @Volatile var keyboardVisible = false
        private set
    @Volatile private var kbPage = dev.avijit.orbit.layout.VrKeyboard.Page.LETTERS
    @Volatile private var kbShift = false
    @Volatile private var kbArt: android.graphics.Bitmap? = null

    fun setKeyboardVisible(on: Boolean) {
        keyboardVisible = on && focused != null
        if (keyboardVisible) redrawKeyboard(null)
        notifyChanged()
    }

    private val unpress = Runnable { if (keyboardVisible) redrawKeyboard(null) }

    private fun redrawKeyboard(pressed: String?) {
        kbArt = dev.avijit.orbit.render.KeyboardArt.render(kbPage, kbShift, pressed)
    }

    /** Below the focused screen, slightly nearer, nearly as wide. */
    fun keyboardPose(): ScreenPose? {
        if (!keyboardVisible) return null
        val f = focused ?: return null
        if (f !in activeSlots()) return null
        val p = pose(f)
        val w = p.widthM * 0.9f
        val h = w / dev.avijit.orbit.layout.VrKeyboard.ASPECT
        val dist = (p.distanceM - 0.08f).coerceAtLeast(0.5f)
        val drop = Math.toDegrees(kotlin.math.atan2((p.heightM / 2 + h / 2 + 0.04f).toDouble(), p.distanceM.toDouble())).toFloat()
        return ScreenPose(p.yawDeg, p.pitchDeg - drop, dist, w, h)
    }

    /** Returns true if the tap landed on the keyboard (and was handled there). */
    private fun keyboardTap(): Boolean {
        val kb = keyboardPose() ?: return false
        val all = activeSlots().associateWith { pose(it) } + (KEYBOARD_SLOT to kb)
        val hit = hitTest(cursor.direction(), all) ?: return false
        if (hit.slot != KEYBOARD_SLOT) return false
        val key = dev.avijit.orbit.layout.VrKeyboard.keyAt(hit.u, hit.v, kbPage) ?: return true
        when (key.action) {
            dev.avijit.orbit.layout.VrKeyboard.Action.SHIFT -> kbShift = !kbShift
            dev.avijit.orbit.layout.VrKeyboard.Action.PAGE -> kbPage =
                if (kbPage == dev.avijit.orbit.layout.VrKeyboard.Page.LETTERS) dev.avijit.orbit.layout.VrKeyboard.Page.SYMBOLS else dev.avijit.orbit.layout.VrKeyboard.Page.LETTERS
            dev.avijit.orbit.layout.VrKeyboard.Action.HIDE -> { setKeyboardVisible(false); return true }
            dev.avijit.orbit.layout.VrKeyboard.Action.TYPE -> {
                val letter = key.label.length == 1 && key.label[0].isLetter()
                val shift = key.shift || (kbShift && letter)
                typeKey(key.keyCode, shift)
                if (letter && kbShift) kbShift = false
            }
        }
        redrawKeyboard(key.label)
        main.removeCallbacks(unpress)
        main.postDelayed(unpress, 140)
        return true
    }

    private fun typeKey(code: Int, shift: Boolean) {
        val id = focused?.let { panels[it]?.displayId }?.takeIf { it >= 0 } ?: return
        val meta = if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        work {
            call("Key") { s ->
                val t = android.os.SystemClock.uptimeMillis()
                val scan = dev.avijit.orbit.input.LinuxKeys.toLinux(code)
                val dev = android.view.KeyCharacterMap.VIRTUAL_KEYBOARD
                s.keyEventFull(id, KeyEvent.ACTION_DOWN, code, meta, 0, t, t, scan, dev) &&
                    s.keyEventFull(id, KeyEvent.ACTION_UP, code, meta, 0, t, android.os.SystemClock.uptimeMillis(), scan, dev)
            }
        }
    }

    // ---------------------------------------------------------------- app dock (recent apps in the glasses)

    /** Recently opened apps, most recent first (persisted). */
    @Volatile var recentApps: List<String> =
        prefs.getString(KEY_RECENT, null)?.split('|')?.filter { it.isNotBlank() } ?: lastScreens
        private set
    @Volatile var dockEnabled = prefs.getBoolean(KEY_DOCK, true)
        private set
    private val iconCache = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()
    @Volatile private var dockArt: android.graphics.Bitmap? = null
    @Volatile private var dockArtKey: Triple<List<String>, Int?, dev.avijit.orbit.render.DockWidget?>? = null

    fun setDockEnabled(on: Boolean) {
        dockEnabled = on
        prefs.edit().putBoolean(KEY_DOCK, on).apply()
        notifyChanged()
    }

    /** Recent apps that aren't already on a screen. */
    private fun dockItems(): List<String> {
        val open = panels.values.mapNotNull { it.component }.toSet()
        return recentApps.filter { it !in open }
    }

    /** Apps shown in the dock: only while the helper can open them. */
    private fun dockApps(): List<String> =
        if (gate.state == dev.avijit.orbit.shell.HelperState.READY) dockItems() else emptyList()

    private fun widgetSlots(): Float = if (widgetsEnabled) dev.avijit.orbit.layout.AppDock.WIDGET_SLOTS else 0f

    /** Under the centre screen: clock/battery widgets and recent apps. Hidden during Mirror and while the keyboard shows. */
    fun dockPose(): ScreenPose? {
        if (!dockEnabled || mirrorOn || keyboardVisible) return null
        val n = dockApps().size
        val w = widgetSlots()
        if (n == 0 && w == 0f) return null
        return dev.avijit.orbit.layout.AppDock.pose(pose(0), n, w)
    }

    // ---------------------------------------------------------------- dock widgets (clock, date, battery, next event)

    @Volatile var widgetsEnabled = prefs.getBoolean(KEY_WIDGETS, true)
        private set
    @Volatile var calendarEnabled = prefs.getBoolean(KEY_CALENDAR, false)
        private set
    @Volatile private var widget: dev.avijit.orbit.render.DockWidget? = null

    fun setWidgetsEnabled(on: Boolean) { widgetsEnabled = on; prefs.edit().putBoolean(KEY_WIDGETS, on).apply(); refreshWidget(); notifyChanged() }
    fun setCalendarEnabled(on: Boolean) { calendarEnabled = on; prefs.edit().putBoolean(KEY_CALENDAR, on).apply(); work { refreshWidget() } }

    /** Phone battery (the glasses charge from it), refreshed with the clock. */
    @Volatile var batteryPct = -1
        private set

    private val widgetTick = object : Runnable {
        override fun run() {
            refreshWidget()
            worker.postDelayed(this, WIDGET_REFRESH_MS)
        }
    }

    /** Worker thread (or any: reads only system state). */
    private fun refreshWidget() {
        val bi = runCatching { context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val level = bi?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bi?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = bi?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        batteryPct = pct
        val now = java.util.Date()
        widget = dev.avijit.orbit.render.DockWidget(
            time = android.text.format.DateFormat.getTimeFormat(context).format(now),
            date = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(now),
            batteryPct = pct.coerceAtLeast(0),
            charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL,
            event = if (calendarEnabled) nextEvent() else null,
        )
    }

    /** "14:00 Standup" for the next timed event in the coming 12 hours (needs calendar permission). */
    private fun nextEvent(): String? {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        val now = System.currentTimeMillis()
        val uri = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(now.toString()).appendPath((now + 12 * 3_600_000L).toString()).build()
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.CalendarContract.Instances.BEGIN, android.provider.CalendarContract.Instances.TITLE, android.provider.CalendarContract.Instances.ALL_DAY),
                "${android.provider.CalendarContract.Instances.ALL_DAY} = 0 AND ${android.provider.CalendarContract.Instances.END} > ?",
                arrayOf(now.toString()),
                "${android.provider.CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                val begin = c.getLong(0)
                val title = c.getString(1).orEmpty().ifBlank { "Event" }
                val at = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(begin))
                (if (begin <= now) "Now: " else "$at ") + title
            }
        }.getOrNull()
    }

    // ---------------------------------------------------------------- window bar (arrange screens with the mouse)

    @Volatile var windowBarEnabled = prefs.getBoolean(KEY_BAR, true)
        private set

    fun setWindowBarEnabled(on: Boolean) { windowBarEnabled = on; prefs.edit().putBoolean(KEY_BAR, on).apply(); notifyChanged() }

    /** Above the focused screen (not in Mirror or phone Move mode). */
    fun windowBarPose(): ScreenPose? {
        if (!windowBarEnabled || mirrorOn || moveMode) return null
        val f = focused ?: return null
        if (f !in activeSlots()) return null
        return dev.avijit.orbit.layout.WindowBar.pose(pose(f))
    }

    private fun barHit(): dev.avijit.orbit.layout.Hit? {
        val b = windowBarPose() ?: return null
        return hitTest(cursor.direction(), activeSlots().associateWith { pose(it) } + overlays())?.takeIf { it.slot == BAR_SLOT }
    }

    /** Returns true if the click landed on the bar (and was handled there). */
    private fun barTap(): Boolean {
        val hit = barHit() ?: return false
        val f = focused ?: return true
        when (val b = dev.avijit.orbit.layout.WindowBar.buttonAt(hit.u)) {
            dev.avijit.orbit.layout.WindowBar.Button.MOVE -> report("Hold the mouse button on the bar and drag to move this screen")
            dev.avijit.orbit.layout.WindowBar.Button.CLOSE -> closeScreen(f)
            else -> setOffset(f, ScreenLayout.clampOffset(f, layout, dev.avijit.orbit.layout.WindowBar.apply(b, offsets[f])))
        }
        return true
    }

    /** Mouse wheel over the bar pushes the screen away / pulls it closer. */
    private fun barScroll(notches: Int): Boolean {
        barHit() ?: return false
        val f = focused ?: return true
        val o = offsets[f]
        setOffset(f, ScreenLayout.clampOffset(f, layout, o.copy(distanceM = o.distanceM + notches * BAR_WHEEL_M)))
        return true
    }

    /** The keyboard, dock and window bar, for hit-testing alongside the screens. */
    private fun overlays(): Map<Int, ScreenPose> = buildMap {
        keyboardPose()?.let { put(KEYBOARD_SLOT, it) }
        dockPose()?.let { put(DOCK_SLOT, it) }
        windowBarPose()?.let { put(BAR_SLOT, it) }
    }

    /** Returns true if the click landed on the dock (and opened that app). */
    private fun dockTap(): Boolean {
        val d = dockPose() ?: return false
        val hit = hitTest(cursor.direction(), activeSlots().associateWith { pose(it) } + (DOCK_SLOT to d)) ?: return false
        if (hit.slot != DOCK_SLOT) return false
        val items = dockApps()
        val i = dev.avijit.orbit.layout.AppDock.indexAt(hit.u, items.size, widgetSlots()) ?: return true
        DiagLog.i(TAG, "dock: open ${items[i].substringBefore('/')}")
        openApp(items[i])
        return true
    }

    private fun dockArtFor(items: List<String>, hover: Int?): android.graphics.Bitmap {
        val w = if (widgetsEnabled) widget else null
        val key = Triple(items, hover, w)
        dockArt?.takeIf { dockArtKey == key }?.let { return it }
        val icons = items.map { comp -> iconFor(comp.substringBefore('/')) }
        return dev.avijit.orbit.render.DockArt.render(w, icons, items.map { label(it) }, hover).also {
            dockArt = it
            dockArtKey = key
        }
    }

    @Volatile private var barArt: android.graphics.Bitmap? = null
    @Volatile private var barArtKey: Triple<String, dev.avijit.orbit.layout.WindowBar.Button?, Boolean>? = null

    private fun barArtFor(title: String, hover: dev.avijit.orbit.layout.WindowBar.Button?, dragging: Boolean): android.graphics.Bitmap {
        val key = Triple(title, hover, dragging)
        barArt?.takeIf { barArtKey == key }?.let { return it }
        return dev.avijit.orbit.render.WindowBarArt.render(title, hover, dragging).also { barArt = it; barArtKey = key }
    }

    private fun iconFor(pkg: String): android.graphics.Bitmap? = iconCache[pkg] ?: runCatching {
        val d = context.packageManager.getApplicationIcon(pkg)
        val b = android.graphics.Bitmap.createBitmap(192, 192, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        d.setBounds(0, 0, 192, 192)
        d.draw(c)
        b
    }.getOrNull()?.also { iconCache[pkg] = it }

    fun setContent(slot: Int, mode: dev.avijit.orbit.render.ContentMode) = work {
        if (slot in panels) content = content + (slot to mode)
        notifyChanged()
    }

    fun snapshot(): SceneSnapshot {
        val s = buildSnapshot()
        val busy = s.screens.isNotEmpty()
        val cm = content
        val kb = keyboardPose()
        val dock = dockPose()
        val dockArtNow = dock?.let { d ->
            val items = dockApps()
            val hit = hitTest(cursor.direction(), activeSlots().associateWith { pose(it) } + (DOCK_SLOT to d))
            val hover = hit?.takeIf { it.slot == DOCK_SLOT }?.let { dev.avijit.orbit.layout.AppDock.indexAt(it.u, items.size, widgetSlots()) }
            dockArtFor(items, hover)
        }
        val bar = windowBarPose()
        val barArtNow = bar?.let {
            val f = focused
            val title = f?.let { panels[it]?.component }?.let { label(it) } ?: ""
            val hover = barHit()?.let { h -> dev.avijit.orbit.layout.WindowBar.buttonAt(h.u) }
            barArtFor(title, hover, barDrag != null)
        }
        return s.copy(
            keyboard = kb, keyboardArt = if (kb != null) kbArt else null,
            dock = dock, dockArt = dockArtNow,
            windowBar = bar, windowBarArt = barArtNow,
            screens = s.screens.map { it.copy(content = cm[it.slot] ?: dev.avijit.orbit.render.ContentMode.FLAT) },
            background = background, orbitApps = orbitApps, orbitStill = orbitStill, backgroundLevel = if (busy) dimWithApps else dimNoApps, showMarkers = showMarkers,
            stereo = stereo, ipdM = ipdM, sharpen = sharpen,
        )
    }

    private fun buildSnapshot(): SceneSnapshot {
        if (mirrorOn) return SceneSnapshot(listOf(ScreenDraw(0, pose(0), false)), null, false, showHeadDot = false)
        val active = activeSlots()
        if (active.isEmpty() && dockPose() == null) return SceneSnapshot()
        val poses = active.associateWith { pose(it) }
        val f = focused
        val draws = poses.map { (slot, p) -> ScreenDraw(slot, p, slot == f) }
        if (!pointerVisible) return SceneSnapshot(draws, null, false, showHeadDot = false)
        val dir = cursor.direction()
        val hit = hitTest(dir, poses + overlays())
        val dist = (hit?.distance ?: CURSOR_FREE_M) - CURSOR_LIFT_M
        val size = CURSOR_SIZE_M * dist / 1.5f
        val cursorPose = ScreenPose(cursor.yawDeg, cursor.pitchDeg, dist, size, size)
        val age = android.os.SystemClock.uptimeMillis() - lastClickMs
        return SceneSnapshot(
            draws, cursorPose, false, showHeadDot = false,
            cursorOverScreen = hit != null, clickAgeMs = if (age in 0..RIPPLE_MS) age else -1,
        )
    }

    private fun activeSlots(): Set<Int> = panels.filterValues { it.active }.keys

    private fun currentHit() = hitTest(cursor.direction(), activeSlots().associateWith { pose(it) })

    // ---------------------------------------------------------------- PanelHost

    override fun onPanelSurface(slot: Int, surface: Surface, setBufferSize: (Int, Int) -> Unit) {
        surfaces[slot] = surface
        sizers[slot] = setBufferSize
        if (slot == 0) mirrorSink?.invoke(surface, setBufferSize)
        work { if (panels[slot]?.component != null) { releaseDisplay(slot); openOnPanel(slot) } }
    }

    override fun onPanelSurfacesGone() {
        DiagLog.i(TAG, "panel surfaces gone")
        surfaces.fill(null)
        sizers.fill(null)
        mirrorSink?.invoke(null) { _, _ -> }
        work { panels.keys.toList().forEach { releaseDisplay(it) } }
    }

    // ---------------------------------------------------------------- worker

    /** Worker thread. Creates the slot's display (if needed) and launches its app. */
    private fun openOnPanel(slot: Int) {
        val state = panels[slot] ?: return
        val comp = state.component ?: return
        val shell = gate.shell() ?: return report("Waiting for the helper to open ${label(comp)}. Tap Start on Home")
        val s = surfaces[slot] ?: return report("Waiting for the glasses to open ${label(comp)}")
        var id = state.displayId
        if (id < 0) {
            id = try {
                val q = quality
                sizers[slot]?.invoke(q.width, q.height)
                shell.createDisplay(s, q.width, q.height, q.dpi).also { if (it >= 0) panels = panels + (slot to state.copy(width = q.width, height = q.height)) }
            } catch (t: Throwable) {
                -1.also { DiagLog.e(TAG, "createDisplay threw", t) }
            }
            if (id < 0) {
                removeSlot(slot)
                return report("Couldn't create a screen for ${label(comp)}: ${safeError(shell)}", error = true)
            }
            panels = panels + (slot to panels.getValue(slot).copy(displayId = id))
            val ime = runCatching { shell.imePolicy(id) }.getOrDefault(0)
            imeLocal = imeLocal + (slot to (ime == 1))
            DiagLog.i(TAG, "slot $slot display $id ime=$ime (${runCatching { shell.info() }.getOrNull()})")
        }
        val out = try { shell.launch(id, comp) } catch (t: Throwable) { "Error: ${t.javaClass.simpleName}: ${t.message}" }
        DiagLog.i(TAG, "am start slot $slot → ${out.replace("\n", " | ")}")
        val code = out.substringAfter("exit ", "").substringBefore('\n').trim().toIntOrNull() ?: 1
        val result = AmStart.parse(code, out.substringAfter('\n', out))
        when (result.outcome) {
            AmStart.Outcome.OPENED -> {
                launchedAt[slot] = android.os.SystemClock.uptimeMillis()
                panels = panels + (slot to panels.getValue(slot).copy(launched = true))
                report("Showing ${label(comp)}")
            }
            AmStart.Outcome.UNCONFIRMED -> {
                launchedAt[slot] = android.os.SystemClock.uptimeMillis()
                panels = panels + (slot to panels.getValue(slot).copy(launched = true))
                report("Opened ${label(comp)}. If its screen stays empty, close it on the phone and try again.")
            }
            AmStart.Outcome.FAILED -> {
                releaseDisplay(slot)
                removeSlot(slot)
                report("${label(comp)} didn't open: ${result.reason}", error = true)
            }
        }
    }

    /** Worker thread. */
    private fun releaseDisplay(slot: Int) {
        val state = panels[slot] ?: return
        val id = state.displayId
        panels = panels + (slot to state.copy(displayId = -1, launched = false))
        if (id >= 0) {
            runCatching { gate.shell()?.releaseDisplay(id) }
            DiagLog.i(TAG, "slot $slot display $id released")
        }
        notifyChanged()
    }

    private inline fun call(what: String, block: (IOrbitShell) -> Boolean) {
        val shell = gate.shell() ?: return report("$what: the helper isn't running", error = true)
        val ok = try { block(shell) } catch (t: Throwable) {
            return report("$what failed: ${t.javaClass.simpleName}: ${t.message}", error = true)
        }
        if (!ok) report("$what failed: ${safeError(shell)}", error = true)
    }

    private fun safeError(shell: IOrbitShell) = runCatching { shell.lastError() }.getOrNull() ?: "unknown error"

    private fun label(component: String) = labeler(component.substringBefore('/'))

    private fun rememberScreens() {
        val list = panels.toSortedMap().values.mapNotNull { it.component }
        if (list.isNotEmpty()) {
            lastScreens = list
            prefs.edit().putString(KEY_LAST, list.joinToString("|")).apply()
        }
    }

    private fun saveLayout() {
        prefs.edit().putString(KEY_LAYOUT, ScreenLayout.encode(layout, offsets)).apply()
    }

    private fun report(msg: String, error: Boolean = false) {
        status = msg
        if (error) DiagLog.e(TAG, msg) else DiagLog.i(TAG, msg)
        notifyChanged()
    }

    private fun notifyChanged() {
        main.post { listener?.invoke() }
    }

    private fun work(block: () -> Unit) {
        worker.post(block)
    }

    companion object {
        private const val TAG = "screens"
        /** Hit-test id for the in-glasses keyboard (screens use 0..9). */
        private const val KEYBOARD_SLOT = -1
        /** Hit-test id for the app dock. */
        private const val DOCK_SLOT = -2
        /** Hit-test id for the focused screen's title bar. */
        private const val BAR_SLOT = -3
        private const val BAR_WHEEL_M = 0.05f
        private const val WIDGET_REFRESH_MS = 20_000L
        private const val APP_WATCH_MS = 2_000L
        /** Don't judge a screen empty right after launching (the activity may still be starting). */
        private const val APP_GRACE_MS = 5_000L
        private const val SCROLL_PX_PER_STEP = 40f
        private const val MOVE_DEG_PER_PX = 0.08f
        private const val PUSH_M_PER_PX = 0.004f
        private const val CURSOR_FREE_M = 2f
        private const val CURSOR_LIFT_M = 0.01f
        private const val CURSOR_SIZE_M = 0.06f
        const val RIPPLE_MS = 350L
        private const val KEY_LAYOUT = "layout"
        private const val KEY_LAST = "lastScreens"
        private const val KEY_RECENT = "recentApps"
        private const val KEY_DOCK = "dock"
        private const val KEY_WIDGETS = "widgets"
        private const val KEY_CALENDAR = "calendarWidget"
        private const val KEY_BAR = "windowBar"
        private const val KEY_ORBIT_APPS = "orbitApps"
        private const val KEY_ORBIT_STILL = "orbitStill"

        @Volatile
        private var instance: ScreensSession? = null

        fun get(context: Context): ScreensSession =
            instance ?: synchronized(this) {
                instance ?: ScreensSession(context.applicationContext).also { instance = it }
            }
    }
}

/** App-screen resolution presets; all 16:10 and the same UI size (dpi scales with pixels). */
enum class ScreenQuality(val label: String, val width: Int, val height: Int, val dpi: Int) {
    STANDARD("Standard · 1280×800", 1280, 800, 213),
    SHARP("Sharp · 1920×1200", 1920, 1200, 320),
    ULTRA("Ultra · 2560×1600 (best for 1–3 screens)", 2560, 1600, 426),
}
