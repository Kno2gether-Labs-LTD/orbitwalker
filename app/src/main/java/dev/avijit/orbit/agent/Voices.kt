package dev.avijit.orbit.agent

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.mutableIntStateOf
import dev.avijit.orbit.diag.DiagLog
import org.json.JSONObject
import java.io.File

/**
 * Orbit's voice. Voices from your Orbit Server (OpenAI, ElevenLabs, Inworld… set up on its dashboard) or the phone's
 * own text-to-speech. Whenever the server's voice can't be had, the phone's voice says it instead.
 * Main thread for control.
 */
object Voices {
    data class Choice(val id: String, val name: String, val provider: String)

    /** The phone's own text-to-speech. */
    const val PHONE = "phone"

    @Volatile var choices: List<Choice> = emptyList()
        private set
    /** The server's default voice (null = none set up there). */
    @Volatile var serverDefault: String? = null
        private set
    @Volatile var listNote: String = ""
        private set
    /** True while Orbit is talking (the buddy moves its mouth). */
    @Volatile var speaking = false
        private set
    val version = mutableIntStateOf(0)

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val net by lazy { Handler(HandlerThread("orbit-voice").apply { start() }.looper) }
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var player: MediaPlayer? = null
    /** Bumped on every new utterance and stop(): late callbacks from an older one are ignored. */
    private var generation = 0

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
    }

    private fun prefs() = app.getSharedPreferences("orbit_agent", Context.MODE_PRIVATE)

    /** The voice you picked: a server voice id ("openai:coral"), [PHONE], or "" = the server's default. */
    var selected: String
        get() = prefs().getString(KEY, "") ?: ""
        set(v) { prefs().edit().putString(KEY, v).apply(); changed() }

    /** What [selected] means right now, for the phone's voice list. */
    fun selectedLabel(): String = when (val s = selected) {
        PHONE -> "Phone voice"
        "" -> serverDefault?.let { d -> choices.firstOrNull { it.id == d }?.let { "${it.name} (${it.provider}) · server default" } } ?: "Phone voice (no server voice set)"
        else -> choices.firstOrNull { it.id == s }?.let { "${it.name} (${it.provider})" } ?: s
    }

    /** Asks the server which voices it has. */
    fun refresh() {
        if (!OrbitAgent.paired) { choices = emptyList(); serverDefault = null; listNote = ""; changed(); return }
        listNote = "Loading voices…"; changed()
        net.post {
            val r = runCatching { OrbitAgent.link.get("/v1/walker/voices") }
            main.post {
                r.onSuccess { o ->
                    val a = o.optJSONArray("voices")
                    choices = (0 until (a?.length() ?: 0)).mapNotNull { i ->
                        val v = a!!.optJSONObject(i) ?: return@mapNotNull null
                        val id = v.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        Choice(id, LabelText.clean(v.optString("name").ifBlank { id }), LabelText.clean(v.optString("providerName").ifBlank { v.optString("provider") }))
                    }
                    serverDefault = o.optString("default").takeIf { it.isNotBlank() && it != "null" }
                    listNote = if (choices.isEmpty()) "No voices on your Orbit Server yet: add a voice provider on its dashboard (Voices)." else ""
                }.onFailure { e ->
                    choices = emptyList(); serverDefault = null
                    listNote = if (e is ServerException && (e.status == 404 || e.code == "not_found")) "Your Orbit Server doesn't offer voices yet: update it." else "Couldn't load voices from your Orbit Server"
                    DiagLog.i(TAG, "voices: ${(e as? ServerException)?.code ?: e.javaClass.simpleName}")
                }
                changed()
            }
        }
    }

    /** Says [text], then calls [done] once (main thread), however it went. */
    fun speak(text: String, done: () -> Unit = {}) {
        stop()
        val gen = ++generation
        var fired = false
        val finish = { if (gen == generation && !fired) { fired = true; speaking = false; changed(); done() } }
        val want = selected
        val serverVoice = when {
            want == PHONE -> null
            want.isNotBlank() -> want
            serverDefault != null -> serverDefault
            else -> null
        }
        if (serverVoice == null || !OrbitAgent.paired) { phoneSay(text, gen, finish); return }
        net.post {
            val audio = runCatching {
                OrbitAgent.link.postForBytes("/v1/walker/speak", JSONObject().put("text", text.take(MAX_TEXT)).put("voice", serverVoice))
            }.onFailure { DiagLog.i(TAG, "server voice failed: ${(it as? ServerException)?.code ?: it.javaClass.simpleName}") }.getOrNull()
            main.post {
                if (gen != generation) return@post
                if (audio == null || audio.size < 64) phoneSay(text, gen, finish) else play(audio, text, gen, finish)
            }
        }
    }

    /** Stops talking now (a new request, or you started speaking). */
    fun stop() {
        generation++
        runCatching { player?.stop() }; runCatching { player?.release() }; player = null
        runCatching { tts?.stop() }
        if (speaking) { speaking = false; changed() }
    }

    private fun play(audio: ByteArray, text: String, gen: Int, finish: () -> Unit) {
        try {
            val f = File(app.cacheDir, "orbit-voice.mp3").also { it.writeBytes(audio) }
            val p = MediaPlayer()
            player = p
            p.setAudioAttributes(ATTRS)
            p.setDataSource(f.absolutePath)
            p.setOnCompletionListener { if (player === p) { p.release(); player = null }; finish() }
            p.setOnErrorListener { _, what, _ ->
                DiagLog.i(TAG, "voice playback error $what")
                if (player === p) { p.release(); player = null }
                if (gen == generation) phoneSay(text, gen, finish)
                true
            }
            p.setOnPreparedListener { if (gen == generation) { speaking = true; changed(); it.start() } }
            p.prepareAsync()
        } catch (e: Exception) {
            DiagLog.e(TAG, "voice playback failed", e)
            phoneSay(text, gen, finish)
        }
    }

    private fun phoneSay(text: String, gen: Int, finish: () -> Unit) {
        val go = {
            val t = tts
            if (t == null || !ttsReady) finish()
            else {
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { main.post { if (gen == generation) { speaking = true; changed() } } }
                    override fun onDone(id: String?) { main.post(finish) }
                    @Deprecated("old api") override fun onError(id: String?) { main.post(finish) }
                    override fun onError(id: String?, code: Int) { main.post(finish) }
                    override fun onStop(id: String?, interrupted: Boolean) { main.post(finish) }
                })
                if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "orbit-$gen") != TextToSpeech.SUCCESS) finish()
            }
        }
        if (tts != null) { go(); return }
        tts = TextToSpeech(app) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            main.post { if (gen == generation) go() }
        }.also { it.setAudioAttributes(ATTRS) }
    }

    private fun changed() { if (Looper.myLooper() == Looper.getMainLooper()) version.intValue++ else main.post { version.intValue++ } }

    private val ATTRS = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private const val TAG = "agent"
    private const val KEY = "voice"
    private const val MAX_TEXT = 1_500
}
