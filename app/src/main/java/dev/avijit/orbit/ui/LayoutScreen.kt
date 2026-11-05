package dev.avijit.orbit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.avijit.orbit.layout.ScreenLayout
import dev.avijit.orbit.layout.SlotOffset
import dev.avijit.orbit.ui.theme.Orbit
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun LayoutScreen(state: UiState, actions: OrbitActions) {
    val p = state.layout
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Layout", style = MaterialTheme.typography.headlineSmall, color = Orbit.Text1, modifier = Modifier.weight(1f))
            Text("Reset", color = Orbit.Accent, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { actions.resetLayout() }.padding(8.dp))
        }
        ArcPreview(state)
        GradientButton("Pixel-perfect size · sharpest text", Modifier.fillMaxWidth()) { actions.pixelPerfect() }
        Text(
            "Sizes your screens so one app pixel is exactly one glasses pixel, as sharp as a directly connected display. Then adjust Distance if you like (tap again afterwards).",
            color = Orbit.Text3, fontSize = 12.sp, lineHeight = 16.sp,
        )
        LabeledSlider("Distance", p.distanceM, ScreenLayout.MIN_DISTANCE_M..ScreenLayout.MAX_DISTANCE_M, { "%.1f m".format(it) }) { actions.setLayout(p.copy(distanceM = it)) }
        LabeledSlider("Screen size", p.sizePct, 50f..200f, { "${it.roundToInt()}%" }) { actions.setLayout(p.copy(sizePct = it)) }
        LabeledSlider("Gap", p.gapDeg, 0f..20f, { "${it.roundToInt()}°" }) { actions.setLayout(p.copy(gapDeg = it)) }
        LabeledSlider("Height", p.heightDeg, -20f..20f, { "${it.roundToInt()}°" }) { actions.setLayout(p.copy(heightDeg = it)) }
        if (state.screens.any { it.slot >= 5 }) {
            LabeledSlider("Row gap", p.rowGapDeg, 0f..20f, { "${it.roundToInt()}°" }) { actions.setLayout(p.copy(rowGapDeg = it)) }
        }
        SectionTitle("3D")
        SettingRow(
            "3D desk",
            if (state.stereo) "Screens float at real depth. Switch off for plain 2D." else "Switch the glasses to 3D so your screens float at real depth.",
            trailing = { OrbitSwitch(state.stereo) { actions.set3D(it) } },
        )
        if (state.stereo) {
            LabeledSlider("Eye distance", state.ipdMm, 55f..75f, { "${it.roundToInt()} mm" }) { actions.setIpd(it) }
        }
        val focused = state.screens.firstOrNull { it.focused }
        if (focused != null) {
            OrbitCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("3D content in ${focused.label}", color = Orbit.Text1, fontSize = 14.sp)
                    Text("For videos that are already 3D (side-by-side or top-bottom), e.g. 3D movies or 3D YouTube. Play it full screen.", color = Orbit.Text2, fontSize = 12.sp, lineHeight = 16.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        dev.avijit.orbit.render.ContentMode.entries.forEach { m ->
                            val on = m == state.focusedContent
                            Text(
                                m.label, color = if (on) Orbit.Text1 else Orbit.Text2, fontSize = 12.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                    .background(if (on) Orbit.Accent.copy(alpha = 0.16f) else Orbit.Surface2)
                                    .clickable { actions.setContent(focused.slot, m) }.padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(if (focused != null) "Move ${focused.label}" else "Move focused", Modifier.weight(1f), OrbitIcons.Move) {
                focused?.let { actions.moveScreen(it.slot) }
            }
            GhostButton("Reset screen", Modifier.weight(1f)) { actions.resetScreen() }
        }
        Text(
            "Move lets the trackpad drag the focused screen: one finger moves it, two fingers push it away or pull it closer, pinch resizes it. Your layout is saved.",
            color = Orbit.Text3, fontSize = 12.sp, lineHeight = 16.sp,
        )
    }
}

/** Top-down view of the arc: the user at the bottom, screens as arcs at their distance. */
@Composable
private fun ArcPreview(state: UiState) {
    Canvas(
        Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(20.dp)).background(Orbit.Bg)
            .border(1.dp, Orbit.Line, RoundedCornerShape(20.dp)),
    ) {
        val origin = Offset(size.width / 2, size.height - 18.dp.toPx())
        val pxPerM = (size.height - 36.dp.toPx()) / ScreenLayout.MAX_DISTANCE_M
        drawCircle(Orbit.Accent2, 5.dp.toPx(), origin)
        val slots = if (state.screens.isEmpty()) listOf(0, 1, 2) else state.screens.map { it.slot }
        slots.forEach { slot ->
            val pose = ScreenLayout.pose(slot, state.layout, SlotOffset())
            val focused = state.screens.any { it.slot == slot && it.focused }
            val half = Math.toDegrees(kotlin.math.atan(pose.widthM / 2.0 / pose.distanceM)).toFloat()
            val r = pose.distanceM * pxPerM
            val a0 = Math.toRadians((pose.yawDeg - half).toDouble()); val a1 = Math.toRadians((pose.yawDeg + half).toDouble())
            val p0 = Offset(origin.x + (sin(a0) * r).toFloat(), origin.y - (cos(a0) * r).toFloat())
            val p1 = Offset(origin.x + (sin(a1) * r).toFloat(), origin.y - (cos(a1) * r).toFloat())
            drawLine(
                if (focused) Orbit.Accent else if (state.screens.isEmpty()) Orbit.Surface3 else Orbit.Text2,
                p0, p1, strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round,
            )
        }
        drawCircle(Orbit.Line, ScreenLayout.MAX_DISTANCE_M * pxPerM, origin, style = Stroke(1f))
    }
}
