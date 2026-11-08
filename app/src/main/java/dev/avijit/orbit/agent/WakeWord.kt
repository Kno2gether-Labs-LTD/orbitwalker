package dev.avijit.orbit.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.compose.runtime.mutableIntStateOf
import dev.avijit.orbit.diag.DiagLog
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * "Hey Orbit": an offline keyword listener (Vosk, small English model, on the phone; nothing leaves it).
 * The model (~40 MB) is downloaded once, checked against a pinned SHA-256, and kept in app storage.
 * Main thread for control.
 */
object WakeWord {
    enum class State { OFF, DOWNLOADING, LISTENING, PAUSED, ERROR }

    @Volatile var state = State.OFF
        private set
    @Volatile var detail: String = ""
        private set
    val version = mutableIntStateOf(0)

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private var model: org.vosk.Model? = null
    private var service: org.vosk.android.SpeechService? = null
    private var pauses = 0
    @Volatile private var wanted = false

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        wanted = prefs().getBoolean(KEY, false)
    }

    val enabled get() = wanted

    private fun prefs() = app.getSharedPreferences("orbit_agent", Context.MODE_PRIVATE)

    /** Turns "Hey Orbit" on or off (saved). On needs the microphone permission and a foreground start (Android 14). */
    fun setEnabled(on: Boolean) {
        wanted = on
        prefs().edit().putBoolean(KEY, on).apply()
        if (on) AgentService.start(app) else { stopListening(); AgentService.stop(app); set(State.OFF, "") }
        changed()
    }

    /** AgentService started: get the model ready, then listen. */
    internal fun onServiceStarted() {
        if (!wanted) return
        val dir = File(app.filesDir, MODEL_DIR)
        if (model == null && !File(dir, "am/final.mdl").exists()) { download(dir); return }
        loadAndListen(dir)
    }

    internal fun onServiceStopped() = stopListening()

    /** While the agent listens or speaks, the mic is free for it (nested calls count). */
    fun pause() {
        pauses++
        if (pauses == 1 && service != null) { stopService(); set(State.PAUSED, "") }
    }

    fun resume() {
        if (pauses == 0) return
        pauses--
        if (pauses == 0 && wanted && model != null) startService()
    }

    private fun download(dir: File) {
        set(State.DOWNLOADING, "Downloading the \"Hey Orbit\" listener (40 MB, once)…")
        Thread({
            val zip = File(app.cacheDir, "vosk-model.zip")
            try {
                val c = URI(MODEL_URL).toURL().openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 15_000; c.readTimeout = 60_000
                val md = MessageDigest.getInstance("SHA-256")
                c.inputStream.use { input -> zip.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n); out.write(buf, 0, n) }
                } }
                val sha = md.digest().joinToString("") { "%02x".format(it) }
                if (sha != MODEL_SHA256) throw IllegalStateException("model checksum mismatch")
                val tmp = File(app.filesDir, "$MODEL_DIR.tmp").also { it.deleteRecursively(); it.mkdirs() }
                ZipInputStream(zip.inputStream()).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        val rel = e.name.substringAfter('/', "")
                        if (rel.isEmpty() || rel.contains("..")) continue
                        val f = File(tmp, rel)
                        if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                    }
                }
                dir.deleteRecursively(); tmp.renameTo(dir)
                main.post { if (wanted) loadAndListen(dir) }
            } catch (e: Exception) {
                DiagLog.e(TAG, "wake word model download failed", e)
                main.post { set(State.ERROR, "Couldn't download the listener: check the internet and try again") }
            } finally {
                zip.delete()
            }
        }, "wake-model").start()
    }

    private fun loadAndListen(dir: File) {
        if (model != null) { startService(); return }
        Thread({
            val m = runCatching { org.vosk.Model(dir.absolutePath) }.onFailure { DiagLog.e(TAG, "wake word model load failed", it) }.getOrNull()
            main.post {
                if (m == null) { set(State.ERROR, "The listener didn't load"); return@post }
                model = m
                if (wanted && pauses == 0) startService()
            }
        }, "wake-load").start()
    }

    private fun startService() {
        val m = model ?: return
        if (service != null) return
        try {
            val rec = org.vosk.Recognizer(m, RATE, GRAMMAR)
            service = org.vosk.android.SpeechService(rec, RATE).also { it.startListening(listener) }
            set(State.LISTENING, "Say \"Hey Orbit\"")
        } catch (e: Exception) {
            DiagLog.e(TAG, "wake word start failed", e)
            set(State.ERROR, "The microphone is busy")
        }
    }

    private fun stopService() { runCatching { service?.stop(); service?.shutdown() }; service = null }

    private fun stopListening() { stopService(); pauses = 0 }

    private val listener = object : org.vosk.android.RecognitionListener {
        override fun onPartialResult(hypothesis: String?) = check(hypothesis, "partial")
        override fun onResult(hypothesis: String?) = check(hypothesis, "text")
        override fun onFinalResult(hypothesis: String?) = Unit
        override fun onError(exception: Exception?) { DiagLog.e(TAG, "wake word error", exception); main.post { stopService(); set(State.ERROR, "Listener stopped") } }
        override fun onTimeout() = Unit
    }

    private var lastFire = 0L

    private fun check(json: String?, key: String) {
        val text = runCatching { JSONObject(json ?: "{}").optString(key) }.getOrDefault("")
        if (!WakePhrase.matches(text)) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastFire < 2_000) return
        lastFire = now
        DiagLog.i(TAG, "wake word heard")
        main.post { OrbitAgent.listen(fromWake = true) }
    }

    private fun set(s: State, d: String) { state = s; detail = d; changed() }
    private fun changed() { if (Looper.myLooper() == Looper.getMainLooper()) version.intValue++ else main.post { version.intValue++ } }

    private const val TAG = "agent"
    private const val KEY = "wake_word"
    private const val RATE = 16_000f
    private const val MODEL_DIR = "vosk-small-en"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private const val MODEL_SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
    /** Only these words are recognised, so anything else is "[unk]": cheap and few false alarms. */
    private const val GRAMMAR = "[\"hey orbit\", \"okay orbit\", \"hi orbit\", \"[unk]\"]"
}

/** Which recognised words count as the wake phrase. Pure. */
object WakePhrase {
    private val PHRASES = listOf("hey orbit", "okay orbit", "hi orbit")
    fun matches(text: String): Boolean = text.lowercase().trim().let { t -> PHRASES.any { t.contains(it) } }
}

/** Keeps the microphone available for "Hey Orbit" while you're in the glasses (a visible notification says so). */
class AgentService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Orbit agent", NotificationManager.IMPORTANCE_LOW))
        val open = android.app.PendingIntent.getActivity(this, 0, packageManager.getLaunchIntentForPackage(packageName),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Listening for \"Hey Orbit\"")
            .setContentText("Only on this phone. Turn off in OrbitWalker → Orbit agent.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        val started = runCatching { startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) }
        if (started.isFailure) {
            DiagLog.e("agent", "wake word service couldn't start", started.exceptionOrNull())
            stopSelf(); return START_NOT_STICKY
        }
        WakeWord.init(this)
        WakeWord.onServiceStarted()
        return START_STICKY
    }

    override fun onDestroy() { WakeWord.onServiceStopped(); super.onDestroy() }

    companion object {
        private const val CHANNEL = "orbit_agent"
        private const val ID = 4207

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, AgentService::class.java)) }
                .onFailure { DiagLog.e("agent", "can't start the wake word service", it) }
        }

        fun stop(context: Context) { context.stopService(Intent(context, AgentService::class.java)) }
    }
}
