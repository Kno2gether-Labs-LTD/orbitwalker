package dev.avijit.orbit.browser

import java.net.URLEncoder

enum class SearchEngine(val label: String, val formAction: String) {
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/"),
    GOOGLE("Google", "https://www.google.com/search"),
    BING("Bing", "https://www.bing.com/search"),
    ;

    fun search(query: String): String = "$formAction?q=" + URLEncoder.encode(query, "UTF-8")
}

/** What the address bar does with what was typed: open it as a web address, or search for it. */
object UrlInput {
    private val WEB = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)
    private val LOCAL = Regex("^(localhost|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?(/\\S*)?$", RegexOption.IGNORE_CASE)
    private val HOST = Regex("^([a-z0-9-]+\\.)+[a-z]{2,}(:\\d+)?(/\\S*)?$", RegexOption.IGNORE_CASE)

    /** A URL to load, or null for blank input. Only http(s) is ever opened; anything else is searched. */
    fun resolve(typed: String, engine: SearchEngine): String? {
        val t = typed.trim()
        return when {
            t.isEmpty() -> null
            t.equals("about:blank", ignoreCase = true) -> "about:blank"
            WEB.matches(t) -> t
            LOCAL.matches(t) -> "http://$t"
            HOST.matches(t) -> "https://$t"
            else -> engine.search(t)
        }
    }

    /** The address bar's text for [url]: without the scheme, and empty for a blank page. */
    fun display(url: String?): String = when {
        url == null || url == "about:blank" -> ""
        else -> url.removePrefix("https://").removePrefix("http://")
    }
}
