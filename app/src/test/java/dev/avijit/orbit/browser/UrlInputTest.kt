package dev.avijit.orbit.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlInputTest {
    private val ddg = SearchEngine.DUCKDUCKGO

    @Test fun bareDomain_becomesHttps() = assertEquals("https://example.com", UrlInput.resolve("example.com", ddg))
    @Test fun domainWithPath_becomesHttps() = assertEquals("https://orbitapps.space/orbitwalker/", UrlInput.resolve(" orbitapps.space/orbitwalker/ ", ddg))
    @Test fun fullUrl_isKept() = assertEquals("http://neverssl.com/a?b=c", UrlInput.resolve("http://neverssl.com/a?b=c", ddg))
    @Test fun words_areSearched() = assertEquals("https://duckduckgo.com/?q=orbit+apps+glasses", UrlInput.resolve("orbit apps glasses", ddg))
    @Test fun singleWord_isSearched() = assertEquals("https://duckduckgo.com/?q=weather", UrlInput.resolve("weather", ddg))
    @Test fun specialCharacters_areEncoded() = assertEquals("https://www.google.com/search?q=c%2B%2B+%26+kotlin", UrlInput.resolve("c++ & kotlin", SearchEngine.GOOGLE))
    @Test fun localhostAndIps_useHttp() {
        assertEquals("http://localhost:8080", UrlInput.resolve("localhost:8080", ddg))
        assertEquals("http://192.168.1.5", UrlInput.resolve("192.168.1.5", ddg))
    }
    @Test fun aboutBlank_isKept() = assertEquals("about:blank", UrlInput.resolve("about:blank", ddg))
    @Test fun dangerousSchemes_areSearchedNotOpened() {
        assertEquals("https://duckduckgo.com/?q=javascript%3Aalert%281%29", UrlInput.resolve("javascript:alert(1)", ddg))
        assertEquals("https://duckduckgo.com/?q=file%3A%2F%2F%2Fsdcard%2Fx", UrlInput.resolve("file:///sdcard/x", ddg))
    }
    @Test fun blank_isNull() = assertNull(UrlInput.resolve("   ", ddg))
    @Test fun display_stripsSchemeForTheAddressBar() {
        assertEquals("orbitapps.space/orbitwalker/", UrlInput.display("https://orbitapps.space/orbitwalker/"))
        assertEquals("", UrlInput.display("about:blank"))
    }
}
