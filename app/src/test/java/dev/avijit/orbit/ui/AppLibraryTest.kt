package dev.avijit.orbit.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLibraryTest {
    private val apps = listOf("Calendar", "Chrome", "Google Maps", "Maps Go", "WhatsApp").map { AppEntry(it, "p/$it", "p") }

    @Test fun emptyQuery_showsEverything() = assertEquals(apps, AppLibrary.filter(apps, "  "))

    @Test fun startsWith_comesBeforeContains() =
        assertEquals(listOf("Maps Go", "Google Maps"), AppLibrary.filter(apps, "maps").map { it.label })

    @Test fun ignoresCase() = assertEquals(listOf("WhatsApp"), AppLibrary.filter(apps, "WHATS").map { it.label })

    @Test fun noMatch_isEmpty() = assertEquals(emptyList<AppEntry>(), AppLibrary.filter(apps, "zzz"))
}
