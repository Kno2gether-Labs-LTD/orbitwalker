package dev.avijit.orbit.shell

/** Interprets `am start -W` output. A package name containing "error" is not a failure. */
object AmStart {
    enum class Outcome { OPENED, UNCONFIRMED, FAILED }
    data class Result(val outcome: Outcome, val reason: String)

    fun parse(exitCode: Int, output: String): Result {
        val lines = output.lines().map { it.trim() }
        lines.firstOrNull { it.startsWith("Error") || it.startsWith("Exception") }?.let {
            return Result(Outcome.FAILED, it)
        }
        if (exitCode != 0) return Result(Outcome.FAILED, "exit $exitCode: ${output.take(300)}")
        lines.firstOrNull { it.startsWith("Warning:") }?.let { return Result(Outcome.UNCONFIRMED, it) }
        lines.firstOrNull { it.startsWith("Status:") && it != "Status: ok" }?.let {
            return Result(Outcome.UNCONFIRMED, it)
        }
        return Result(Outcome.OPENED, "ok")
    }
}

/** VirtualDisplay flags (several are hidden constants). */
object DisplayFlags {
    const val PUBLIC = 1
    const val OWN_CONTENT_ONLY = 1 shl 3
    const val SUPPORTS_TOUCH = 1 shl 6
    const val ROTATES_WITH_CONTENT = 1 shl 7
    const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    const val TRUSTED = 1 shl 10
    const val OWN_DISPLAY_GROUP = 1 shl 11
    const val ALWAYS_UNLOCKED = 1 shl 12
    const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
    const val OWN_FOCUS = 1 shl 14

    fun full(sdk: Int): Int {
        var f = PUBLIC or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or ROTATES_WITH_CONTENT or
            DESTROY_CONTENT_ON_REMOVAL or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED
        if (sdk >= 34) f = f or OWN_FOCUS
        return f
    }

    /** Fallback when the trusted display is refused: drop every flag that needs ADD_TRUSTED_DISPLAY. */
    fun reduced(full: Int): Int = full and (TRUSTED or ALWAYS_UNLOCKED or OWN_DISPLAY_GROUP or OWN_FOCUS).inv()
}
