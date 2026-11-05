package dev.avijit.orbit.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.avijit.orbit.input.TrackpadAction
import dev.avijit.orbit.input.TrackpadInterpreter
import dev.avijit.orbit.ui.theme.Orbit

@Composable
fun TrackpadScreen(state: UiState, actions: OrbitActions, onKeys: () -> Unit, onFullscreen: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.moveMode) "Move screen" else "Trackpad", style = MaterialTheme.typography.headlineSmall, color = Orbit.Text1, modifier = Modifier.weight(1f))
            state.screens.firstOrNull { it.focused }?.let { StatusPill(it.label, Orbit.Accent) }
        }
        if (state.mirrorOn) {
            StatusLine("Mirror mode: touch your phone directly. The trackpad controls app screens (needs the helper).")
        }
        if (state.input?.let { it.keyboard != dev.avijit.orbit.input.Link.NONE || it.mouse != dev.avijit.orbit.input.Link.NONE } == true) {
            InputStatusCard(state, actions)
        } else if (state.mouseCaptured) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Orbit.Accent.copy(alpha = 0.12f)).clickable { actions.releaseMouseCapture() }.padding(horizontal = 12.dp, vertical = 9.dp)) {
                Text("Mouse drives the glasses pointer · Esc, back gesture or tap here to release", color = Orbit.Accent, fontSize = 12.sp)
            }
        } else if (state.mouseConnected && state.mouseReleasedByUser && state.screens.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Orbit.Surface2).clickable { actions.useMouseForGlasses() }.padding(horizontal = 12.dp, vertical = 9.dp)) {
                Text("Mouse is on the phone · tap to use it in the glasses", color = Orbit.Text2, fontSize = 12.sp)
            }
        }
        StatusLine(if (state.moveMode) "One finger moves the screen · two fingers push or pull · pinch resizes" else state.status)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            TrackpadSurface(
                Modifier.fillMaxSize().clip(RoundedCornerShape(26.dp))
                    .background(Brush.verticalGradient(listOf(Orbit.Surface2, Orbit.Surface1)))
                    .border(1.dp, if (state.moveMode) Orbit.Accent.copy(alpha = 0.6f) else Orbit.Line, RoundedCornerShape(26.dp)),
                actions,
            )
            Text(
                if (state.moveMode) "Drag to move · pinch to resize" else "Move · tap to click · two fingers scroll",
                color = Orbit.Text3, fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center),
            )
            Box(
                Modifier.align(Alignment.TopEnd).padding(12.dp).size(40.dp).clip(RoundedCornerShape(12.dp))
                    .background(Orbit.Surface3).clickable(onClick = onFullscreen),
                contentAlignment = Alignment.Center,
            ) { Icon(OrbitIcons.Expand, "Full screen", tint = Orbit.Text1, modifier = Modifier.size(18.dp)) }
        }
        if (state.moveMode) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("Reset screen", Modifier.weight(1f)) { actions.resetScreen() }
                GradientButton("Done", Modifier.weight(1f)) { actions.setMoveMode(false) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction(OrbitIcons.Back, "Back", modifier = Modifier.weight(1f)) { actions.back() }
                QuickAction(OrbitIcons.Keyboard, keysLabel(state), modifier = Modifier.weight(1f), onClick = onKeys)
                QuickAction(OrbitIcons.Switch, "Switch", modifier = Modifier.weight(1f)) { actions.switchScreen() }
                QuickAction(OrbitIcons.Recenter, "Recenter", modifier = Modifier.weight(1f)) { actions.recenter() }
            }
        }
    }
}

@Composable
fun FullscreenTrackpad(state: UiState, actions: OrbitActions, onKeys: () -> Unit, onExit: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        TrackpadSurface(Modifier.fillMaxSize(), actions)
        Text(
            if (state.moveMode) "Moving ${state.screens.firstOrNull { it.focused }?.label ?: "screen"} · tap Done when finished" else "Full-screen trackpad",
            color = Color(0xFF3A4456), fontSize = 12.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp),
        )
        Row(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).fillMaxWidth()
                .clip(RoundedCornerShape(22.dp)).background(Orbit.Surface2.copy(alpha = 0.9f))
                .border(1.dp, Orbit.Line, RoundedCornerShape(22.dp)).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            if (state.moveMode) {
                BarItem(OrbitIcons.Recenter, "Reset") { actions.resetScreen() }
                BarItem(OrbitIcons.Move, "Done") { actions.setMoveMode(false) }
            } else {
                BarItem(OrbitIcons.Back, "Back") { actions.back() }
                BarItem(OrbitIcons.Keyboard, keysLabel(state), onKeys)
                BarItem(OrbitIcons.Switch, "Switch") { actions.switchScreen() }
                BarItem(OrbitIcons.Recenter, "Center") { actions.recenter() }
            }
            BarItem(OrbitIcons.Collapse, "Exit", onExit)
        }
    }
}

@Composable
private fun BarItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = Orbit.Text1, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = Orbit.Text2, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

/** Raw multi-touch → [TrackpadInterpreter] → actions. */
@SuppressLint("ClickableViewAccessibility")
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TrackpadSurface(modifier: Modifier, actions: OrbitActions) {
    val interpreter = remember { TrackpadInterpreter() }
    Box(
        modifier.pointerInteropFilter { e ->
            val t = e.eventTime
            val i = e.actionIndex
            val out = when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> interpreter.down(e.getPointerId(i), e.getX(i), e.getY(i), t)
                MotionEvent.ACTION_MOVE -> (0 until e.pointerCount).flatMap { p -> interpreter.move(e.getPointerId(p), e.getX(p), e.getY(p), t) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> interpreter.up(e.getPointerId(i), e.getX(i), e.getY(i), t)
                MotionEvent.ACTION_CANCEL -> (0 until e.pointerCount)
                    .flatMap { p -> interpreter.up(e.getPointerId(p), e.getX(p), e.getY(p), t + 10_000) }
                    .filter { it !is TrackpadAction.Tap }
                else -> emptyList()
            }
            out.forEach(actions::onTrackpad)
            true
        },
    )
}

/** "Type" when this screen needs phone typing; otherwise the keyboard is in the glasses and this hides it. */
private fun keysLabel(state: UiState) = "Keys"

/**
 * Bluetooth/USB keyboard and mouse at a glance: where each one is going right now, when the last
 * key went through, and, when keys aren't reaching the glasses, why in plain words.
 */
@Composable
fun InputStatusCard(state: UiState, actions: OrbitActions) {
    val s = state.input ?: return
    if (s.keyboard == dev.avijit.orbit.input.Link.NONE && s.mouse == dev.avijit.orbit.input.Link.NONE) return
    val anyInGlasses = s.keyboard == dev.avijit.orbit.input.Link.GLASSES || s.mouse == dev.avijit.orbit.input.Link.GLASSES
    val tap: (() -> Unit)? = when {
        anyInGlasses -> ({ actions.releaseRawInputToPhone() })
        state.rawReleased -> ({ actions.useRawInput() })
        else -> null
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Orbit.Surface2)
            .then(if (tap != null) Modifier.clickable(onClick = tap) else Modifier)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DeviceLine("Keyboard", s.keyboard, s.keyboardName)
        DeviceLine("Mouse", s.mouse, s.mouseName)
        val bad = s.problem ?: s.reason
        if (bad != null) Text(bad, color = Orbit.Warn, fontSize = 12.sp, lineHeight = 16.sp)
        Text(
            s.lastKey + if (anyInGlasses) " · Ctrl+Esc or tap to give them back to the phone" else "",
            color = Orbit.Text3, fontSize = 11.sp,
        )
    }
}

@Composable
private fun DeviceLine(kind: String, link: dev.avijit.orbit.input.Link, name: String?) {
    val (dot, text) = when (link) {
        dev.avijit.orbit.input.Link.GLASSES -> Orbit.Ok to "In the glasses"
        dev.avijit.orbit.input.Link.PHONE -> Orbit.Warn to "On the phone"
        dev.avijit.orbit.input.Link.NONE -> Orbit.Text3 to "Not connected"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(dot))
        Spacer(Modifier.size(8.dp))
        Text("$kind: $text", color = Orbit.Text1, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        if (name != null) Text("  $name", color = Orbit.Text2, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
