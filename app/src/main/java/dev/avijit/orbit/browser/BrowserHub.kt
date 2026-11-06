package dev.avijit.orbit.browser

import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import dev.avijit.orbit.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/** One open page: its WebView lives on while the tab moves between browser screens. */
class BrowserTab(val id: Long, val context: MutableContextWrapper, val web: WebView) {
    var title: String = ""
    var url: String? = null
    var desktop: Boolean = false
}

/** Process-wide hand-offs between the screens session and Orbit Browser screens. */
object BrowserHub {
    const val COMPONENT = BuildConfig.APPLICATION_ID + "/dev.avijit.orbit.browser.BrowserActivity"
    const val LABEL = "Orbit Browser"

    /** What a new browser screen opens: a page ([url], null = start page) or a tab moved from another screen. */
    data class OpenRequest(val url: String?, val adoptTab: Long?)

    private val pending = ConcurrentHashMap<Int, OpenRequest>()

    /** Session (worker thread): the browser about to launch on [displayId] should open [request]. */
    fun expect(displayId: Int, request: OpenRequest) { pending[displayId] = request }

    /** Opens Orbit Browser on the phone itself (no glasses screens, or Mirror mode). */
    fun openOnPhone(context: android.content.Context, url: String? = null) {
        expect(android.view.Display.DEFAULT_DISPLAY, OpenRequest(url, null))
        context.startActivity(
            android.content.Intent().setClassName(context, "dev.avijit.orbit.browser.BrowserActivity")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Browser screen (main thread): what to open on [displayId], if a screen was asked for. */
    fun take(displayId: Int): OpenRequest? = pending.remove(displayId)

    // Tabs on their way to a new screen (main thread only).
    private val parked = HashMap<Long, BrowserTab>()
    private val main = Handler(Looper.getMainLooper())
    private var nextId = 1L

    fun newTabId(): Long = nextId++

    fun park(tab: BrowserTab) {
        parked[tab.id] = tab
        // If the new screen never opens (all screens in use, helper stopped), don't leak the page.
        main.postDelayed({ parked.remove(tab.id)?.web?.destroy() }, PARK_TIMEOUT_MS)
    }

    fun unpark(id: Long): BrowserTab? = parked.remove(id)

    private const val PARK_TIMEOUT_MS = 20_000L
}
