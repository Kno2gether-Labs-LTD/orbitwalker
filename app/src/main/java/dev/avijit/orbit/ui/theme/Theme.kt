package dev.avijit.orbit.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** OrbitWalker's design tokens: the Orbit family's deep indigo with the Walker teal-to-violet accent. */
object Orbit {
    val Bg = Color(0xFF07061A)
    val Surface1 = Color(0xFF100E2A)
    val Surface2 = Color(0xFF17143A)
    val Surface3 = Color(0xFF201C4A)
    val Line = Color(0xFF2A2662)
    val Text1 = Color(0xFFF2F5FA)
    val Text2 = Color(0xFFAAA6D0)
    val Text3 = Color(0xFF7671A6)
    val Accent = Color(0xFF7C9CFF)
    val Accent2 = Color(0xFF7DF5D6)
    val Ok = Color(0xFF4ADE80)
    val Warn = Color(0xFFFBBF24)
    val Error = Color(0xFFF87171)
    /** OrbitWalker's family gradient: teal → blue → violet (the logo ring). */
    val Gradient = Brush.linearGradient(listOf(Color(0xFF7DF5D6), Color(0xFF6F8CFF), Color(0xFFA98BFF)))
}

private val scheme = darkColorScheme(
    primary = Orbit.Accent,
    onPrimary = Color(0xFF05121A),
    secondary = Orbit.Accent2,
    background = Orbit.Bg,
    onBackground = Orbit.Text1,
    surface = Orbit.Surface1,
    onSurface = Orbit.Text1,
    surfaceVariant = Orbit.Surface2,
    onSurfaceVariant = Orbit.Text2,
    outline = Orbit.Line,
    error = Orbit.Error,
)

private val type = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

@Composable
fun OrbitTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, typography = type, content = content)
