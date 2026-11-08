package dev.avijit.orbit.record

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.avijit.orbit.BuildConfig
import dev.avijit.orbit.ui.OrbitSwitch
import dev.avijit.orbit.ui.theme.Orbit
import dev.avijit.orbit.ui.theme.OrbitTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where things sit on the camera studio screen, shared with the renderer that draws the picture. */
object StudioLayout {
    const val COMPONENT = BuildConfig.APPLICATION_ID + "/dev.avijit.orbit.record.StudioActivity"
    const val LABEL = "Camera studio"

    /** Glasses screens Orbit created for the studio. It's exported (the helper starts it), so it must check. */
    private val displays = java.util.Collections.synchronizedSet(HashSet<Int>())
    fun expect(displayId: Int) { displays += displayId }
    fun allowed(displayId: Int?) = displayId != null && displayId in displays

    /** The camera picture area, as fractions of the screen: left, top, right, bottom. */
    val PICTURE = floatArrayOf(0.015f, 0.03f, 0.6f, 0.97f)
}

/**
 * The camera studio: a screen in the glasses with the live mixed reality picture (drawn onto it by
 * the renderer) and every recording control beside it. Never appears in recordings.
 */
class StudioActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only on a screen Orbit opened for it: anything else launching it must not turn the camera on.
        if (!StudioLayout.allowed(display?.displayId)) { finish(); return }
        Recorder.init(this)
        window.statusBarColor = android.graphics.Color.BLACK
        setContent { OrbitTheme { Studio(onClose = ::finish) } }
    }

    override fun onStart() { super.onStart(); if (!isFinishing) Recorder.studioShowing(true) }
    override fun onStop() { super.onStop(); if (StudioLayout.allowed(display?.displayId)) Recorder.studioShowing(false) }
}

@Composable
private fun Studio(onClose: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val v = Recorder.version.intValue // redraw on every recorder change
    val p = StudioLayout.PICTURE
    BoxWithConstraints(Modifier.fillMaxSize().background(Orbit.Bg)) {
        @Suppress("UNUSED_VARIABLE") val v2 = Recorder.version.intValue // its own scope: read here too
        // Picture area: the renderer draws the live camera picture over this exact rectangle.
        Box(
            Modifier.offset(maxWidth * p[0], maxHeight * p[1]).size(maxWidth * (p[2] - p[0]), maxHeight * (p[3] - p[1]))
                .clip(RoundedCornerShape(8.dp)).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (Recorder.monitorOn) "Starting the camera…" else Recorder.lastError ?: "Camera off",
                color = Orbit.Text3, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp),
            )
        }
        Column(
            Modifier.offset(maxWidth * (p[2] + 0.015f)).width(maxWidth * (1f - p[2] - 0.03f)).fillMaxHeight()
                .verticalScroll(rememberScrollState()).padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { Controls(onClose) }
    }
}

@Composable
private fun Controls(onClose: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val v = Recorder.version.intValue // recording state, errors, camera on/off
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Camera studio", color = Orbit.Text1, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Pill("Close", Orbit.Surface2, Orbit.Text1, onClick = onClose)
    }
    RecordButton()
    Recorder.lastError?.takeIf { !Recorder.recording }?.let { Text(it, color = Color(0xFFFF8A95), fontSize = 12.sp) }

    Label("Phone stands")
    Chips(PhoneStand.entries, Recorder.phoneStand, { it.label }, enabled = !Recorder.recording) { Recorder.phoneStand = it }
    Label("Screens in the video")
    Chips(ScreenStaging.entries, Recorder.staging, { it.label }) { Recorder.staging = it }
    if (Recorder.staging == ScreenStaging.FRAMED) {
        Chips(HeroSide.entries, Recorder.heroSide, { it.label }) { Recorder.heroSide = it }
        PadRow("Screen size", "Smaller" to { Recorder.heroFill -= 0.02f }, "Bigger" to { Recorder.heroFill += 0.02f })
        Text("The screen you're using stands behind you, filling the shot. Click another screen and it swaps in.", color = Orbit.Text3, fontSize = 11.sp)
    }
    Label("Behind you")
    Chips(MixedBackground.entries, Recorder.background, { it.label }) { Recorder.background = it }
    Label("Camera")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Chips(listOf(false, true), Recorder.frontCamera, { if (it) "Front" else "Back" }, enabled = !Recorder.recording) { Recorder.frontCamera = it }
        Chips(listOf(true, false), Recorder.wideLens, { if (it) "Ultra-wide" else "Normal (sharpest)" }, enabled = !Recorder.recording) { Recorder.wideLens = it }
    }

    Label("Where the phone is")
    Chips(CameraSpot.entries, Recorder.spot, { it.label }) { Recorder.spot = it }
    Text(CameraRig.placement(Recorder.rig), color = Orbit.Text2, fontSize = 12.sp)
    PadRow("Around you", "◀" to { Recorder.nudge(CalKey.LEFT) }, "▶" to { Recorder.nudge(CalKey.RIGHT) })
    PadRow("Height", "▼" to { Recorder.nudge(CalKey.DOWN) }, "▲" to { Recorder.nudge(CalKey.UP) })
    PadRow("Distance", "Closer" to { Recorder.nudge(CalKey.FORWARD) }, "Further" to { Recorder.nudge(CalKey.BACK) })
    PadRow("Aim", "◀" to { Recorder.nudge(CalKey.LEFT, shift = true) }, "▶" to { Recorder.nudge(CalKey.RIGHT, shift = true) },
        "▼" to { Recorder.nudge(CalKey.DOWN, shift = true) }, "▲" to { Recorder.nudge(CalKey.UP, shift = true) })
    PadRow("Zoom", "−" to { Recorder.nudge(CalKey.ZOOM_OUT) }, "+" to { Recorder.nudge(CalKey.ZOOM_IN) }, "Reset spot" to { Recorder.nudge(CalKey.RESET) })
    Text("Hold a button to keep moving. Line your screens up with where they float around you.", color = Orbit.Text3, fontSize = 11.sp)

    Label("Record")
    Toggle("Glasses view", Recorder.glassesView, !Recorder.recording) { Recorder.glassesView = it }
    Toggle("Mixed reality", Recorder.mixedReality, !Recorder.recording) { Recorder.mixedReality = it }
    Toggle("Clean screen (for editing)", Recorder.cleanScreen, !Recorder.recording) { Recorder.cleanScreen = it }
    Toggle("Microphone", Recorder.microphone, !Recorder.recording) { Recorder.microphone = it }
    Toggle("Face bubble in the glasses view", Recorder.faceBubble) { Recorder.faceBubble = it }
    Toggle("You in front of the screens", Recorder.personInFront, !Recorder.recording) { Recorder.personInFront = it }
    Text("This screen never appears in your recordings.", color = Orbit.Text3, fontSize = 11.sp)
}

@Composable
private fun RecordButton() {
    val now = remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(Recorder.recording) { while (Recorder.recording) { now.longValue = SystemClock.uptimeMillis(); delay(500) } }
    val rec = Recorder.recording
    Box(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp))
            .background(if (rec) Color(0xFFFF4D5E) else Orbit.Accent).clickable { Recorder.toggle() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (rec) "■  Stop and save · ${RecordingNames.elapsed(now.longValue - Recorder.startedAt)}" else "●  Start recording",
            color = if (rec) Color.White else Orbit.Bg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun Label(text: String) =
    Text(text.uppercase(), color = Orbit.Accent, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))

@Composable
private fun <T> Chips(items: List<T>, selected: T, label: (T) -> String, enabled: Boolean = true, onPick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            val on = item == selected
            Pill(label(item), if (on) Orbit.Accent else Orbit.Surface2, if (on) Orbit.Bg else if (enabled) Orbit.Text1 else Orbit.Text3) {
                if (enabled && !on) onPick(item)
            }
        }
    }
}

@Composable
private fun Pill(text: String, bg: Color, fg: Color, onClick: () -> Unit) = Text(
    text, color = fg, fontSize = 13.sp,
    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
)

/** A row of buttons that repeat while held. */
@Composable
private fun PadRow(title: String, vararg buttons: Pair<String, () -> Unit>) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Orbit.Text2, fontSize = 12.sp, modifier = Modifier.width(76.dp))
        buttons.forEach { (label, action) -> RepeatButton(label, action) }
    }
}

@Composable
private fun RepeatButton(label: String, action: () -> Unit) {
    val scope = rememberCoroutineScope()
    Text(
        label, color = Orbit.Text1, fontSize = 13.sp, textAlign = TextAlign.Center,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Orbit.Surface2)
            .pointerInput(action) {
                detectTapGestures(onPress = {
                    val job = scope.launch {
                        action()
                        delay(350)
                        while (true) { action(); delay(100) }
                    }
                    tryAwaitRelease()
                    job.cancel()
                })
            }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun Toggle(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = if (enabled) Orbit.Text1 else Orbit.Text3, fontSize = 13.sp, modifier = Modifier.weight(1f))
        OrbitSwitch(checked) { if (enabled) onChange(it) }
    }
}
