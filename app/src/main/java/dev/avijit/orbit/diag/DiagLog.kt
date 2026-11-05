package dev.avijit.orbit.diag

import android.content.Context
import android.content.Intent
import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** In-memory ring buffer of recent log lines that the user can share (no adb needed). */
object DiagLog {
    private const val MAX_LINES = 2000
    private val lines = ArrayDeque<String>(MAX_LINES)
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun i(tag: String, msg: String) = add("I", tag, msg, null)

    fun e(tag: String, msg: String, t: Throwable? = null) = add("E", tag, msg, t)

    @Synchronized
    fun snapshot(): String = lines.joinToString("\n")

    fun shareIntent(context: Context): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Orbit diagnostics")
            putExtra(Intent.EXTRA_TEXT, snapshot())
        }
        return Intent.createChooser(send, "Share Orbit logs")
    }

    @Synchronized
    private fun add(level: String, tag: String, msg: String, t: Throwable?) {
        val line = buildString {
            append(time.format(Date())).append(' ').append(level).append(' ').append(tag).append(": ").append(msg)
            if (t != null) append(" | ").append(t.javaClass.simpleName).append(": ").append(t.message)
        }
        if (lines.size >= MAX_LINES) lines.removeFirst()
        lines.addLast(line)
        if (level == "E") Log.e("Orbit/$tag", msg, t) else Log.i("Orbit/$tag", msg)
    }
}
