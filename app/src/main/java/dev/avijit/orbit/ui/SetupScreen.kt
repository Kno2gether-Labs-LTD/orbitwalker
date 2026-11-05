package dev.avijit.orbit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.avijit.orbit.helper.HelperSetup.Phase
import dev.avijit.orbit.shell.HelperState
import dev.avijit.orbit.ui.theme.Orbit

/**
 * One-time setup of OrbitWalker's helper, no other apps needed: Developer options →
 * Wireless debugging → pair with the code → the helper starts and runs until the phone restarts.
 */
@Composable
fun SetupScreen(state: UiState, actions: OrbitActions, onClose: () -> Unit) {
    // Leaving Setup stops looking for the pairing dialog (and removes its notification).
    val close = {
        if (state.setupPhase == Phase.FINDING_DIALOG || state.setupPhase == Phase.WAITING_FOR_CODE || state.setupPhase == Phase.FAILED) actions.cancelPairing()
        onClose()
    }
    Column(
        Modifier.fillMaxSize().background(Orbit.Bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("SET UP APP SCREENS", color = Orbit.Accent, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
                Wordmark(26)
            }
            Text("Done", color = Orbit.Accent, fontSize = 15.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = close).padding(10.dp))
        }

        if (state.helper == HelperState.READY) {
            OrbitCard(Modifier.fillMaxWidth(), highlight = Orbit.Ok.copy(alpha = 0.4f)) {
                Column {
                    Text("App screens are ready", color = Orbit.Ok, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "The helper keeps running until you restart the phone. You can turn Auto Blocker back on; leave Wireless debugging on. " +
                            "After a restart: turn on Wireless debugging and tap Start on Home.",
                        color = Orbit.Text2, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        } else {
            Text(
                "OrbitWalker starts its own helper through Android's Wireless debugging, once per phone restart. " +
                    "It takes about a minute and needs Wi-Fi. No other apps needed.",
                color = Orbit.Text2, fontSize = 13.sp, lineHeight = 18.sp,
            )
        }

        // Samsung only: Auto Blocker blocks Wireless debugging. Its "off" lasts a while; that's enough.
        val n0 = if (state.isSamsung) 1 else 0
        if (state.isSamsung) Step(1, "Turn off Auto Blocker for now", done = false) {
            Hint("Samsung's Auto Blocker stops Wireless debugging. Turn it off; it switches itself back on later, and that's fine: once OrbitWalker says it's ready, the helper keeps running.")
            GradientButton("Open Auto Blocker", Modifier.fillMaxWidth()) { actions.openAutoBlocker() }
            Hint("If Security and privacy opens instead, tap Auto Blocker there.")
        }

        Step(n0 + 1, "Turn on Developer options", done = state.devOptionsOn) {
            if (state.devOptionsOn) Hint("On.")
            else {
                Hint("Settings → About phone → Software information → tap Build number 7 times.")
                GhostButton("Open About phone", Modifier.fillMaxWidth()) { actions.openAboutPhone() }
            }
        }

        Step(n0 + 2, "Pair with Wireless debugging (first time only)", done = state.helper == HelperState.STOPPED || state.helper == HelperState.READY) {
            Hint("Connect to Wi-Fi first (not this phone's own hotspot).")
            Hint("Tap Pair below. In Developer options turn on Wireless debugging, tap it, then tap \"Pair device with pairing code\".")
            Hint("Type the 6-digit code into OrbitWalker's notification (pull down the notification shade). Keep the code on screen while you type.")
            val watching = state.setupPhase == Phase.FINDING_DIALOG || state.setupPhase == Phase.WAITING_FOR_CODE
            when {
                // Settings won't open Wireless debugging until Developer options are on.
                !state.devOptionsOn -> GhostButton("Turn on Developer options first (step ${n0 + 1})", Modifier.fillMaxWidth()) { actions.openAboutPhone() }
                else -> GradientButton(
                    if (watching) "Open Wireless debugging" else "Pair",
                    Modifier.fillMaxWidth(),
                    enabled = state.setupPhase != Phase.PAIRING && state.setupPhase != Phase.STARTING,
                ) { if (watching) actions.openWirelessDebugging() else actions.beginPairing() }
            }
            if (state.setupPhase == Phase.WAITING_FOR_CODE || state.setupPhase == Phase.FAILED) CodeField(actions)
        }

        Step(n0 + 3, "Start", done = state.helper == HelperState.READY) {
            Hint("Happens automatically after pairing. After a phone restart: turn on Wireless debugging, then tap Start.")
            if (state.helper != HelperState.READY) {
                GradientButton("Start", Modifier.fillMaxWidth(), enabled = state.helper == HelperState.STOPPED) { actions.startHelper() }
            }
        }

        SetupStatus(state)

        Hint("Keep Wireless debugging (or USB debugging) on while you use OrbitWalker: turning both off stops the helper, just like Shizuku. Auto Blocker can go back on.")
        Spacer(Modifier.size(12.dp))
    }
}

@Composable
fun SetupStatus(state: UiState) {
    val text = when (state.setupPhase) {
        Phase.FINDING_DIALOG -> "Waiting for the pairing code screen in Wireless debugging…"
        Phase.WAITING_FOR_CODE -> "Pairing code screen found. Enter the code in the notification (or below)."
        Phase.PAIRING -> "Pairing…"
        Phase.STARTING -> "Starting the helper…"
        Phase.FAILED -> state.setupMessage ?: "Something went wrong. Share logs."
        Phase.START_FAILED -> (state.setupMessage ?: "The helper didn't start") + ". Tap Start to try again."
        Phase.DONE, Phase.IDLE -> null
    } ?: return
    StatusLine(text)
}

@Composable
private fun CodeField(actions: OrbitActions) {
    var code by remember { mutableStateOf("") }
    Hint("Using split screen? You can type the code here instead:")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = code,
            onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
            singleLine = true,
            placeholder = { Text("6-digit code", color = Orbit.Text3) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Orbit.Text1, unfocusedTextColor = Orbit.Text1),
            modifier = Modifier.weight(1f),
        )
        GradientButton("Pair", enabled = code.length == 6) { actions.submitPairingCode(code); code = "" }
    }
}

@Composable
private fun Step(n: Int, title: String, done: Boolean, content: @Composable () -> Unit) {
    OrbitCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(24.dp).clip(CircleShape).background(if (done) Orbit.Ok.copy(alpha = 0.2f) else Orbit.Accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (done) "✓" else "$n", color = if (done) Orbit.Ok else Orbit.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.size(10.dp))
                Text(title, color = Orbit.Text1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Orbit.Text2, fontSize = 13.sp, lineHeight = 18.sp)
}
