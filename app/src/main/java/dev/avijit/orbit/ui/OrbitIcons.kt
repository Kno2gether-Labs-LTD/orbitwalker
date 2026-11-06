package dev.avijit.orbit.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Stroke icons from the mockup (24×24, 2 px). Tinted by Icon(). */
object OrbitIcons {
    private fun stroke(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach {
                addPath(
                    addPathNodes(it), stroke = SolidColor(Color.White), strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val Home = stroke("home", "M3 11l9-7 9 7v9H3z")
    val Trackpad = stroke("trackpad", "M6 5h12a3 3 0 0 1 3 3v8a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V8a3 3 0 0 1 3-3z", "M7 15h10")
    val Layout = stroke("layout", "M4 6h10M4 12h16M4 18h7", "M17 4a2 2 0 1 1 0 4a2 2 0 1 1 0-4z", "M14 16a2 2 0 1 1 0 4a2 2 0 1 1 0-4z")
    val Settings = stroke("settings", "M12 9a3 3 0 1 1 0 6a3 3 0 1 1 0-6z", "M12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M5 19l2-2M17 7l2-2")
    val Globe = stroke("globe", "M12 3a9 9 0 1 1 0 18a9 9 0 1 1 0-18z", "M3 12h18", "M12 3c2.4 2.5 3.6 5.5 3.6 9s-1.2 6.5-3.6 9c-2.4-2.5-3.6-5.5-3.6-9s1.2-6.5 3.6-9z")
    val Recenter = stroke("recenter", "M12 9a3 3 0 1 1 0 6a3 3 0 1 1 0-6z", "M12 2v3M12 19v3M2 12h3M19 12h3")
    val Pin = stroke("pin", "M12 17v5M9 3h6l-1 7 4 3H6l4-3z")
    val Follow = stroke("follow", "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z", "M12 9a3 3 0 1 1 0 6a3 3 0 1 1 0-6z")
    val Back = stroke("back", "M15 18l-6-6 6-6")
    val Keyboard = stroke("keyboard", "M5 7h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z", "M7 11h.01M11 11h.01M15 11h.01M8 15h8")
    val Switch = stroke("switch", "M8 4l-4 8 4 8M16 4l4 8-4 8")
    val Expand = stroke("expand", "M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5")
    val Collapse = stroke("collapse", "M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5")
    val Plus = stroke("plus", "M12 5v14M5 12h14")
    val Close = stroke("close", "M6 6l12 12M18 6L6 18")
    val Move = stroke("move", "M12 3v18M3 12h18M9 6l3-3 3 3M9 18l3 3 3-3M6 9l-3 3 3 3M18 9l3 3-3 3")
    val Mirror = stroke("mirror", "M8 3h8a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M11 18h2")
    val Share = stroke("share", "M12 3v12M8 7l4-4 4 4", "M5 13v6a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-6")
    val Enter = stroke("enter", "M20 5v7a3 3 0 0 1-3 3H5", "M9 11l-4 4 4 4")
    val Backspace = stroke("backspace", "M21 5H9l-6 7 6 7h12z", "M12 9l6 6M18 9l-6 6")
    val Chevron = stroke("chevron", "M9 6l6 6-6 6")
    val Reopen = stroke("reopen", "M3 12a9 9 0 1 0 3-6.7", "M3 4v5h5")
}
