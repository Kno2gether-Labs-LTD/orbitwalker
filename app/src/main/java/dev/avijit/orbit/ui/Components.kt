package dev.avijit.orbit.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.avijit.orbit.ui.theme.Orbit

val CardShape = RoundedCornerShape(20.dp)

@Composable
fun OrbitCard(
    modifier: Modifier = Modifier,
    highlight: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(CardShape)
            .background(Orbit.Surface1)
            .border(1.dp, highlight ?: Orbit.Line, CardShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
    ) { content() }
}

@Composable
fun SectionTitle(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = Orbit.Text1, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodySmall, color = Orbit.Accent)
    }
}

@Composable
fun QuickAction(icon: ImageVector, label: String, active: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .height(72.dp)
            .clip(shape)
            .background(if (active) Orbit.Accent.copy(alpha = 0.14f) else Orbit.Surface1)
            .border(1.dp, if (active) Orbit.Accent.copy(alpha = 0.45f) else Orbit.Line, shape)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = Orbit.Text1, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 11.sp, color = if (active) Orbit.Text1 else Orbit.Text2, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

@Composable
fun GradientButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .height(50.dp)
            .clip(shape)
            .background(if (enabled) Orbit.Gradient else Brush.linearGradient(listOf(Orbit.Surface3, Orbit.Surface3)))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) Color(0xFF05121A) else Orbit.Text3, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .height(50.dp)
            .clip(shape)
            .background(Orbit.Surface2)
            .border(1.dp, Orbit.Line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Orbit.Text1, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Orbit.Text1, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
fun StatusPill(text: String, color: Color) {
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: (Float) -> String, onChange: (Float) -> Unit) {
    OrbitCard(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth()) {
                Text(label, color = Orbit.Text1, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(display(value), color = Orbit.Text2, fontSize = 13.sp)
            }
            Slider(
                value = value, onValueChange = onChange, valueRange = range,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Orbit.Accent, inactiveTrackColor = Orbit.Surface3),
            )
        }
    }
}

@Composable
fun SettingRow(title: String, subtitle: String? = null, subtitleColor: Color = Orbit.Text2, trailing: @Composable () -> Unit = {}, onClick: (() -> Unit)? = null) {
    OrbitCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Orbit.Text1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (subtitle != null) Text(subtitle, color = subtitleColor, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.width(10.dp))
            trailing()
        }
    }
}

@Composable
fun OrbitSwitch(checked: Boolean, onChange: (Boolean) -> Unit) = Switch(
    checked = checked, onCheckedChange = onChange,
    colors = SwitchDefaults.colors(checkedTrackColor = Orbit.Accent, checkedThumbColor = Color.White, uncheckedTrackColor = Orbit.Surface3, uncheckedBorderColor = Orbit.Line),
)

/** An installed app's launcher icon (cached per package). */
@Composable
fun AppIcon(packageName: String, size: Dp) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName).toBitmapSafe() }.getOrNull()
    }
    if (bitmap != null) {
        Image(bitmap, null, Modifier.size(size).clip(RoundedCornerShape(size / 4)))
    } else {
        Box(Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(Orbit.Surface3))
    }
}

/** Orbit's own adaptive icon, drawn as a bitmap (Compose can't paint adaptive-icon XML). */
@Composable
fun OrbitLogo(size: Dp) {
    val context = LocalContext.current
    val bitmap = remember {
        runCatching { androidx.core.content.ContextCompat.getDrawable(context, dev.avijit.orbit.R.mipmap.ic_launcher_round)!!.toBitmapSafe() }.getOrNull()
    }
    if (bitmap != null) Image(bitmap, null, Modifier.size(size).clip(CircleShape))
    else Box(Modifier.size(size).clip(CircleShape).background(Orbit.Gradient))
}

private fun Drawable.toBitmapSafe() = toBitmap(96, 96).asImageBitmap()
