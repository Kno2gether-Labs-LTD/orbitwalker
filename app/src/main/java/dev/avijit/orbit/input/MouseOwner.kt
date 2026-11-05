package dev.avijit.orbit.input

/**
 * Who owns the Bluetooth mouse. The phone window and the glasses window can each hold pointer
 * capture; callbacks from the two can arrive in any order, so each is tracked separately.
 */
data class MouseOwner(
    val phoneCaptured: Boolean = false,
    val glassesCaptured: Boolean = false,
    /** The user gave the mouse back to Android (Esc / back / the bar) until they reclaim it. */
    val userReleased: Boolean = false,
) {
    val captured: Boolean get() = phoneCaptured || glassesCaptured

    fun phone(has: Boolean) = copy(phoneCaptured = has)
    fun glasses(has: Boolean) = copy(glassesCaptured = has)
    fun presentationGone() = copy(glassesCaptured = false)
    fun release() = copy(userReleased = true)
    fun reclaim() = copy(userReleased = false)

    /** OrbitWalker should drive (and hide the system pointer for) the mouse right now. */
    fun wants(screensReady: Boolean) = !userReleased && screensReady
}
