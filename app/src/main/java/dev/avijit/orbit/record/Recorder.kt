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

    var glassesView: Boolean
        get() = prefs.getBoolean("rec_glasses", true)
        set(v) { prefs.edit().putBoolean("rec_glasses", v).apply(); changed() }
    var mixedReality: Boolean
        get() = prefs.getBoolean("rec_mixed", true)
        set(v) { prefs.edit().putBoolean("rec_mixed", v).apply(); changed() }
    var frontCamera: Boolean
        get() = prefs.getBoolean("rec_front", false)
        set(v) { prefs.edit().putBoolean("rec_front", v).apply(); reopenCameraIfOpen() }
    var wideLens: Boolean
        get() = prefs.getBoolean("rec_wide", true)
        set(v) { prefs.edit().putBoolean("rec_wide", v).apply(); reopenCameraIfOpen() }
    var microphone: Boolean
        get() = prefs.getBoolean("rec_mic", true)
        set(v) { prefs.edit().putBoolean("rec_mic", v).apply(); changed() }
    /** Your camera picture in a corner of the glasses-view file. */
    var faceBubble: Boolean
        get() = prefs.getBoolean("rec_bubble", false)
        set(v) { prefs.edit().putBoolean("rec_bubble", v).apply(); changed() }
    /** You hide the screens behind you (person detection). */
    var personInFront: Boolean
        get() = prefs.getBoolean("rec_person", true)
        set(v) { prefs.edit().putBoolean("rec_person", v).apply(); reopenCameraIfOpen() }
    var spot: CameraSpot
        get() = runCatching { CameraSpot.valueOf(prefs.getString("rec_spot", null)!!) }.getOrDefault(CameraSpot.RIGHT_SHOULDER)
        set(v) { prefs.edit().putString("rec_spot", v.name).apply(); rig = v.preset() }
    @Volatile var rig: RigPose = CameraSpot.RIGHT_SHOULDER.preset()
        set(v) { field = v; if (::prefs.isInitialized) prefs.edit().putString("rec_rig", v.encode()).apply(); changed() }

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
        if (!glassesView && !mixedReality) return fail("Turn on at least one view to record")
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
        } catch (t: Throwable) {
            DiagLog.e(TAG, "couldn't start recording", t)
            made.forEach { runCatching { it.finish() } }
            if (!monitorOn) closeCamera()
            return fail("Couldn't start recording: ${t.message ?: t.javaClass.simpleName}")
        }
        files = made
        recording = true
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
            if (next == null) setMonitor(false)
            else {
                if (k.ordinal >= CalKey.SPOT_1.ordinal) prefs.edit().putString("rec_spot", CameraSpot.entries[k.ordinal - CalKey.SPOT_1.ordinal].name).apply()
                rig = next
            }
        }
    }

    private fun ensureCamera(want: Boolean): Boolean {
        if (!want || camera != null) return true
        val rotation = (app.getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0) * 90
        val spec = CameraFeed.choose(app, frontCamera, wideLens, rotation)
            ?: return fail("This phone has no ${if (frontCamera) "front" else "back"} camera to use")
        val m = if (personInFront) runCatching { PersonMask() }.getOrNull() else null
        mask = m
        camera = CameraFeed(app, spec, m?.let { pm -> { img, rot -> pm.process(img, rot) } })
        RecordingService.update(app)
        changed()
        return true
    }

    /** Renderer (GL thread): the texture for [feed] is ready; open the camera into it. */
    fun onCameraSurface(feed: CameraFeed, surface: Surface) {
        if (camera === feed) feed.open(surface)
    }

    private fun closeCamera() {
        camera?.close(); camera = null
        mask?.close(); mask = null
        changed()
    }

    private fun reopenCameraIfOpen() {
        changed()
        if (camera == null || recording) return
        closeCamera()
        ensureCamera(true)
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
            "${CameraRig.placement(rig)} · ${camera?.spec?.label ?: "camera"}",
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
