package dev.avijit.orbit.browser

import org.junit.Assert.assertTrue
import org.junit.Test

class StartPageTest {
    @Test fun searchBox_usesTheChosenEngine() {
        assertTrue(StartPage.html(SearchEngine.DUCKDUCKGO).contains("action=\"https://duckduckgo.com/\""))
        assertTrue(StartPage.html(SearchEngine.GOOGLE).contains("action=\"https://www.google.com/search\""))
    }

    @Test fun linksToTheOrbitApps() = assertTrue(StartPage.html(SearchEngine.BING).contains("href=\"https://orbitapps.space/\""))

    @Test fun hasNoScripts() = assertTrue("<script" !in StartPage.html(SearchEngine.DUCKDUCKGO))
}
