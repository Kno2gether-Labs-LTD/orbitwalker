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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.avijit.orbit.ui.theme.Orbit

const val VIDEO_GUIDES_URL = "https://www.youtube.com/@Kno2gether"

/** "How to use OrbitWalker": setup and everyday tips, reachable from Home, the setup card and Settings. */
@Composable
fun HelpScreen(onOpenVideos: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Orbit.Bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("HOW TO USE", color = Orbit.Accent, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
                Wordmark(26)
            }
            Text("Done", color = Orbit.Accent, fontSize = 15.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClose).padding(10.dp))
        }

        // Video guides
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFF3A0F18), Orbit.Surface1)))
                .border(1.dp, Orbit.Line, RoundedCornerShape(20.dp)).clickable(onClick = onOpenVideos).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFFF0033)), contentAlignment = Alignment.Center) {
                Text("▶", color = Color.White, fontSize = 18.sp)
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Watch the video guides", color = Orbit.Text1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("youtube.com/@Kno2gether: setup, multi-screen tips and more", color = Orbit.Text2, fontSize = 12.sp)
            }
        }

        HelpSection("Get started", listOf(
            "Turn off Samsung DeX (Settings → Connected devices → Samsung DeX).",
            "Plug your VITURE Pro 2 into the phone and open OrbitWalker.",
            "Tap OK when Android asks to let OrbitWalker use the glasses.",
            "Hold your head still for 2 seconds. Tracking calibrates and screens pin in place.",
        ))
        HelpSection("Set up app screens (once): no other apps needed", listOf(
            "Samsung: turn off Auto Blocker for now (Settings → Security and privacy → Auto Blocker).",
            "Turn on Developer options: Settings → About phone → Software information → tap Build number 7 times.",
            "Connect the phone to Wi-Fi (not your own hotspot).",
            "Home → Set up → Pair. Turn on Wireless debugging, tap \"Pair device with pairing code\", and type the code into OrbitWalker's notification.",
            "OrbitWalker starts its helper. It runs until you restart the phone. You can turn Auto Blocker back on.",
            "After a phone restart: turn on Wireless debugging (and Auto Blocker off), then Home → Start. No new pairing needed.",
        ))
        HelpSection("Your multi-screen desk", listOf(
            "Add app opens an app on its own screen. Up to 10, side by side, then a second row above.",
            "Trackpad: one finger moves the pointer, tap clicks, two fingers scroll. ⤢ makes the whole phone a trackpad.",
            "Tap a screen to focus it (blue outline). Switch jumps to the next screen.",
            "Layout: Distance, Size, Gap and Height move everything. Move drags one screen; pinch resizes it.",
            "Recenter brings everything back in front of you.",
            "With a mouse: drag the bar above the focused screen to move it; the wheel over the bar pushes it back or brings it closer; its buttons resize, reset or close it.",
            "Under the centre screen: clock, battery and your recent apps. Click an app to open it.",
            "Sharpest text: Layout → Pixel-perfect size. Settings → Screen sharpness → Ultra helps when screens are large or close.",
        ))
        HelpSection("Typing, keyboard and mouse", listOf(
            "Trackpad → Keys → Show the keyboard in the glasses: OrbitWalker's keyboard appears under the focused screen. Click keys with the pointer; passwords work.",
            "Want Android's own keyboard inside the screens instead? Samsung Keyboard doesn't support that; install Gboard and make it your default keyboard.",
            "A Bluetooth keyboard types into the focused screen while OrbitWalker is open on the phone.",
            "A Bluetooth mouse drives the pointer: left click clicks, right click is Back, the wheel scrolls. Esc releases it.",
            "Lost the pointer? An arrow at the edge of the glasses points to it. Ctrl+Alt+C brings it to where you're looking; Ctrl+Alt+R recenters the view and the pointer. Ctrl+Esc gives the keyboard and mouse back to the phone.",
        ))
        HelpSection("Orbit Browser", listOf(
            "Home → Orbit Browser, or pick it in Add app: a full browser on its own screen. Open as many as you like.",
            "Drag a tab down out of the tab strip (or use the ⧉ button) to give that page a screen of its own; it keeps playing as it moves.",
            "Ctrl+T new tab, Ctrl+W close tab, Ctrl+L address bar, Ctrl+Tab next tab, Alt+← / Alt+→ back and forward. Menu ⋮ has Desktop site and the search engine.",
            "Click a planet in the Orbit sky to read about that Orbit app.",
        ))
        HelpSection("No helper? Mirror mode", listOf(
            "Settings → Mirror phone screen pins your phone's own screen in the glasses. Use the phone as normal.",
        ))
        HelpSection("Netflix and other protected video", listOf(
            "Home → Cinema mode → pick the app. It plays directly on the glasses so the video isn't blocked.",
            "Cinema isn't pinned. Exit Cinema to get your desk back.",
        ))
        HelpSection("If something's wrong", listOf(
            "Settings → Share logs and send them with your report.",
            "No picture in the glasses? Make sure DeX is off and the cable supports video.",
        ))
        Spacer(Modifier.size(12.dp))
    }
}

@Composable
internal fun HelpSection(title: String, steps: List<String>) {
    OrbitCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = Orbit.Text1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            steps.forEachIndexed { i, s ->
                Row {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(Orbit.Accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = Orbit.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.size(10.dp))
                    Text(s, color = Orbit.Text2, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** "OrbitWalker" wordmark: bold "Orbit" plus gradient "Walker". */
@Composable
fun Wordmark(sizeSp: Int) {
    Row {
        Text("Orbit", color = Orbit.Text1, fontSize = sizeSp.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
        Text(
            "Walker", fontSize = sizeSp.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.4).sp,
            style = androidx.compose.ui.text.TextStyle(brush = Orbit.Gradient),
        )
    }
}
