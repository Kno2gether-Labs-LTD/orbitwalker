package dev.avijit.orbit.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.avijit.orbit.ui.theme.Orbit

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", OrbitIcons.Home),
    TRACKPAD("Trackpad", OrbitIcons.Trackpad),
    LAYOUT("Layout", OrbitIcons.Layout),
    SETTINGS("Settings", OrbitIcons.Settings),
}

@Composable
fun OrbitApp(state: UiState, actions: OrbitActions) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var cinemaPicker by remember { mutableStateOf(false) }
    var help by rememberSaveable { mutableStateOf(false) }
    var setup by rememberSaveable { mutableStateOf(false) }
    var record by rememberSaveable { mutableStateOf(false) }
    var keyboard by remember { mutableStateOf(false) }
    var keysMenu by remember { mutableStateOf(false) }
    // Keys: toggles OrbitWalker's own keyboard in the glasses (works everywhere, including passwords).
    // Long-standing alternatives (phone typing, hiding Android's keyboard) are in the Keys menu.
    val onKeys = { keysMenu = true }
    BackHandler(enabled = state.mouseCaptured) { actions.releaseMouseCapture() }

    LaunchedEffect(fullscreen) { actions.setFullscreen(fullscreen) }
    LaunchedEffect(state.moveMode) { if (state.moveMode && !fullscreen) tab = Tab.TRACKPAD }
    BackHandler(enabled = fullscreen) { fullscreen = false }
    BackHandler(enabled = !fullscreen && state.moveMode) { actions.setMoveMode(false) }

    Box(Modifier.fillMaxSize().background(Orbit.Bg)) {
        if (record) {
            BackHandler { record = false }
            RecordScreen(onClose = { record = false })
        } else if (setup) {
            BackHandler { setup = false }
            SetupScreen(state, actions, onClose = { setup = false })
        } else if (help) {
            BackHandler { help = false }
            HelpScreen(onOpenVideos = { actions.openVideoGuides() }, onClose = { help = false })
        } else if (state.cinemaApp != null) {
            BackHandler { actions.exitCinema() }
            CinemaScreen(state, actions)
        } else if (fullscreen) {
            FullscreenTrackpad(state, actions, onKeys = onKeys, onExit = { fullscreen = false })
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        Tab.HOME -> HomeScreen(state, actions, onAddApp = { picker = true }, onTab = { tab = it }, onCinema = { cinemaPicker = true }, onHelp = { help = true }, onSetup = { setup = true }, onRecord = { record = true })
                        Tab.TRACKPAD -> TrackpadScreen(state, actions, onKeys = onKeys, onFullscreen = { fullscreen = true })
                        Tab.LAYOUT -> LayoutScreen(state, actions)
                        Tab.SETTINGS -> SettingsScreen(state, actions, onHelp = { help = true }, onSetup = { setup = true })
                    }
                }
                BottomTabs(tab) { tab = it }
            }
        }
        if (picker) AppPickerSheet(actions, "Open on the glasses", actions::launchableApps, actions::openApp, onDismiss = { picker = false })
        if (cinemaPicker) AppPickerSheet(actions, "Watch in Cinema mode", actions::launchableAppsAll, actions::startCinema, onDismiss = { cinemaPicker = false })
        if (keyboard) KeyboardSheet(actions, onDismiss = { keyboard = false })
        if (keysMenu) KeysMenu(actions, state.keyboardInGlasses, onPhoneTyping = { keysMenu = false; keyboard = true }, onDismiss = { keysMenu = false })
    }
}

@Composable
private fun BottomTabs(current: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Orbit.Bg).navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        Tab.entries.forEach { t ->
            val on = t == current
            Column(
                Modifier.clip(RoundedCornerShape(14.dp)).clickable { onSelect(t) }.padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) Orbit.Accent.copy(alpha = 0.14f) else Color.Transparent)
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                ) { Icon(t.icon, t.label, tint = if (on) Orbit.Accent else Orbit.Text3, modifier = Modifier.size(22.dp)) }
                Spacer(Modifier.height(3.dp))
                Text(t.label, fontSize = 11.sp, color = if (on) Orbit.Text1 else Orbit.Text3, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerSheet(
    actions: OrbitActions,
    title: String,
    source: () -> List<AppEntry>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val apps = remember { source() }
    var query by remember { mutableStateOf("") }
    val shown = remember(query, apps) { filterApps(apps, query) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Orbit.Surface1) {
        Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = Orbit.Text1, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Search apps", color = Orbit.Text3) },
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedTextColor = Orbit.Text1, unfocusedTextColor = Orbit.Text1),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                // Enter opens the only/first match: type a few letters and go.
                shown.firstOrNull()?.let { onPick(it.component); onDismiss() }
            }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (shown.isEmpty()) Text("No apps match \"$query\"", color = Orbit.Text3, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        LazyColumn(Modifier.fillMaxWidth().height(520.dp)) {
            items(shown, key = { it.component }) { app ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(app.component); onDismiss() }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(app.packageName, 40.dp)
                    Spacer(Modifier.size(14.dp))
                    Text(app.label, color = Orbit.Text1, fontSize = 15.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeysMenu(actions: OrbitActions, keyboardOn: Boolean, onPhoneTyping: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Orbit.Surface1) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Keyboard", style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = Orbit.Text1)
            Text("OrbitWalker's keyboard appears under the focused screen in the glasses. Click its keys with the pointer; it types anywhere, passwords included. A Bluetooth keyboard works too.", color = Orbit.Text2, fontSize = 13.sp, lineHeight = 18.sp)
            GradientButton(if (keyboardOn) "Hide the keyboard in the glasses" else "Show the keyboard in the glasses", Modifier.fillMaxWidth()) {
                actions.setKeyboardInGlasses(!keyboardOn); onDismiss()
            }
            GhostButton("Type on the phone instead", Modifier.fillMaxWidth(), OrbitIcons.Enter) { onPhoneTyping() }
            Text("Tip: with Gboard as your phone keyboard, Android's own keyboard can also appear inside the screens (Samsung Keyboard can't).", color = Orbit.Text3, fontSize = 12.sp, lineHeight = 16.sp)
            GhostButton("Close Android's keyboard in the screen (Back)", Modifier.fillMaxWidth(), OrbitIcons.Keyboard) { actions.hideKeyboard(); onDismiss() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyboardSheet(actions: OrbitActions, onDismiss: () -> Unit) {
    // Live typing: every change is sent to the focused screen straight away (no Send step).
    var text by remember { mutableStateOf("") }
    var hidden by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Orbit.Surface1) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(150) // the sheet's window attaches a frame later
            runCatching { focus.requestFocus() }
        }
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text("Type on the phone", style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = Orbit.Text1)
            Text("Each key appears in the focused screen as you type.", color = Orbit.Text2, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { new ->
                    val common = text.commonPrefixWith(new).length
                    repeat(text.length - common) { actions.backspace() }
                    if (new.length > common) actions.typeText(new.substring(common))
                    // Keep only a short tail so the diff stays small and nothing long is held.
                    text = if (new.length > 64) new.takeLast(32) else new
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = { Text("Start typing…", color = Orbit.Text3) },
                singleLine = true,
                visualTransformation = if (hidden) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                // No autocorrect or suggestions: what you type is exactly what's sent (and passwords aren't learned).
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { actions.enter(); text = "" }),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Orbit.Accent, unfocusedBorderColor = Orbit.Line, focusedTextColor = Orbit.Text1, unfocusedTextColor = Orbit.Text1, cursorColor = Orbit.Accent),
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(if (hidden) "Show" else "Hide", Modifier.weight(1f)) { hidden = !hidden }
                GhostButton("Delete", Modifier.weight(1f), OrbitIcons.Backspace) { actions.backspace(); text = text.dropLast(1) }
                GradientButton("Enter", Modifier.weight(1f)) { actions.enter(); text = "" }
            }
        }
    }
}

/** Shows a short-lived message line under a screen's header. */
@Composable
fun StatusLine(text: String) {
    if (text.isBlank()) return
    val error = text.contains("fail", true) || text.contains("couldn't", true) || text.contains("didn't", true) || text.contains("stopped", true)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background((if (error) Orbit.Warn else Orbit.Accent).copy(alpha = 0.08f))
            .border(1.dp, (if (error) Orbit.Warn else Orbit.Accent).copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) { Text(text, color = if (error) Orbit.Warn else Orbit.Text2, fontSize = 12.sp, lineHeight = 16.sp) }
}

/** Ticks a counter while [active], for screens that poll. */
@Composable
fun rememberTicker(periodMs: Long): Int {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(periodMs) {
        while (true) { kotlinx.coroutines.delay(periodMs); tick++ }
    }
    return tick
}
