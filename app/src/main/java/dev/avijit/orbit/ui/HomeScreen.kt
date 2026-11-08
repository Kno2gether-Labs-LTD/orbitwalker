package dev.avijit.orbit.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.avijit.orbit.shell.HelperState
import dev.avijit.orbit.ui.theme.Orbit

@Composable
fun HomeScreen(state: UiState, actions: OrbitActions, onAddApp: () -> Unit, onTab: (Tab) -> Unit, onCinema: () -> Unit, onHelp: () -> Unit, onSetup: () -> Unit, onRecord: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OrbitLogo(34.dp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) { Wordmark(22) }
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(12.dp)).background(Orbit.Surface2).clickable(onClick = onHelp),
                contentAlignment = Alignment.Center,
            ) { Text("?", color = Orbit.Text1, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.width(8.dp))
            when {
                state.imuHz != null -> StatusPill("Live", Orbit.Ok)
                state.glassesProblem -> StatusPill("Check", Orbit.Warn)
                else -> StatusPill("Waiting", Orbit.Text3)
            }
        }

        HeroCard(state)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction(OrbitIcons.Recenter, "Recenter", modifier = Modifier.weight(1f)) { actions.recenter() }
            QuickAction(if (state.pinned) OrbitIcons.Pin else OrbitIcons.Follow, if (state.pinned) "Pinned" else "Follow", active = true, modifier = Modifier.weight(1f)) { actions.togglePinned() }
            QuickAction(OrbitIcons.Trackpad, "Trackpad", modifier = Modifier.weight(1f)) { onTab(Tab.TRACKPAD) }
            QuickAction(OrbitIcons.Layout, "Layout", modifier = Modifier.weight(1f)) { onTab(Tab.LAYOUT) }
        }

        StatusLine(state.status)

        SettingRow(
            "Orbit Browser",
            if (state.helper == HelperState.READY && !state.mirrorOn) "Opens as a screen in the glasses. Drag a tab down to give it its own screen."
            else "A built-in browser. Opens on the phone (and in Mirror mode, in the glasses).",
            trailing = { Icon(OrbitIcons.Globe, null, tint = Orbit.Accent, modifier = Modifier.size(22.dp)) },
            onClick = { actions.openBrowser() },
        )
        SettingRow(
            "Orbit agent",
            if (dev.avijit.orbit.agent.OrbitAgent.paired) "Say \"Hey Orbit\" or click the mic in the dock" else "Talk to your glasses: pair your Orbit Server",
            trailing = { Icon(OrbitIcons.Globe, null, tint = Orbit.Accent2, modifier = Modifier.size(22.dp)) },
            onClick = { AgentNav.open.value = true },
        )
        SettingRow(
            "Record",
            "Your glasses view and a mixed reality video of you among your screens",
            trailing = { Icon(OrbitIcons.Record, null, tint = Orbit.Accent, modifier = Modifier.size(22.dp)) },
            onClick = onRecord,
        )

        if (state.mirrorOn) {
            SettingRow("Mirroring your phone", "Your phone screen is pinned in the glasses. Turn off in Settings to open app screens.", trailing = {
                OrbitSwitch(true) { actions.setMirror(false) }
            })
        } else {
            SectionTitle("Screens", "${state.screens.size} of ${state.maxScreens}")
            if (state.screens.size > 6) {
                StatusLine("${state.screens.size} screens open: the phone may get warm. Lower Settings → Screen sharpness if it does.")
            }
            InputStatusCard(state, actions)
            ScreensGrid(state, actions, onAddApp)
            if (state.helper == HelperState.READY && !state.mirrorOn && state.recentApps.isNotEmpty()) RecentRow(state, actions)
            if (state.screens.isEmpty() && state.canReopen) {
                SettingRow(
                    "Reopen last screens",
                    state.reopenLabels.joinToString(" · "),
                    trailing = { Icon(OrbitIcons.Reopen, null, tint = Orbit.Accent, modifier = Modifier.size(22.dp)) },
                    onClick = { actions.reopenLast() },
                )
            }
            if (state.helper != HelperState.READY) {
                HelperCard(state, actions, onSetup)
                Text(
                    "New here? See step-by-step help →", color = Orbit.Accent, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onHelp).padding(vertical = 6.dp),
                )
            }
        }
        if (state.hasGlassesDisplay) {
            state.cinemaError?.let { StatusLine("Cinema: $it") }
            CinemaCard(onCinema)
        }
    }
}

@Composable
private fun HeroCard(state: UiState) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(Orbit.Surface1)
            .background(Brush.radialGradient(listOf(Orbit.Accent.copy(alpha = 0.30f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(900f, 0f), radius = 700f))
            .background(Brush.radialGradient(listOf(Orbit.Accent2.copy(alpha = 0.14f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(0f, 500f), radius = 600f))
            .border(1.dp, Orbit.Line, shape)
            .padding(18.dp),
    ) {
        Text(state.glassesTitle, color = Orbit.Text1, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        if (state.glassesDetail.isNotBlank()) {
            Text(state.glassesDetail, color = if (state.glassesProblem) Orbit.Warn else Orbit.Text2, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Tracking", state.imuHz?.let { "$it Hz" } ?: "—", Modifier.weight(1f))
            Stat("Mode", (if (state.pinned) "Pinned" else "Follow") + if (state.stereo) " · 3D" else "", Modifier.weight(1f))
            Stat(
                "Apps",
                when {
                    state.mirrorOn -> "Mirror"
                    state.helper == HelperState.READY -> "Ready"
                    else -> "Setup"
                },
                Modifier.weight(1f),
                valueColor = if (state.helper == HelperState.READY || state.mirrorOn) Orbit.Ok else Orbit.Warn,
            )
        }
    }
}

@Composable
private fun Stat(key: String, value: String, modifier: Modifier, valueColor: Color = Orbit.Text1) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.04f)).padding(10.dp)) {
        Text(key.uppercase(), color = Orbit.Text3, fontSize = 10.sp, letterSpacing = 0.6.sp)
        Text(value, color = valueColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun ScreensGrid(state: UiState, actions: OrbitActions, onAddApp: () -> Unit) {
    val cells = state.screens.map<ScreenCard, ScreenCard?> { it } + if (state.screens.size < state.maxScreens) listOf(null) else emptyList()
    cells.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { card ->
                Box(Modifier.weight(1f)) {
                    if (card == null) AddCard(state, onAddApp) else ScreenTile(card, actions)
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScreenTile(card: ScreenCard, actions: OrbitActions) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    Box {
        Column(
            Modifier.fillMaxWidth().height(96.dp).clip(shape).background(Orbit.Surface1)
                .border(if (card.focused) 1.5.dp else 1.dp, if (card.focused) Orbit.Accent.copy(alpha = 0.7f) else Orbit.Line, shape)
                .combinedClickable(onClick = { actions.focus(card.slot) }, onLongClick = { menu = true })
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(card.packageName, 26.dp)
                Spacer(Modifier.width(8.dp))
                Text(card.label, color = Orbit.Text1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            Text(
                (if (card.live) card.position else "${card.position} · opening…") + if (card.focused) " · focused" else "",
                color = if (card.focused) Orbit.Accent else Orbit.Text3, fontSize = 11.sp,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Orbit.Surface2) {
            DropdownMenuItem(text = { Text("Move / resize", color = Orbit.Text1) }, onClick = { menu = false; actions.moveScreen(card.slot) })
            DropdownMenuItem(text = { Text("Close", color = Orbit.Error) }, onClick = { menu = false; actions.closeScreen(card.slot) })
        }
    }
}

@Composable
private fun AddCard(state: UiState, onAddApp: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val ready = state.helper == HelperState.READY
    Column(
        Modifier.fillMaxWidth().height(96.dp).clip(shape)
            .border(1.dp, if (ready) Orbit.Accent.copy(alpha = 0.35f) else Orbit.Line, shape)
            .then(if (ready) Modifier.clickable(onClick = onAddApp) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(OrbitIcons.Plus, null, tint = if (ready) Orbit.Accent else Orbit.Text3, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(if (ready) "Add app" else "Set up first", color = if (ready) Orbit.Text1 else Orbit.Text3, fontSize = 12.sp)
    }
}

@Composable
fun HelperCard(state: UiState, actions: OrbitActions, onSetup: () -> Unit) {
    val (title, sub) = when (state.helper) {
        HelperState.NEEDS_SETUP -> "Set up app screens (once)" to
            "OrbitWalker turns on its own helper through Wireless debugging. About a minute, no other apps needed. Mirror mode works without it."
        HelperState.STOPPED -> "Start app screens" to
            if (state.isSamsung) "Needed after a phone restart. Turn off Auto Blocker for now, turn on Wireless debugging (Wi-Fi on), then tap Start."
            else "Needed after a phone restart. Turn on Wireless debugging (Wi-Fi on), then tap Start."
        HelperState.STARTING -> "Starting the helper…" to "This takes a few seconds."
        HelperState.READY -> "App screens ready" to ""
    }
    OrbitCard(Modifier.fillMaxWidth(), highlight = Orbit.Warn.copy(alpha = 0.35f)) {
        Column {
            Text(title, color = Orbit.Text1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Orbit.Text2, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            if (state.helper == HelperState.STOPPED) state.setupMessage?.takeIf { state.setupPhase == dev.avijit.orbit.helper.HelperSetup.Phase.FAILED || state.setupPhase == dev.avijit.orbit.helper.HelperSetup.Phase.START_FAILED }?.let {
                Text(it, color = Orbit.Warn, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(bottom = 10.dp))
            }
            when (state.helper) {
                HelperState.NEEDS_SETUP -> GradientButton("Set up", Modifier.fillMaxWidth()) { onSetup() }
                HelperState.STOPPED -> {
                    GradientButton("Start", Modifier.fillMaxWidth()) { actions.helperAction() }
                    androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
                    if (state.isSamsung) {
                        GhostButton("1 · Open Auto Blocker", Modifier.fillMaxWidth()) { actions.openAutoBlocker() }
                        androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
                    }
                    GhostButton(if (state.isSamsung) "2 · Open Wireless debugging" else "Open Wireless debugging", Modifier.fillMaxWidth()) { actions.openWirelessDebugging() }
                    androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
                    GhostButton("Pair again", Modifier.fillMaxWidth()) { onSetup() }
                }
                else -> Unit
            }
            if (state.helper == HelperState.NEEDS_SETUP || state.helper == HelperState.STOPPED) {
                androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
                GhostButton("Or mirror your phone screen", Modifier.fillMaxWidth(), OrbitIcons.Mirror) { actions.setMirror(true) }
            }
        }
    }
}

/** One-tap reopen of recently used apps (the same list as the dock in the glasses). */
@Composable
private fun RecentRow(state: UiState, actions: OrbitActions) {
    Column {
        Text("Recent", color = Orbit.Text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.recentApps.size) { i ->
                val app = state.recentApps[i]
                Column(
                    Modifier.width(72.dp).clip(RoundedCornerShape(14.dp)).clickable { actions.openApp(app.component) }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AppIcon(app.packageName, 44.dp)
                    Text(app.label, color = Orbit.Text2, fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
