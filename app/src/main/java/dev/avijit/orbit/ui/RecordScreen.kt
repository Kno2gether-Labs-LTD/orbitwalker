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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.avijit.orbit.record.CameraRig
import dev.avijit.orbit.record.CameraSpot
import dev.avijit.orbit.record.HeroSide
import dev.avijit.orbit.record.ScreenStaging
import dev.avijit.orbit.record.MixedBackground
import dev.avijit.orbit.record.PhoneStand
import dev.avijit.orbit.record.Recorder
import dev.avijit.orbit.record.RecordingNames
import dev.avijit.orbit.ui.theme.Orbit

/** Recording: the glasses view and a mixed reality view (you, filmed by the phone, among your screens). */
@Composable
fun RecordScreen(onClose: () -> Unit, onStudio: () -> Unit = {}) {
    @Suppress("UNUSED_VARIABLE") val v = Recorder.version.intValue // redraw on every recorder change
    DisposableEffect(Unit) { Recorder.watchTilt(true); onDispose { Recorder.watchTilt(false) } }
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
        SettingRow("Clean screen", "Just the screen you're using, flat and full size, with the pointer. Pin it onto a wall in your video editor.",
            trailing = { OrbitSwitch(Recorder.cleanScreen) { Recorder.cleanScreen = it } })
        SettingRow("Microphone", "Your voice in every file", trailing = { OrbitSwitch(Recorder.microphone) { Recorder.microphone = it } })
        SettingRow("Face bubble", "Your camera picture in a corner of the glasses view", trailing = { OrbitSwitch(Recorder.faceBubble) { Recorder.faceBubble = it } })

        SectionTitle("Where the phone stands")
        Text("Put this phone on a tripod: on its side for a 16:9 video, upright for Shorts and Reels. Then pick its spot, or line it up exactly.", color = Orbit.Text2, fontSize = 13.sp)
        Chips(PhoneStand.entries, Recorder.phoneStand, { it.label }) { Recorder.phoneStand = it }
        Chips(CameraSpot.entries, Recorder.spot, { it.label }) { Recorder.spot = it }
        StatusLine(CameraRig.placement(Recorder.rig))
        GhostButton(if (Recorder.studioOpen) "Close the camera studio" else "Open the camera studio in the glasses", Modifier.fillMaxWidth()) {
            withPermissions(onStudio)
        }
        Text(
            "The camera studio is a screen in your glasses (Ctrl+Alt+M) with the live picture and every control here. Click its buttons, " +
                "or use the keys: ←→ around you, ↑↓ height, Shift+arrows aim, PgUp/PgDn distance, [ ] zoom, 1–7 spots. It never shows in recordings.",
            color = Orbit.Text3, fontSize = 12.sp,
        )

        SectionTitle("Screens in the video")
        Chips(ScreenStaging.entries, Recorder.staging, { it.label }) { Recorder.staging = it }
        Text(
            when (Recorder.staging) {
                ScreenStaging.FRAMED -> "The screen you're using stands big behind you, framed for the camera, glowing on the room. Click another screen in the glasses and it swaps in."
                ScreenStaging.BIGGER -> "Every screen twice the size, further back and turned towards the camera."
                ScreenStaging.REAL -> "Screens exactly where they float around you: true to life, but small from the camera."
            },
            color = Orbit.Text3, fontSize = 12.sp,
        )
        if (Recorder.staging == ScreenStaging.FRAMED) {
            Chips(HeroSide.entries, Recorder.heroSide, { "Screen: ${it.label}" }) { Recorder.heroSide = it }
        }

        SectionTitle("Behind you in mixed reality")
        Chips(MixedBackground.entries, Recorder.background, { it.label }) { Recorder.background = it }
        Text("Virtual space puts you in the space you see in the glasses: record anywhere, no studio needed. Works best with a plain wall behind you.", color = Orbit.Text3, fontSize = 12.sp)

        SectionTitle("Camera")
        SettingRow("Ultra-wide lens", "Fits more in, but softer and stretched at the edges. The normal lens is sharpest.", trailing = { OrbitSwitch(Recorder.wideLens) { Recorder.wideLens = it } })
        SettingRow("Front camera", "Use the selfie camera instead", trailing = { OrbitSwitch(Recorder.frontCamera) { Recorder.frontCamera = it } })
        SettingRow("You in front of the screens", "Finds you in the picture (on the phone) so screens behind you are hidden by you",
            trailing = { OrbitSwitch(Recorder.personInFront) { Recorder.personInFront = it } })

        SectionTitle("Good to know")
        Text(
            "• The glasses' cable has to reach the phone: a USB-C extension cable gives the tripod room.\n" +
                "• Keep the phone still while recording; move the tripod and you'll need to line it up again.\n" +
                "• Don't stand with a bright window behind you: the camera can't see your face against it.\n" +
                "• Light makes the video: a lamp in front of you (behind the phone) gives a sharp, clean picture.\n" +
                "• For the classic shot, put the phone over your shoulder, about a metre behind you, and keep the normal lens.\n" +
                "• Netflix and other protected video record as black (Android enforces this).\n" +
                "• Two 1080p files at once is hard work: if the phone gets hot, record one view.",
            color = Orbit.Text2, fontSize = 13.sp, lineHeight = 19.sp,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun <T> Chips(items: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            val on = item == selected
            Text(
                label(item), fontSize = 13.sp, color = if (on) Orbit.Bg else Orbit.Text1,
                modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) Orbit.Accent else Orbit.Surface2)
                    .clickable { onPick(item) }.padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}
