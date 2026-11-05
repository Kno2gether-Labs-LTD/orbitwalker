package dev.avijit.orbit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.avijit.orbit.shell.HelperState
import dev.avijit.orbit.ui.theme.Orbit

@Composable
fun SettingsScreen(state: UiState, actions: OrbitActions, onHelp: () -> Unit, onSetup: () -> Unit) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    var confirmMirror by remember { mutableStateOf(false) }
    if (confirmMirror) MirrorConfirm(state, onConfirm = { confirmMirror = false; actions.setMirror(true) }, onDismiss = { confirmMirror = false })
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, color = Orbit.Text1, modifier = Modifier.padding(bottom = 4.dp))

        SectionTitle("Screens")
        SettingRow(
            "Mirror phone screen",
            "Works without the helper: your phone screen as one pinned screen. Closes app screens.",
            trailing = {
                OrbitSwitch(state.mirrorOn) { on ->
                    if (on && state.screens.isNotEmpty()) confirmMirror = true else actions.setMirror(on)
                }
            },
        )
        val helperText = when (state.helper) {
            HelperState.READY -> "Running${if (state.helperVia == "Shizuku") " (via Shizuku)" else ""} · tap for setup details"
            HelperState.STARTING -> "Starting…"
            HelperState.STOPPED -> "Stopped (phone restarted?) · turn on Wireless debugging and tap to start"
            HelperState.NEEDS_SETUP -> "Not set up · tap to set up (once)"
        }
        SettingRow(
            "App screens helper", helperText,
            subtitleColor = if (state.helper == HelperState.READY) Orbit.Ok else Orbit.Warn,
            trailing = { Icon(OrbitIcons.Chevron, null, tint = Orbit.Text3, modifier = Modifier.size(18.dp)) },
            onClick = { if (state.helper == HelperState.STOPPED) actions.startHelper() else onSetup() },
        )

        SectionTitle("Background")
        OrbitCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    dev.avijit.orbit.render.BackgroundMode.entries.forEach { m ->
                        val on = m == state.background
                        Text(
                            m.label, color = if (on) Orbit.Text1 else Orbit.Text2, fontSize = 13.sp,
                            modifier = Modifier.weight(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                .background(if (on) Orbit.Accent.copy(alpha = 0.16f) else Orbit.Surface2)
                                .clickable { actions.setBackground(m, state.dimWithApps, state.dimNoApps, state.showMarkers) }
                                .padding(vertical = 12.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
        if (state.background == dev.avijit.orbit.render.BackgroundMode.ORBIT) {
            SettingRow("Orbit apps in the sky", "Each planet is an app from the Orbit family, with its name.", trailing = { OrbitSwitch(state.orbitApps) { actions.setOrbitApps(it) } })
            SettingRow("Still sky", "Planets drift very slowly (under 0.2° a second). Turn this on to keep them completely still.", trailing = { OrbitSwitch(state.orbitStill) { actions.setOrbitStill(it) } })
        }
        if (state.background != dev.avijit.orbit.render.BackgroundMode.BLACK) {
            LabeledSlider("Brightness with apps open", state.dimWithApps * 100, 0f..100f, { "${it.toInt()}%" }) {
                actions.setBackground(state.background, it / 100, state.dimNoApps, state.showMarkers)
            }
            LabeledSlider("Brightness with no apps", state.dimNoApps * 100, 0f..100f, { "${it.toInt()}%" }) {
                actions.setBackground(state.background, state.dimWithApps, it / 100, state.showMarkers)
            }
        }

        SectionTitle("Screen sharpness")
        OrbitCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Resolution of each app screen. Sharper text uses more battery and graphics power. Applies to screens you open next.", color = Orbit.Text2, fontSize = 12.sp, lineHeight = 16.sp)
                dev.avijit.orbit.ScreenQuality.entries.forEach { q ->
                    val on = q == state.quality
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .background(if (on) Orbit.Accent.copy(alpha = 0.14f) else Orbit.Surface2)
                            .clickable { actions.setQuality(q) }.padding(12.dp),
                    ) {
                        Text(q.label, color = if (on) Orbit.Text1 else Orbit.Text2, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        if (on) Text("✓", color = Orbit.Accent, fontSize = 14.sp)
                    }
                }
            }
        }

        SectionTitle("Notifications in the glasses")
        if (!state.notificationAccess) {
            SettingRow("Allow notification access", "Needed to show phone notifications in the glasses (and for Cinema play/pause).", trailing = {
                GhostButton("Allow") { actions.openNotificationAccess() }
            })
        } else {
            SettingRow("Show notifications", "Pop up briefly at the bottom of your view.", trailing = {
                OrbitSwitch(state.notificationsOn) { actions.setNotifications(it, state.notificationsHideText) }
            })
            SettingRow("Hide message contents", "Show only which app notified you, for privacy.", trailing = {
                OrbitSwitch(state.notificationsHideText) { actions.setNotifications(state.notificationsOn, it) }
            })
        }

        LabeledSlider("Text sharpening", state.sharpen / 0.45f * 100f, 0f..100f, { if (it < 1f) "Off" else "${it.toInt()}%" }) {
            actions.setSharpen(it / 100f * 0.45f)
        }

        SectionTitle("Pointer & keyboard")
        SettingRow("Show pointer", "The arrow you move with the trackpad or a Bluetooth mouse.", trailing = { OrbitSwitch(state.pointerVisible) { actions.setPointerVisible(it) } })
        SettingRow("Title bar on the focused screen", "Drag it with the mouse to move the screen; buttons resize, push back, bring closer, reset or close it.", trailing = { OrbitSwitch(state.windowBarEnabled) { actions.setWindowBarEnabled(it) } })

        SectionTitle("Dock in the glasses")
        SettingRow("Dock under the centre screen", "Clock, date, phone battery and your recent apps (click one to open it).", trailing = { OrbitSwitch(state.dockEnabled) { actions.setDockEnabled(it) } })
        SettingRow("Clock, date & battery", "The phone's battery matters: the glasses charge from it.", trailing = { OrbitSwitch(state.widgetsEnabled) { actions.setWidgetsEnabled(it) } })
        SettingRow("Next calendar event", "Shows your next event in the dock (asks for calendar access).", trailing = { OrbitSwitch(state.calendarEnabled) { actions.setCalendarEnabled(it) } })

        SectionTitle("Eye care")
        Text("A short reminder in the glasses to look far away for 20 seconds. After 90 minutes it suggests taking them off for a while. Never during a film: it waits until Cinema or a playing video stops.", color = Orbit.Text2, fontSize = 12.sp, lineHeight = 16.sp)
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Off", 20 to "20 min", 30 to "30 min", 45 to "45 min", 60 to "60 min").forEach { (min, label) ->
                val on = state.eyeBreakMin == min
                Text(
                    label, color = if (on) Orbit.Text1 else Orbit.Text2, fontSize = 12.sp,
                    modifier = Modifier.weight(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .background(if (on) Orbit.Accent.copy(alpha = 0.16f) else Orbit.Surface2)
                        .clickable { actions.setEyeBreakMinutes(min) }
                        .padding(vertical = 12.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        SettingRow(
            "Typing",
            "Tap a text box with the pointer and your phone keyboard appears inside that screen; click its keys or use a Bluetooth keyboard. Passwords work too.",
        )

        SectionTitle("Tracking")
        SettingRow("Use compass", "Less sideways drift in long sessions; may wobble near metal or magnets.", trailing = { OrbitSwitch(state.compass) { actions.setCompass(it) } })
        SettingRow("Glasses", state.glassesTitle, trailing = { GhostButton("Retry") { actions.retryGlasses() } })

        SectionTitle("Help & feedback")
        SettingRow("How to use OrbitWalker", "Setup, multi-screen tips and video guides.", trailing = {
            Icon(OrbitIcons.Chevron, null, tint = Orbit.Text3, modifier = Modifier.size(18.dp))
        }, onClick = onHelp)
        SettingRow("Share logs", "Send diagnostics with your feedback or bug report.", trailing = {
            Icon(OrbitIcons.Share, null, tint = Orbit.Text2, modifier = Modifier.size(20.dp))
        }, onClick = { actions.shareLogs() })
        SettingRow("Advanced", if (advanced) "Hide diagnostics" else "Diagnostics and axis fixes", trailing = {
            Icon(OrbitIcons.Chevron, null, tint = Orbit.Text3, modifier = Modifier.size(18.dp))
        }, onClick = { advanced = !advanced })
        if (advanced) {
            SettingRow("Alignment markers", "Show the head-tracking test grid and ring.", trailing = {
                OrbitSwitch(state.showMarkers) { actions.setBackground(state.background, state.dimWithApps, state.dimNoApps, it) }
            })
            SettingRow("Invert left/right", null, trailing = { OrbitSwitch(state.invertYaw) { actions.setInvert(it, state.invertPitch, state.invertRoll) } })
            SettingRow("Invert up/down", null, trailing = { OrbitSwitch(state.invertPitch) { actions.setInvert(state.invertYaw, it, state.invertRoll) } })
            SettingRow("Invert tilt", null, trailing = { OrbitSwitch(state.invertRoll) { actions.setInvert(state.invertYaw, state.invertPitch, it) } })
            OrbitCard(Modifier.fillMaxWidth()) {
                Text(state.diagnostics, color = Orbit.Text2, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Text(
            "OrbitWalker ${state.version} · open source · not affiliated with VITURE",
            color = Orbit.Text3, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
        )
    }
}

@Composable
fun MirrorConfirm(state: UiState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Orbit.Surface2,
        title = { Text("Switch to Mirror mode?", color = Orbit.Text1) },
        text = {
            Text(
                "This closes your ${state.screens.size} app screen(s). You can bring them back later with \"Reopen last screens\".",
                color = Orbit.Text2,
            )
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onConfirm) { Text("Mirror", color = Orbit.Accent) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel", color = Orbit.Text2) } },
    )
}
