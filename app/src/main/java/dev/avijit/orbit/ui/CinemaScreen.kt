package dev.avijit.orbit.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.avijit.orbit.ui.theme.Orbit

/** Phone controls while an app plays directly on the glasses (Cinema mode). */
@Composable
fun CinemaScreen(state: UiState, actions: OrbitActions) {
    Column(
        Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("CINEMA", color = Orbit.Accent, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
                Text(state.cinemaApp ?: "", color = Orbit.Text1, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            StatusPill(if (state.cinemaPlaying == true) "Playing" else "On glasses", Orbit.Ok)
        }
        Text(
            "Playing directly on the glasses so protected video works. The picture is fixed in front of you (not pinned) until you exit.",
            color = Orbit.Text2, fontSize = 13.sp, lineHeight = 18.sp,
        )

        Spacer(Modifier.weight(1f))
        if (state.mediaAccess) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                RoundControl("−10s", Modifier.weight(1f)) { actions.cinemaSeek(-10_000) }
                Box(
                    Modifier.size(104.dp).clip(CircleShape).background(Orbit.Gradient).clickable { actions.cinemaPlayPause() },
                    contentAlignment = Alignment.Center,
                ) { Text(if (state.cinemaPlaying == true) "❚❚" else "▶", color = Color(0xFF05121A), fontSize = 30.sp, fontWeight = FontWeight.Bold) }
                RoundControl("+10s", Modifier.weight(1f)) { actions.cinemaSeek(10_000) }
            }
        } else {
            OrbitCard(Modifier.fillMaxWidth(), highlight = Orbit.Warn.copy(alpha = 0.35f)) {
                Column {
                    Text("Want play/pause and skip here?", color = Orbit.Text1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Allow OrbitWalker notification access. It's used to control playback (and, later, to show notifications in the glasses).",
                        color = Orbit.Text2, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                    GradientButton("Allow access", Modifier.fillMaxWidth()) { actions.openNotificationAccess() }
                }
            }
        }
        if (state.cinemaKeys) {
            // Navigate the app on the glasses (profile pickers, menus) like a TV remote.
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundControl("▲", Modifier.size(64.dp)) { actions.cinemaNav(android.view.KeyEvent.KEYCODE_DPAD_UP) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundControl("◀", Modifier.size(64.dp)) { actions.cinemaNav(android.view.KeyEvent.KEYCODE_DPAD_LEFT) }
                    RoundControl("OK", Modifier.size(64.dp)) { actions.cinemaNav(android.view.KeyEvent.KEYCODE_DPAD_CENTER) }
                    RoundControl("▶", Modifier.size(64.dp)) { actions.cinemaNav(android.view.KeyEvent.KEYCODE_DPAD_RIGHT) }
                }
                RoundControl("▼", Modifier.size(64.dp)) { actions.cinemaNav(android.view.KeyEvent.KEYCODE_DPAD_DOWN) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("Back") { actions.cinemaNav(android.view.KeyEvent.KEYCODE_BACK) }
                    GhostButton("Tap centre") { actions.cinemaTapCentre() }
                }
            }
        } else {
            Text("Start app screens (Home → Start) to navigate the app from here (arrows, OK, Back). A Bluetooth keyboard also works then.", color = Orbit.Text3, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GhostButton("Volume −", Modifier.weight(1f)) { actions.cinemaVolume(false) }
            GhostButton("Volume +", Modifier.weight(1f)) { actions.cinemaVolume(true) }
        }
        Spacer(Modifier.weight(1f))
        GradientButton("Exit Cinema · back to my screens", Modifier.fillMaxWidth()) { actions.exitCinema() }
    }
}

@Composable
private fun RoundControl(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(64.dp).clip(RoundedCornerShape(32.dp)).background(Orbit.Surface2)
            .border(1.dp, Orbit.Line, RoundedCornerShape(32.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Orbit.Text1, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) }
}

/** Home entry point for Cinema mode. */
@Composable
fun CinemaCard(onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.horizontalGradient(listOf(Color(0xFF1A1030), Orbit.Surface1)))
            .border(1.dp, Orbit.Line, shape).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🎬", fontSize = 26.sp)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Cinema mode", color = Orbit.Text1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("For Netflix, Prime Video, Disney+ and other protected video.", color = Orbit.Text2, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}
