package dev.avijit.orbit.input

/** Where a Bluetooth/USB keyboard or mouse is going right now. */
enum class Link { NONE, PHONE, GLASSES }

/** Everything that decides the keyboard/mouse status line. */
data class InputFacts(
    /** RawInputController.devices: "Name (keyboard); Name (mouse)". */
    val grabbed: String,
    /** External keyboards/mice Android itself currently sees. */
    val phoneKeyboards: List<String>,
    val phoneMice: List<String>,
    val helperReady: Boolean,
    val locked: Boolean,
    val released: Boolean,
    val hasScreens: Boolean,
    val mirror: Boolean,
    val cinema: Boolean,
    val focusedScreen: Boolean,
    /** 0 = no key sent yet. */
    val lastKeyAtMs: Long,
    val lastProblem: String?,
    val lastProblemAtMs: Long,
    val nowMs: Long,
)

data class InputStatus(
    val keyboard: Link,
    val keyboardName: String?,
    val mouse: Link,
    val mouseName: String?,
    /** Why input isn't (fully) reaching the glasses, in plain words; null when all is well. */
    val reason: String?,
    val lastKey: String,
    /** The last key that failed, if within the last [PROBLEM_SHOW_MS]. */
    val problem: String?,
)

private const val PROBLEM_SHOW_MS = 30_000L

fun inputStatus(f: InputFacts): InputStatus {
    val grabbedKb = grabbedNames(f.grabbed, "keyboard")
    val grabbedMouse = grabbedNames(f.grabbed, "mouse")
    val kb = when {
        grabbedKb.isNotEmpty() -> Link.GLASSES
        f.phoneKeyboards.isNotEmpty() -> Link.PHONE
        else -> Link.NONE
    }
    val mouse = when {
        grabbedMouse.isNotEmpty() -> Link.GLASSES
        f.phoneMice.isNotEmpty() -> Link.PHONE
        else -> Link.NONE
    }
    val wanted = f.helperReady && !f.locked && !f.released && ((f.hasScreens && !f.mirror) || f.cinema)
    val reason = when {
        kb == Link.NONE && mouse == Link.NONE -> null
        f.mirror -> "Mirror mode: they work on the phone directly"
        !f.helperReady -> "App screens helper isn't running: tap Start on Home"
        f.locked -> "Paused while the phone is locked: unlock it"
        f.released -> "Given back to the phone (Ctrl+Esc): tap to use them in the glasses"
        !f.hasScreens && !f.cinema -> "Open an app screen to use them in the glasses"
        wanted && kb == Link.PHONE -> "Keyboard just connected: taking over…"
        wanted && mouse == Link.PHONE -> "Mouse just connected: taking over…"
        kb == Link.NONE && mouse == Link.GLASSES -> "Keyboard not connected (asleep?): press a key to wake it"
        kb == Link.GLASSES && !f.focusedScreen && !f.cinema -> "Keys have nowhere to go: Click a screen to focus it"
        else -> null
    }
    val lastKey = if (f.lastKeyAtMs <= 0L) "no keys yet" else {
        val s = ((f.nowMs - f.lastKeyAtMs) / 1000).coerceAtLeast(0)
        if (s < 120) "last key $s s ago" else "last key ${s / 60} min ago"
    }
    val problem = f.lastProblem?.takeIf { f.nowMs - f.lastProblemAtMs <= PROBLEM_SHOW_MS }
    return InputStatus(kb, grabbedKb.firstOrNull() ?: f.phoneKeyboards.firstOrNull(), mouse, grabbedMouse.firstOrNull() ?: f.phoneMice.firstOrNull(), reason, lastKey, problem)
}

private fun grabbedNames(desc: String, kind: String): List<String> =
    desc.split(';').map { it.trim() }.filter { it.endsWith("($kind)") }.map { it.removeSuffix("($kind)").trim() }
