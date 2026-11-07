package dev.avijit.orbit.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.avijit.orbit.record.CameraRig
import dev.avijit.orbit.record.CameraSpot
import dev.avijit.orbit.record.Recorder
import dev.avijit.orbit.record.RecordingNames
import dev.avijit.orbit.ui.theme.Orbit

/** Recording: the glasses view and a mixed reality view (you, filmed by the phone, among your screens). */
@Composable
fun RecordScreen(onClose: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val v = Recorder.version.intValue // redraw on every recorder change
    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { Recorder.version.intValue++ }
    fun withPermissions(then: () -> Unit) {
        val missing = buildList {
            if (!Recorder.hasCameraPermission() && (Recorder.mixedReality || Recorder.faceBubble)) add(Manifest.permission.CAMERA)
            if (!Recorder.hasMicPermission() && Recorder.microphone) add(Manifest.permission.RECORD_AUDIO)
        }
        if (missing.isEmpty()) then() else askPermissions.launch(missing.toTypedArray())
    }

    Column(
        Modifier.fillMaxSize().background(Orbit.Bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("RECORD", color = Orbit.Accent, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
                Text("Film your glasses and you", color = Orbit.Text1, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Text("Done", color = Orbit.Accent, fontSize = 15.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClose).padding(10.dp))
        }

        if (Recorder.recording) {
            StatusLine("Recording ${RecordingNames.elapsed(android.os.SystemClock.uptimeMillis() - Recorder.startedAt)} · ${Recorder.files.joinToString(" + ") { it.view.suffix }}")
            GradientButton("Stop and save", Modifier.fillMaxWidth()) { Recorder.stop() }
        } else {
            GradientButton("Start recording", Modifier.fillMaxWidth()) { withPermissions { Recorder.start() } }
            Recorder.lastError?.let { StatusLine(it) }
            if (Recorder.lastSaved.isNotEmpty()) StatusLine("Last recording saved to Movies/OrbitWalker (${Recorder.lastSaved.joinToString(" and ")})")
        }
        Text("Ctrl+Alt+S starts and stops from the keyboard. Notifications never appear in recordings.", color = Orbit.Text3, fontSize = 12.sp)

        SectionTitle("What to record")
        SettingRow("Glasses view", "Exactly what you see, 1080p", trailing = { OrbitSwitch(Recorder.glassesView) { Recorder.glassesView = it } })
        SettingRow("Mixed reality", "You, filmed by this phone, with every open screen where it floats around you. Saved as a second file to edit with.",
            trailing = { OrbitSwitch(Recorder.mixedReality) { Recorder.mixedReality = it } })
        SettingRow("Microphone", "Your voice in both files", trailing = { OrbitSwitch(Recorder.microphone) { Recorder.microphone = it } })
        SettingRow("Face bubble", "Your camera picture in a corner of the glasses view", trailing = { OrbitSwitch(Recorder.faceBubble) { Recorder.faceBubble = it } })

        SectionTitle("Where the phone stands")
        Text("Put this phone on a tripod: landscape for a 16:9 video, portrait for Shorts and Reels. Then pick its spot, or line it up exactly.", color = Orbit.Text2, fontSize = 13.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CameraSpot.entries.forEach { s ->
                val on = s == Recorder.spot
                Text(
                    s.label, fontSize = 13.sp, color = if (on) Orbit.Bg else Orbit.Text1,
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) Orbit.Accent else Orbit.Surface2)
                        .clickable { Recorder.spot = s }.padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
        StatusLine(CameraRig.placement(Recorder.rig))
        GhostButton(if (Recorder.monitorOn) "Close the camera monitor" else "Line up the camera in the glasses", Modifier.fillMaxWidth()) {
            if (Recorder.monitorOn) Recorder.setMonitor(false) else withPermissions { Recorder.setMonitor(true) }
        }
        Text(
            "The camera monitor shows the mixed reality picture in your glasses (Ctrl+Alt+M). Move the virtual camera until your screens " +
                "sit where they should: ←→ around you, ↑↓ height, Shift+arrows aim, PgUp/PgDn distance, [ ] zoom, 1–7 spots, Enter when done.",
            color = Orbit.Text3, fontSize = 12.sp,
        )

        SectionTitle("Camera")
        SettingRow("Wide lens", "Use the phone's ultra-wide lens, to fit you and all your screens", trailing = { OrbitSwitch(Recorder.wideLens) { Recorder.wideLens = it } })
        SettingRow("Front camera", "Use the selfie camera instead", trailing = { OrbitSwitch(Recorder.frontCamera) { Recorder.frontCamera = it } })
        SettingRow("You in front of the screens", "Finds you in the picture (on the phone) so screens behind you are hidden by you",
            trailing = { OrbitSwitch(Recorder.personInFront) { Recorder.personInFront = it } })

        SectionTitle("Good to know")
        Text(
            "• The glasses' cable has to reach the phone: a USB-C extension cable gives the tripod room.\n" +
                "• Keep the phone still while recording; move the tripod and you'll need to line it up again.\n" +
                "• Netflix and other protected video record as black (Android enforces this).\n" +
                "• Two 1080p files at once is hard work: if the phone gets hot, record one view.",
            color = Orbit.Text2, fontSize = 13.sp, lineHeight = 19.sp,
        )
        Spacer(Modifier.height(24.dp))
    }
}
