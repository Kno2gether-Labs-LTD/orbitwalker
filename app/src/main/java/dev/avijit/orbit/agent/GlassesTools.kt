package dev.avijit.orbit.agent

import dev.avijit.orbit.ScreensSession
import dev.avijit.orbit.record.Recorder
import dev.avijit.orbit.record.ScreenStaging
import dev.avijit.orbit.ui.AppEntry
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** What the agent's glasses tools need from the app (MainActivity provides it). */
interface GlassesHost {
    val screens: ScreensSession
    /** Every app that can open on a screen (Orbit Browser first). */
    fun allApps(): List<AppEntry>
    fun setBackgroundMode(name: String): Boolean
    fun backgroundMode(): String
    fun openStudio()
}

/**
 * The glasses tools the phone declares to the Orbit Server agent (client tools: the server sends calls back, the
 * phone runs them here), and the picture of the glasses it sends with each request.
 */
class GlassesTools(private val host: GlassesHost, private val onTyping: (String) -> Unit = {}) {

    fun specs(): JSONArray = JSONArray().apply {
        tool("open_app", "Open an app on a new screen in the glasses. Use the app's name as shown in the context's apps list.",
            req("name"), "name" to str("App name, e.g. \"YouTube\", \"Chrome\", \"Orbit Browser\"."))
        tool("open_website", "Open a web page, or search the web, in Orbit Browser on a new screen.",
            req("target"), "target" to str("A URL (\"youtube.com\") or search words (\"weather in London\")."))
        tool("focus_screen", "Bring a screen to the front and move the pointer onto it.", req("screen"), "screen" to SCREEN)
        tool("close_screen", "Close one screen.", req("screen"), "screen" to SCREEN)
        tool("close_all_screens", "Close every open screen.", req())
        tool("move_screen", "Move a screen around you: left/right/up/down, or closer/further.",
            req("screen", "direction"), "screen" to SCREEN,
            "direction" to enum("Which way.", "left", "right", "up", "down", "closer", "further"),
            "amount" to enum("How far (default medium).", "small", "medium", "large"))
        tool("resize_screen", "Make a screen bigger or smaller, or put it back to its normal size and place.",
            req("screen", "change"), "screen" to SCREEN, "change" to enum("What to do.", "bigger", "smaller", "reset"))
        tool("reset_layout", "Put every screen back in its default place and size.", req())
        tool("type_text", "Type text into the focused screen (where its text box has focus), optionally pressing Enter after.",
            req("text"), "text" to str("Text to type."), "press_enter" to bool("Press Enter after typing (to send or search)."))
        tool("press_key", "Press a key on the focused screen.", req("key"), "key" to enum("The key.", "back", "enter", "backspace"))
        tool("set_background", "Change the space around the screens.", req("mode"),
            "mode" to enum("orbit = planets of Orbit apps, space = stars, logo, black = plain black.", "orbit", "space", "logo", "black"))
        tool("open_all_apps", "Show the All apps library as a screen (the user picks an app there).", req())
        tool("recording", "Start or stop recording the glasses (and the mixed reality / clean screen files that are turned on).",
            req("action"), "action" to enum("start or stop.", "start", "stop"))
        tool("video_screens", "How screens appear in the mixed reality video.", req("mode"),
            "mode" to enum("framed = the screen in use big behind the user; bigger = all screens larger; real = exactly where they are.",
                "framed", "bigger", "real"))
        tool("open_camera_studio", "Open the camera studio screen (live mixed reality preview and recording controls).", req())
    }

    /** The glasses right now, for the agent's system prompt. Small: names, not pixels. */
    fun context(): JSONObject {
        val s = host.screens
        val panels = s.panels.filterValues { it.component != null }
        val screens = JSONArray()
        panels.keys.sorted().forEach { slot ->
            val p = s.pose(slot)
            screens.put(JSONObject()
                .put("screen", slot + 1)
                .put("app", LabelText.clean(s.labelOf(slot) ?: "?"))
                .put("focused", slot == s.focused)
                .put("where", where(p.yawDeg, p.pitchDeg))
                .put("distance_m", "%.1f".format(java.util.Locale.ROOT, p.distanceM))
                .put("size", "%.0f%%".format(java.util.Locale.ROOT, s.offsets[slot].scale * 100)))
        }
        // App names come from the apps themselves: keep them short and plain, so a label can't smuggle in instructions.
        val apps = JSONArray().apply { host.allApps().take(MAX_APPS).forEach { put(LabelText.clean(it.label)) } }
        return JSONObject()
            .put("screens", screens)
            .put("recent_apps", JSONArray(s.recentApps.take(8).map { it.substringBefore('/') }))
            .put("apps", apps)
            .put("background", host.backgroundMode())
            .put("recording", JSONObject().put("on", Recorder.recording).put("video_screens", Recorder.staging.name.lowercase()))
    }

    /** Runs one tool call; returns the result text for the model (or throws [ToolError]). Main thread. */
    fun run(name: String, args: JSONObject): String {
        val s = host.screens
        return when (name) {
            "open_app" -> {
                val want = args.optString("name")
                val app = findApp(want) ?: throw ToolError("No app called \"$want\". Installed: ${host.allApps().take(40).joinToString { it.label }}")
                s.openApp(app.component)
                "Opening ${app.label}"
            }
            "open_website" -> {
                val t = args.optString("target").trim()
                if (t.isEmpty()) throw ToolError("Nothing to open")
                s.openBrowser(WebTarget.url(t))
                "Opening ${WebTarget.url(t)} in Orbit Browser"
            }
            "focus_screen" -> screen(args).let { s.focusSlot(it); "Focused ${s.labelOf(it)}" }
            "close_screen" -> screen(args).let { val l = s.labelOf(it); s.closeScreen(it); "Closed $l" }
            "close_all_screens" -> { s.closeAll(); "Closed all screens" }
            "move_screen" -> {
                val slot = screen(args)
                val k = when (args.optString("amount")) { "small" -> 0.5f; "large" -> 2f; else -> 1f }
                when (args.optString("direction")) {
                    "left" -> s.adjustScreen(slot, dYawDeg = -STEP_DEG * k)
                    "right" -> s.adjustScreen(slot, dYawDeg = STEP_DEG * k)
                    "up" -> s.adjustScreen(slot, dPitchDeg = STEP_DEG * 0.6f * k)
                    "down" -> s.adjustScreen(slot, dPitchDeg = -STEP_DEG * 0.6f * k)
                    "closer" -> s.adjustScreen(slot, dDistanceM = -STEP_M * k)
                    "further" -> s.adjustScreen(slot, dDistanceM = STEP_M * k)
                    else -> throw ToolError("direction must be left, right, up, down, closer or further")
                }
                "Moved ${s.labelOf(slot)} ${args.optString("direction")}"
            }
            "resize_screen" -> {
                val slot = screen(args)
                when (args.optString("change")) {
                    "bigger" -> s.adjustScreen(slot, scaleBy = 1.2f)
                    "smaller" -> s.adjustScreen(slot, scaleBy = 1f / 1.2f)
                    "reset" -> s.adjustScreen(slot, reset = true)
                    else -> throw ToolError("change must be bigger, smaller or reset")
                }
                "${s.labelOf(slot)}: ${args.optString("change")}"
            }
            "reset_layout" -> { s.resetLayout(); "Layout reset" }
            "type_text" -> {
                if (s.focused == null) throw ToolError("No screen is focused")
                val text = args.optString("text")
                if (text.length > MAX_TYPE) throw ToolError("Too long to type (max $MAX_TYPE characters)")
                onTyping(text)
                s.typeText(text)
                if (args.optBoolean("press_enter")) s.enter()
                "Typed into ${s.focused?.let { s.labelOf(it) }}"
            }
            "press_key" -> {
                if (s.focused == null) throw ToolError("No screen is focused")
                when (args.optString("key")) { "back" -> s.back(); "enter" -> s.enter(); "backspace" -> s.backspace(); else -> throw ToolError("Unknown key") }
                "Pressed ${args.optString("key")}"
            }
            "set_background" -> if (host.setBackgroundMode(args.optString("mode"))) "Background: ${args.optString("mode")}" else throw ToolError("Unknown background")
            "open_all_apps" -> { s.openLibrary(); "Showing All apps" }
            "recording" -> when (args.optString("action")) {
                "start" -> if (Recorder.start()) "Recording started" else throw ToolError(Recorder.lastError ?: "Couldn't start recording")
                "stop" -> { Recorder.stop(); "Recording stopped and saved" }
                else -> throw ToolError("action must be start or stop")
            }
            "video_screens" -> {
                Recorder.staging = when (args.optString("mode")) {
                    "framed" -> ScreenStaging.FRAMED; "bigger" -> ScreenStaging.BIGGER; "real" -> ScreenStaging.REAL
                    else -> throw ToolError("mode must be framed, bigger or real")
                }
                "Video screens: ${Recorder.staging.label}"
            }
            "open_camera_studio" -> { host.openStudio(); "Camera studio opened" }
            else -> throw ToolError("Unknown tool $name")
        }
    }

    /** The screen a call means: "this"/"focused", a screen number, or an app name. */
    private fun screen(args: JSONObject): Int {
        val s = host.screens
        val open = s.panels.filterValues { it.component != null }.keys
        if (open.isEmpty()) throw ToolError("No screens are open")
        val want = args.optString("screen").trim().lowercase()
        if (want.isEmpty() || want in setOf("this", "focused", "current", "this screen", "it")) {
            return s.focused?.takeIf { it in open } ?: throw ToolError("No screen is focused")
        }
        want.removePrefix("screen").trim().toIntOrNull()?.let { n -> if (n - 1 in open) return n - 1 }
        open.firstOrNull { s.labelOf(it)?.lowercase() == want }?.let { return it }
        open.firstOrNull { s.labelOf(it)?.lowercase()?.contains(want) == true }?.let { return it }
        throw ToolError("No open screen matches \"$want\". Open: ${open.sorted().joinToString { "${it + 1} ${s.labelOf(it)}" }}")
    }

    private fun findApp(name: String): AppEntry? = AppMatch.best(host.allApps(), name)

    private fun where(yaw: Float, pitch: Float): String {
        val h = when { abs(yaw) < 10 -> "ahead"; yaw < 0 -> "left ${(-yaw).toInt()}°"; else -> "right ${yaw.toInt()}°" }
        val v = when { pitch > 8 -> ", up"; pitch < -8 -> ", down"; else -> "" }
        return h + v
    }

    class ToolError(message: String) : Exception(message)

    private companion object {
        const val MAX_APPS = 250
        const val MAX_TYPE = 500

        const val STEP_DEG = 12f
        const val STEP_M = 0.3f
        val SCREEN = str("Which screen: \"this\" for the focused one, its number, or its app name.")

        fun JSONArray.tool(name: String, description: String, required: JSONArray, vararg props: Pair<String, JSONObject>) {
            val p = JSONObject(); props.forEach { (k, v) -> p.put(k, v) }
            put(JSONObject().put("name", name).put("description", description)
                .put("parameters", JSONObject().put("type", "object").put("properties", p).put("required", required)))
        }
        fun req(vararg names: String) = JSONArray(names.toList())
        fun str(d: String) = JSONObject().put("type", "string").put("description", d)
        fun bool(d: String) = JSONObject().put("type", "boolean").put("description", d)
        fun enum(d: String, vararg v: String) = JSONObject().put("type", "string").put("description", d).put("enum", JSONArray(v.toList()))
    }
}

/** Matching what was said to an installed app's name. Pure. */
object AppMatch {
    fun best(apps: List<AppEntry>, said: String): AppEntry? {
        val w = norm(said)
        if (w.isEmpty()) return null
        return apps.firstOrNull { norm(it.label) == w }
            ?: apps.filter { norm(it.label).startsWith(w) }.minByOrNull { it.label.length }
            ?: apps.filter { norm(it.label).contains(w) }.minByOrNull { it.label.length }
            ?: apps.filter { w.contains(norm(it.label)) && norm(it.label).length >= 3 }.maxByOrNull { it.label.length }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")
}

/** A spoken web target as a URL: a domain stays a domain, anything else becomes a search. Pure. */
object WebTarget {
    private val DOMAIN = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+(/\\S*)?$", RegexOption.IGNORE_CASE)

    fun url(t: String): String {
        val s = t.trim()
        return when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            DOMAIN.matches(s) -> "https://$s"
            else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(s, "UTF-8")
        }
    }
}

/** App labels come from the apps themselves: before they go into the agent's prompt, keep only plain text. Pure. */
object LabelText {
    /** Letters, numbers, spaces, punctuation and symbols; whitespace collapsed; at most 40 characters. */
    fun clean(label: String): String =
        label.replace(Regex("[^\\p{L}\\p{N}\\p{Zs}\\p{P}\\p{S}]"), " ").replace(Regex("\\s+"), " ").trim().take(40)
}
