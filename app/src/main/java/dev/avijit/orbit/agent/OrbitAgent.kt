package dev.avijit.orbit.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.runtime.mutableIntStateOf
import dev.avijit.orbit.diag.DiagLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * The Orbit agent in the glasses: hears you ("Hey Orbit", or the dock's mic), asks your own Orbit Server, runs the
 * glasses actions the server sends back, then answers out loud with a caption in the glasses.
 *
 * After "Hey Orbit" it stays in the conversation: once it has answered it listens again, so you can just keep talking.
 * After [SESSION_IDLE_MS] of silence (or "that's all") it says it's stepping back and waits for "Hey Orbit" again.
 * Main thread for control; network on its own thread; the renderer reads [captionArt] and the buddy's state.
 */
object OrbitAgent {
    enum class Phase { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

    @Volatile var phase = Phase.IDLE
        private set
    /** Head-locked caption in the glasses (null = hidden). Never in recordings. */
    @Volatile var captionArt: Bitmap? = null
        private set
    @Volatile var lastHeard: String = ""
        private set
    @Volatile var lastAnswer: String = ""
        private set
    @Volatile var lastError: String? = null
        private set
    /** True from "Hey Orbit" until Orbit steps back: it keeps listening between answers. */
    @Volatile var inSession = false
        private set
    /** Microphone loudness while listening, 0..1 (the buddy's ears). */
    @Volatile var micLevel = 0f
        private set
    /** When Orbit was last called (uptime ms): the buddy pops up. */
    @Volatile var calledAt = 0L
        private set

    /** Compose: bumped on every change so the phone's agent card redraws. */
    val version = mutableIntStateOf(0)

    lateinit var link: OrbitLink
        private set
    private lateinit var app: Context
    private var host: GlassesHost? = null
    private var tools: GlassesTools? = null
    private val main = Handler(Looper.getMainLooper())
    private val net by lazy { Handler(HandlerThread("orbit-agent").apply { start() }.looper) }
    private val history = ArrayDeque<Pair<String, String>>()
    @Volatile private var busy = false
    private var micHeld = false
    private var lastActive = 0L
    private var quickMisses = 0
    private val hide = Runnable {
        captionArt = null
        if (phase == Phase.SPEAKING || phase == Phase.ERROR) setPhase(Phase.IDLE) else changed()
    }

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        link = OrbitLink(app)
        Voices.init(app)
        OrbitBuddy.init(app)
    }

    /** MainActivity attaches the glasses (screens, apps, background) while it's alive. */
    fun attach(h: GlassesHost?) { host = h; tools = h?.let { GlassesTools(it) { typed -> show(Phase.THINKING, "Typing: $typed") } } }

    val paired get() = ::link.isInitialized && link.paired

    private fun now() = android.os.SystemClock.uptimeMillis()

    // ---------------------------------------------------------------- conversation

    /**
     * "Hey Orbit", the dock's mic or Ctrl+Alt+O. Starts a conversation and listens. The mic (not the wake word)
     * while Orbit is waiting for you ends the conversation instead.
     */
    fun listen(fromWake: Boolean = false) {
        main.post {
            if (inSession && !fromWake && phase == Phase.LISTENING && !busy) { endSession(); return@post }
            if (busy || phase == Phase.LISTENING) return@post
            if (!paired) { show(Phase.ERROR, "Pair your Orbit Server first: phone → Orbit agent", 6_000); return@post }
            calledAt = now()
            if (!inSession) { inSession = true; holdMic(true) }
            lastActive = now(); quickMisses = 0
            hear(first = true)
        }
    }

    /** One listen inside the conversation. The first shows "Listening…"; later ones keep the last answer on screen. */
    private fun hear(first: Boolean) {
        Voices.stop()
        if (first) show(Phase.LISTENING, "Listening…") else { main.removeCallbacks(hide); setPhase(Phase.LISTENING); main.postDelayed(hide, 6_000) }
        val started = now()
        Voice.listenOnce(app,
            onPartial = { lastActive = now(); show(Phase.LISTENING, "$it…") },
            onLevel = { micLevel = it },
            onSpeech = { lastActive = now() },
        ) { text, error ->
            micLevel = 0f
            if (!inSession) return@listenOnce
            val heard = text?.let { Conversation.classify(it) }
            when {
                heard is Conversation.Heard.Bye -> { lastActive = now(); signOff(BYE_LINE) }
                heard is Conversation.Heard.Ask -> { lastActive = now(); quickMisses = 0; ask(heard.text) }
                error != null && error != Voice.MISSED -> { show(Phase.ERROR, error, 4_000); endSession(keepCaption = true) }
                Conversation.idle(lastActive, now(), SESSION_IDLE_MS) -> signOff(AWAY_LINE)
                else -> {
                    // Nothing said: listen again. A recogniser that gives up at once is backed off, then dropped.
                    val quick = now() - started < 1_200
                    quickMisses = if (quick) quickMisses + 1 else 0
                    if (quickMisses > 6) { DiagLog.i(TAG, "listening keeps failing; ending the conversation"); endSession() }
                    else main.postDelayed({ if (inSession && !busy && phase == Phase.LISTENING) hear(first = false) }, if (quick) 700L else 0L)
                }
            }
        }
    }

    /** Orbit says it's stepping back, then waits for "Hey Orbit". */
    private fun signOff(line: String) {
        Voice.cancel()
        show(Phase.SPEAKING, line, holdMs = 5_000)
        Voices.speak(line) { endSession(keepCaption = true) }
    }

    /** Ends the conversation now (the wake word listens again). */
    fun endSession(keepCaption: Boolean = false) {
        main.post {
            if (!inSession) return@post
            inSession = false
            Voice.cancel()
            micLevel = 0f
            holdMic(false)
            if (!keepCaption) { captionArt = null; main.removeCallbacks(hide) }
            if (phase == Phase.LISTENING || (!keepCaption && phase != Phase.THINKING)) setPhase(Phase.IDLE) else changed()
            DiagLog.i(TAG, "conversation ended")
        }
    }

    /** The wake word and the agent share the microphone: while Orbit has it, "Hey Orbit" listening pauses. */
    private fun holdMic(on: Boolean) {
        if (on == micHeld) return
        micHeld = on
        if (on) WakeWord.pause() else WakeWord.resume()
    }

    // ---------------------------------------------------------------- asking

    /** Sends [text] to the agent and runs what it asks for. Main thread or any. */
    fun ask(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        main.post {
            if (busy) { DiagLog.i(TAG, "ask ignored: busy"); return@post }
            val tl = tools ?: run { DiagLog.i(TAG, "ask: no glasses host"); show(Phase.ERROR, "Open OrbitWalker on the phone first", 4_000); return@post }
            DiagLog.i(TAG, "ask (${t.length} chars)")
            busy = true
            Voices.stop()
            lastHeard = t
            show(Phase.THINKING, "“$t”")
            val context = runCatching { tl.context() }.getOrElse { JSONObject() }
            val specs = tl.specs()
            val hist = JSONArray().apply { history.forEach { (r, x) -> put(JSONObject().put("role", r).put("text", x)) } }
            val conv = conversation ?: Conversation.newId().also { conversation = it }
            net.post { converse(t, hist, context, specs, conv) }
        }
    }

    /** Network thread: ask, then run tool calls and continue until the agent answers. */
    private fun converse(text: String, hist: JSONArray, context: JSONObject, specs: JSONArray, conv: String) {
        DiagLog.i(TAG, "asking the server")
        try {
            var reply = link.post("/v1/walker/ask", JSONObject()
                .put("text", text).put("history", hist).put("context", context).put("tools", specs)
                .put("conversation", conv).put("zone", java.util.TimeZone.getDefault().id))
            adoptConversation(reply)
            var rounds = 0
            while (reply.optString("status") == "tools" && rounds++ < MAX_ROUNDS) {
                reply.optString("say").takeIf { it.isNotBlank() && it != "null" }?.let { s -> main.post { show(Phase.THINKING, s) } }
                val results = JSONArray()
                val calls = reply.optJSONArray("calls") ?: JSONArray()
                for (i in 0 until calls.length()) {
                    val c = calls.getJSONObject(i)
                    val (content, isError) = runOnMain(c.optString("name"), c.optJSONObject("args") ?: JSONObject())
                    DiagLog.i(TAG, "tool ${c.optString("name")} → ${if (isError) "error" else "ok"}")
                    results.put(JSONObject().put("id", c.optString("id")).put("content", content).put("isError", isError))
                }
                reply = link.post("/v1/walker/continue", JSONObject().put("turn", reply.optString("turn")).put("results", results))
                adoptConversation(reply)
            }
            val answer = reply.optString("answer").takeIf { it.isNotBlank() && it != "null" } ?: "Done."
            main.post {
                remember("user", text); remember("assistant", answer)
                lastAnswer = answer; lastError = null
                busy = false
                answerOutLoud(answer)
            }
        } catch (e: ServerException) {
            DiagLog.i(TAG, "server said ${e.status} ${e.code}")
            fail(when (e.code) {
                "no_model" -> "No model is set on your Orbit Server (dashboard → Agent)"
                "rate_limited" -> "Too many requests: wait a moment"
                "model_error" -> "The model didn't answer. Try again."
                "unauthorized", "http_401" -> "This phone isn't paired any more: pair it again"
                else -> "Orbit Server: ${e.code}"
            })
        } catch (e: Exception) {
            DiagLog.e(TAG, "agent request failed", e)
            fail("Can't reach your Orbit Server (${e.javaClass.simpleName})")
        }
    }

    /** The server keeps the conversation (for memory later); it may start a new one, e.g. after hours apart. */
    private fun adoptConversation(reply: JSONObject) {
        reply.optString("conversation").takeIf { Conversation.validId(it) }?.let { id -> if (id != conversation) main.post { conversation = id } }
    }

    private fun fail(msg: String) = main.post {
        busy = false; lastError = msg
        show(Phase.ERROR, msg, 5_000)
        endSession(keepCaption = true)
    }

    /** A tool call on the main thread (screens, recorder); the network thread waits for its result. */
    private fun runOnMain(name: String, args: JSONObject): Pair<String, Boolean> {
        val tl = tools ?: return "The glasses aren't available" to true
        val task = FutureTask {
            try { tl.run(name, args) to false } catch (e: GlassesTools.ToolError) { (e.message ?: "failed") to true }
            catch (e: Exception) { DiagLog.e(TAG, "tool $name failed", e); "Failed: ${e.javaClass.simpleName}" to true }
        }
        main.post(task)
        return runCatching { task.get(10, TimeUnit.SECONDS) }.getOrElse { "Timed out" to true }
    }

    private fun remember(role: String, text: String) {
        history.addLast(role to text.take(2_000))
        while (history.size > MAX_HISTORY) history.removeFirst()
    }

    private fun prefs() = app.getSharedPreferences("orbit_agent", Context.MODE_PRIVATE)

    /** This conversation's id on the server (kept across app restarts; the server ends stale ones). */
    private var conversation: String?
        get() = if (::app.isInitialized) prefs().getString("conversation", null) else null
        set(v) { if (::app.isInitialized) prefs().edit().putString("conversation", v).apply() }

    /** Starts a fresh conversation (the phone's "New conversation", pairing). */
    fun forgetConversation() { history.clear(); conversation = null; changed() }

    // ---------------------------------------------------------------- speaking

    /** Says the answer; in a conversation, listens again afterwards. */
    private fun answerOutLoud(text: String) {
        show(Phase.SPEAKING, text, holdMs = (2_500 + text.length * 70L).coerceAtMost(14_000))
        holdMic(true) // don't hear ourselves
        Voices.speak(text) {
            if (inSession) { lastActive = now(); quickMisses = 0; if (!busy) hear(first = false) }
            else holdMic(false)
        }
    }

    // ---------------------------------------------------------------- caption

    private fun show(p: Phase, text: String, holdMs: Long = 0) {
        main.removeCallbacks(hide)
        captionArt = Caption.render(p, text)
        setPhase(p)
        if (holdMs > 0) main.postDelayed(hide, holdMs)
        else if (p != Phase.LISTENING && p != Phase.THINKING) main.postDelayed(hide, 6_000)
    }

    private fun setPhase(p: Phase) { phase = p; changed() }
    private fun changed() { if (Looper.myLooper() == Looper.getMainLooper()) version.intValue++ else main.post { version.intValue++ } }

    private const val TAG = "agent"
    private const val MAX_ROUNDS = 8
    private const val MAX_HISTORY = 12
    /** Silence after which Orbit steps back from a conversation. */
    const val SESSION_IDLE_MS = 30_000L
    private const val AWAY_LINE = "I'll step back for now. Just say Hey Orbit whenever you need me."
    private const val BYE_LINE = "Okay! I'm right here whenever you need me. Just say Hey Orbit."
}

/** Conversation rules. Pure. */
object Conversation {
    private val ID = Regex("[A-Za-z0-9_-]{1,64}")
    private val ENDINGS = setOf(
        "that's all", "that is all", "that's it", "that's all for now", "that's it for now", "that will be all",
        "goodbye", "bye", "bye bye", "see you", "see you later", "good night",
        "stop listening", "go to sleep", "go away", "never mind", "nevermind", "nothing", "nothing else",
        "no thanks", "no thank you", "thanks", "thank you", "thanks that's all", "thank you that's all",
        "thanks that's it", "thank you that's it", "i'm done", "we're done", "all done", "done", "cancel", "stop",
    )
    private val WAKE = Regex("^(hey|hi|okay|ok)[ ,]+orbit\\b[ ,.!]*", RegexOption.IGNORE_CASE)

    sealed interface Heard {
        data class Ask(val text: String) : Heard
        data object Bye : Heard
        data object Nothing : Heard
    }

    /** What was heard means: a request (any "Hey Orbit" in front dropped), a goodbye, or nothing to act on. */
    fun classify(heard: String): Heard {
        val t = WAKE.replace(heard.trim(), "").trim()
        val bare = t.lowercase().replace(Regex("[^a-z' ]"), " ").replace(Regex("\\borbit\\b"), " ").replace(Regex("\\s+"), " ").trim()
        return when {
            bare.isEmpty() -> Heard.Nothing
            bare in ENDINGS -> Heard.Bye
            else -> Heard.Ask(t)
        }
    }

    fun idle(lastActive: Long, now: Long, limitMs: Long) = now - lastActive >= limitMs

    fun validId(s: String?) = s != null && ID.matches(s)

    fun newId(): String = java.util.UUID.randomUUID().toString().replace("-", "")
}

/** The agent's caption: a dark pill with a coloured state dot and up to three lines of text. */
object Caption {
    private const val W = 1400

    fun render(phase: OrbitAgent.Phase, text: String): Bitmap {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 40f }
        val label = when (phase) {
            OrbitAgent.Phase.LISTENING -> "Orbit · listening"
            OrbitAgent.Phase.THINKING -> "Orbit · working"
            OrbitAgent.Phase.SPEAKING -> "Orbit"
            OrbitAgent.Phase.ERROR -> "Orbit · problem"
            OrbitAgent.Phase.IDLE -> "Orbit"
        }
        val textW = W - 150
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, textW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(3).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        val h = 84 + layout.height + 30
        val b = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawRoundRect(RectF(0f, 0f, W.toFloat(), h.toFloat()), 40f, 40f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 12, 10, 30) })
        val dot = when (phase) {
            OrbitAgent.Phase.LISTENING -> Color.rgb(125, 245, 214)
            OrbitAgent.Phase.THINKING -> Color.rgb(124, 156, 255)
            OrbitAgent.Phase.ERROR -> Color.rgb(255, 120, 130)
            else -> Color.rgb(200, 190, 255)
        }
        c.drawCircle(64f, 52f, 16f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dot })
        c.drawText(label, 100f, 64f, TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(170, 180, 205); textSize = 32f; isFakeBoldText = true })
        c.save(); c.translate(100f, 84f); layout.draw(c); c.restore()
        return b
    }
}
