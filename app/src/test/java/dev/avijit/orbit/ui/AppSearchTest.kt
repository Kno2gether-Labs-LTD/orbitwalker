package dev.avijit.orbit.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSearchTest {
    private fun app(label: String, pkg: String) = AppEntry(label, "$pkg/.Main", pkg)
    private val apps = listOf(
        app("Google Chrome", "com.android.chrome"),
        app("Chess", "org.chess"),
        app("RustDesk", "com.carriez.flutter_hbb"),
        app("Microsoft Teams", "com.microsoft.teams"),
        app("Settings", "com.android.settings"),
    )

    @Test fun emptyQuery_returnsAllInOrder() = assertEquals(apps, filterApps(apps, "  "))

    @Test fun caseInsensitive_prefixFirst_thenWordPrefix() =
        assertEquals(listOf("Chess", "Google Chrome"), filterApps(apps, "CH").map { it.label })

    @Test fun matchesInsideLabel() = assertEquals(listOf("RustDesk"), filterApps(apps, "desk").map { it.label })

    @Test fun matchesPackageName() = assertEquals(listOf("RustDesk"), filterApps(apps, "flutter").map { it.label })

    @Test fun noMatch_empty() = assertEquals(emptyList<AppEntry>(), filterApps(apps, "zzz"))
}
