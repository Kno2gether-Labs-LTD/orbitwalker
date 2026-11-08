package dev.avijit.orbit.record

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Surface
import androidx.compose.runtime.mutableIntStateOf
import dev.avijit.orbit.diag.DiagLog
import java.util.Date

/**
 * Recording: the glasses view and/or a mixed reality view (you, filmed by the phone, with your
 * screens drawn where they are around you), each to its own MP4 in Movies/OrbitWalker.
 * Main thread for control; the renderer polls the volatile fields once per frame.
 */
object Recorder {
    // ---------------------------------------------------------------- options (saved)

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())

    /** Options as Compose state (screens update the moment they change), saved to prefs. */
    private val opts = androidx.compose.runtime.mutableStateMapOf<String, Any>()
    private fun flag(key: String) = opts[key] as Boolean
    private fun put(key: String, v: Any) {
        opts[key] = v
        prefs.edit().apply { if (v is Boolean) putBoolean(key, v) else putString(key, v.toString()) }.apply()
    }
    private fun loadOptions() {
        mapOf(
            "rec_glasses" to true, "rec_mixed" to true, "rec_front" to false, "rec_wide2" to false,
            "rec_mic" to true, "rec_bubble" to false, "rec_person" to true, "rec_clean" to false,
        ).forEach { (k, d) -> opts[k] = prefs.getBoolean(k, d) }
        opts["rec_stand"] = runCatching { PhoneStand.valueOf(prefs.getString("rec_stand", null)!!) }.getOrDefault(PhoneStand.AUTO)
        opts["rec_bg"] = runCatching { MixedBackground.valueOf(prefs.getString("rec_bg", null)!!) }.getOrDefault(MixedBackground.REAL)
        opts["rec_spot"] = runCatching { CameraSpot.valueOf(prefs.getString("rec_spot", null)!!) }.getOrDefault(CameraSpot.RIGHT_SHOULDER)
        opts["rec_stage"] = runCatching { ScreenStaging.valueOf(prefs.getString("rec_stage", null)!!) }.getOrDefault(ScreenStaging.FRAMED)
        opts["rec_side"] = runCatching { HeroSide.valueOf(prefs.getString("rec_side", null)!!) }.getOrDefault(HeroSide.AUTO)
        opts["rec_fill"] = prefs.getString("rec_fill", null)?.toFloatOrNull() ?: Staging.DEFAULT_FILL
    }

    var glassesView: Boolean
        get() = flag("rec_glasses")
        set(v) { put("rec_glasses", v); changed() }
    var mixedReality: Boolean
        get() = flag("rec_mixed")
        set(v) { put("rec_mixed", v); changed() }
    var frontCamera: Boolean
        get() = flag("rec_front")
        set(v) { put("rec_front", v); reopenCameraSoon() }
    var wideLens: Boolean
        get() = flag("rec_wide2") // new key: the normal lens is now the default (the ultra-wide stretches you at the edges)
        set(v) { put("rec_wide2", v); reopenCameraSoon() }
    /** How the phone stands: Auto reads gravity; or the user says upright (9:16) / on its side (16:9). */
    var phoneStand: PhoneStand
        get() = opts["rec_stand"] as PhoneStand
        set(v) { put("rec_stand", v); reopenCameraSoon() }
    /** Mixed reality behind you: the room the phone films, or the virtual space you see in the glasses. */
    var background: MixedBackground
        get() = opts["rec_bg"] as MixedBackground
        set(v) { put("rec_bg", v); reopenCameraSoon() } // virtual space needs person detection
    /** A third file: just the screen you're using, flat and full size (for editing). */
    var cleanScreen: Boolean
        get() = flag("rec_clean")
        set(v) { put("rec_clean", v); changed() }
    var microphone: Boolean
        get() = flag("rec_mic")
        set(v) { put("rec_mic", v); changed() }
    /** Your camera picture in a corner of the glasses-view file. */
    var faceBubble: Boolean
        get() = flag("rec_bubble")
        set(v) { put("rec_bubble", v); changed() }
    /** You hide the screens behind you (person detection). */
    var personInFront: Boolean
        get() = flag("rec_person")
        set(v) { put("rec_person", v); reopenCameraSoon() }
    /** How screens appear in the mixed reality video (framed, bigger, or exactly where they are). */
    var staging: ScreenStaging
        get() = opts["rec_stage"] as ScreenStaging
        set(v) { put("rec_stage", v); changed() }
    /** Framed: where the screen sits across the picture. */
    var heroSide: HeroSide
        get() = opts["rec_side"] as HeroSide
        set(v) { put("rec_side", v); changed() }
    /** Framed: how much of the picture's width the screen fills. */
    var heroFill: Float
        get() = opts["rec_fill"] as Float
        set(v) { put("rec_fill", v.coerceIn(Staging.MIN_FILL, Staging.MAX_FILL)); changed() }
    var spot: CameraSpot
        get() = opts["rec_spot"] as CameraSpot
        set(v) { put("rec_spot", v); rig = v.preset() }
    private val rigState = androidx.compose.runtime.mutableStateOf(CameraSpot.RIGHT_SHOULDER.preset())
    /** Where the virtual camera stands (Compose state: the studio's placement line follows it). */
    var rig: RigPose
        get() = rigState.value
        set(v) { rigState.value = v; if (::prefs.isInitialized) prefs.edit().putString("rec_rig", v.encode()).apply(); changed() }

    // ---------------------------------------------------------------- live state (renderer reads)

    /** Files being written: the renderer draws each view into its file's input surface. */
    @Volatile var files: List<Mp4File> = emptyList()
        private set
    @Volatile var recording = false
        private set
    @Volatile var startedAt = 0L
        private set
    /** Camera monitor in the glasses (and calibration keys) is open. */
    @Volatile var monitorOn = false
        private set
    /** The renderer should provide a camera texture for [camera]'s spec. */
    @Volatile var camera: CameraFeed? = null
        private set
    @Volatile var mask: PersonMask? = null
        private set
    /** Set by the renderer every frame: the glasses scene is drawing. */
    @Volatile var lastFrameMs = 0L
    @Volatile var lastError: String? = null
        private set
    @Volatile var lastSaved: List<String> = emptyList()
        private set

    /** Compose: bumped on every change so the Record screen redraws. */
    val version = mutableIntStateOf(0)
    var onReport: ((String, Boolean) -> Unit)? = null

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        prefs = app.getSharedPreferences("orbit", Context.MODE_PRIVATE)
        loadOptions()
        tilt = PhoneTilt(app) { main.post(::onTiltChanged) }
        rig = RigPose.decode(prefs.getString("rec_rig", null)) ?: spot.preset()
        app.getSystemService(PowerManager::class.java).addThermalStatusListener(app.mainExecutor) { status ->
            if (!recording) return@addThermalStatusListener
            if (status >= PowerManager.THERMAL_STATUS_CRITICAL) { report("The phone is too hot: recording stopped and saved", true); stop() }
            else if (status >= PowerManager.THERMAL_STATUS_SEVERE) report("The phone is getting hot. Record one view, or close some screens.", true)
        }
    }

    fun hasCameraPermission() = app.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    fun hasMicPermission() = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun glassesDrawing() = SystemClock.uptimeMillis() - lastFrameMs < 1_500
    private fun needsCamera(rec: Boolean) = monitorOn || (rec && (mixedReality || (glassesView && faceBubble)))

    // ---------------------------------------------------------------- recording

    fun toggle() = if (recording) stop() else start()

    /** Starts recording; reports (and returns false) when it can't. */
    fun start(): Boolean {
        if (recording) return true
        if (!glassesDrawing()) return fail("Connect the glasses to record")
        if (!glassesView && !mixedReality && !cleanScreen) return fail("Turn on at least one view to record")
        val wantCam = mixedReality || (glassesView && faceBubble)
        if (wantCam && !hasCameraPermission()) return fail("Allow the camera on the phone (Home → Record) to record mixed reality")
        if (!ensureCamera(wantCam)) return false
        val withMic = microphone && hasMicPermission()
        val stamp = Date()
        val made = mutableListOf<Mp4File>()
        try {
            if (glassesView) made += Mp4File.create(app, RecordingNames.file(stamp, RecordingView.GLASSES), RecordingView.GLASSES, 1920, 1080, withMic)
            if (mixedReality) {
                val spec = camera!!.spec
                val portrait = spec.outH > spec.outW
                val (w, h) = Mp4File.supportedSize(if (portrait) listOf(1080 to 1920, 720 to 1280) else listOf(1920 to 1080, 1280 to 720))
                made += Mp4File.create(app, RecordingNames.file(stamp, RecordingView.MIXED), RecordingView.MIXED, w, h, withMic)
            }
            if (cleanScreen) {
                val (w, h) = Mp4File.supportedSize(listOf(1920 to 1200, 1280 to 800))
                made += Mp4File.create(app, RecordingNames.file(stamp, RecordingView.CLEAN), RecordingView.CLEAN, w, h, withMic)
            }
        } catch (t: Throwable) {
            DiagLog.e(TAG, "couldn't start recording", t)
            made.forEach { runCatching { it.finish() } }
            if (!monitorOn) closeCamera()
            return fail("Couldn't start recording: ${t.message ?: t.javaClass.simpleName}")
        }
        files = made
        recording = true
        // Hold exposure once the camera has settled (it may have only just opened).
        val settle = (cameraOpenedAt + 2_000 - SystemClock.uptimeMillis()).coerceAtLeast(0)
        main.postDelayed({ if (recording) camera?.lockExposure(true) }, settle)
        startedAt = SystemClock.uptimeMillis()
        if (withMic) mic = Mic(made).also { it.start() }
        RecordingService.update(app)
        lastError = null
        report("Recording ${made.joinToString(" + ") { it.view.suffix }}${if (withMic) " with sound" else ""}")
        DiagLog.i(TAG, "recording: ${made.joinToString { "${it.view.suffix} ${it.width}x${it.height}" }} mic=$withMic")
        tick()
        return true
    }

    private var mic: Mic? = null

    /** Stops and saves. Finishing the files happens off the main thread. */
    fun stop() {
        if (!recording) return
        val done = files
        recording = false
        files = emptyList() // the renderer stops drawing into them on its next frame
        val m = mic; mic = null
        camera?.lockExposure(false)
        if (!monitorOn) closeCamera()
        RecordingService.update(app)
        changed()
        Thread({
            Thread.sleep(150) // let the renderer let go of the input surfaces
            m?.stop()
            val saved = done.filter { runCatching { it.finish() }.getOrDefault(false) }
            main.post {
                lastSaved = saved.map { it.view.suffix }
                report(if (saved.isEmpty()) "Nothing was recorded" else "Saved to Movies/OrbitWalker: ${saved.joinToString(" and ") { it.view.suffix }}", saved.isEmpty())
                changed()
            }
        }, "rec-finish").start()
    }

    // ---------------------------------------------------------------- camera + monitor

    fun toggleMonitor() = setMonitor(!monitorOn)

    fun setMonitor(on: Boolean) {
        if (on == monitorOn) return
        if (on) {
            if (!glassesDrawing()) { fail("Connect the glasses to line up the camera"); return }
            if (!hasCameraPermission()) { fail("Allow the camera on the phone (Home → Record) first"); return }
            monitorOn = true
            if (!ensureCamera(true)) { monitorOn = false; return }
            report("Camera monitor: arrows move the phone around you, Shift+arrows aim, [ ] zoom, Enter when done")
        } else {
            monitorOn = false
            if (!needsCamera(recording)) closeCamera()
            report("Camera lined up: ${CameraRig.placement(rig)}")
        }
        RecordingService.update(app)
        changed()
    }

    /** A key while the monitor is open. */
    fun calibrate(keyCode: Int, shift: Boolean) {
        val k = CalKey.of(keyCode) ?: return
        val next = Calibration.apply(rig, k, shift, spot)
        main.post {
            if (next == null) { if (!studioOpen) setMonitor(false) }
            else {
                if (k.ordinal >= CalKey.SPOT_1.ordinal) put("rec_spot", CameraSpot.entries[k.ordinal - CalKey.SPOT_1.ordinal])
                rig = next
            }
        }
    }

    private lateinit var tilt: PhoneTilt
    private var cameraOpenedAt = 0L

    /** The Record screen is showing: read gravity so the phone's stance is known before it's needed. */
    fun watchTilt(on: Boolean) = if (on) tilt.hold("screen") else tilt.release("screen")

    /** Which way the camera picture has to turn, for how the phone stands right now. */
    private fun displayDeg() = CameraOrient.displayDegFor(CameraOrient.deviceDeg(phoneStand, tilt.now()))

    /** The phone was turned: re-aim the camera monitor (never mid-recording: the file's shape is fixed). */
    private fun onTiltChanged() {
        val cam = camera ?: return
        if (recording || phoneStand != PhoneStand.AUTO) return
        if (CameraOrient.rotation(cam.spec.sensorDeg, displayDeg(), cam.spec.front) != cam.spec.rotation) reopenCameraIfOpen()
    }

    private fun ensureCamera(want: Boolean): Boolean {
        if (!want || camera != null) return true
        tilt.hold("camera")
        val turn = displayDeg()
        DiagLog.i(TAG, "camera stance: $phoneStand, gravity tilt ${tilt.steady} → display turn $turn")
        val spec = CameraFeed.choose(app, frontCamera, wideLens, turn)
        if (spec == null) {
            tilt.release("camera")
            return fail("This phone has no ${if (frontCamera) "front" else "back"} camera to use")
        }
        val m = if (personInFront || background == MixedBackground.VIRTUAL) runCatching { PersonMask() }.getOrNull() else null
        mask = m
        camera = CameraFeed(app, spec, m?.let { pm -> { img, rot -> pm.process(img, rot) } })
        cameraOpenedAt = SystemClock.uptimeMillis()
        RecordingService.update(app)
        changed()
        return true
    }

    /** Renderer (GL thread): the texture for [feed] is ready; open the camera into it. */
    fun onCameraSurface(feed: CameraFeed, surface: Surface) {
        if (camera === feed) feed.open(surface)
    }

    private fun closeCamera() {
        if (camera != null) tilt.release("camera")
        camera?.close(); camera = null
        mask?.close(); mask = null
        changed()
    }

    /** After the switch has redrawn: reopening the camera takes a moment. */
    private fun reopenCameraSoon() { changed(); main.post(::reopenCameraIfOpen) }

    // ---------------------------------------------------------------- camera studio (a screen in the glasses)

    /** The camera studio screen is showing: it carries the camera picture, so the floating monitor hides. */
    @Volatile var studioOpen = false
        private set

    /** Studio screen started/stopped (main thread). */
    fun studioShowing(on: Boolean) {
        if (on == studioOpen) return
        studioOpen = on
        if (on) setMonitor(true) else if (monitorOn) setMonitor(false)
        changed()
    }

    /** A studio button: [times] steps of a camera-moving key (main thread). */
    fun nudge(key: CalKey, shift: Boolean = false, times: Int = 1) {
        var p = rig
        repeat(times) { p = Calibration.apply(p, key, shift, spot) ?: p }
        rig = p
    }

    private fun reopenCameraIfOpen() {
        changed()
        if (camera == null || recording) return
        tilt.hold("reopen") // keep gravity readings across the reopen
        closeCamera()
        ensureCamera(true)
        tilt.release("reopen")
    }

    // ---------------------------------------------------------------- glasses art

    /** "● REC 1:23" for the glasses (never in the recording). Redrawn once a second. */
    @Volatile var statusArt: Bitmap? = null
        private set
    /** Caption under the camera monitor. */
    @Volatile var monitorArt: Bitmap? = null
        private set

    private fun tick() {
        if (!recording) { statusArt = null; return }
        statusArt = pill("REC  ${RecordingNames.elapsed(SystemClock.uptimeMillis() - startedAt)}", Color.rgb(255, 77, 94))
        changed()
        main.postDelayed(::tick, 1_000)
    }

    private fun drawMonitorArt() {
        monitorArt = if (!monitorOn) null else caption(
            "${CameraRig.placement(rig)} · ${camera?.spec?.let { "${it.label}, ${if (it.outH > it.outW) "9:16" else "16:9"}" } ?: "camera"}",
            "←→ around you   ↑↓ height   Shift+arrows aim   PgUp/PgDn distance   [ ] zoom   1–7 spot   R reset   Enter done",
        )
    }

    private fun pill(text: String, dot: Int): Bitmap {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 34f; typeface = Typeface.DEFAULT_BOLD; color = Color.WHITE }
        val w = (p.measureText(text) + 88).toInt(); val h = 64
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b)
            c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 32f, 32f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 12, 10, 30) })
            c.drawCircle(36f, 32f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dot })
            c.drawText(text, 60f, 44f, p)
        }
    }

    private fun caption(title: String, keys: String): Bitmap {
        val w = 1200; val h = 110
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b)
            c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 22f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 12, 10, 30) })
            c.drawText(title, 24f, 46f, Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 32f; typeface = Typeface.DEFAULT_BOLD; color = Color.WHITE })
            c.drawText(keys, 24f, 90f, Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 23f; color = Color.rgb(125, 245, 214) })
        }
    }

    // ---------------------------------------------------------------- plumbing

    private fun fail(msg: String): Boolean { lastError = msg; report(msg, true); changed(); return false }
    private fun report(msg: String, error: Boolean = false) = main.post { onReport?.invoke(msg, error) }

    private fun changed() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(::changed); return }
        if (::prefs.isInitialized) drawMonitorArt()
        version.intValue++
    }

    private const val TAG = "record"
}
