package dev.avijit.orbit.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.avijit.orbit.agent.OrbitAgent
import dev.avijit.orbit.agent.PairLink
import dev.avijit.orbit.agent.Voices
import dev.avijit.orbit.agent.WakeWord
import dev.avijit.orbit.ui.theme.Orbit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opens the agent screen on the phone (e.g. when a pairing link arrives). */
object AgentNav {
    val open = androidx.compose.runtime.mutableStateOf(false)
    val pendingLink = androidx.compose.runtime.mutableStateOf<String?>(null)
}

/** Orbit agent: pair with your own Orbit Server, "Hey Orbit", and a type-to-ask box. */
@Composable
fun AgentScreen(onClose: () -> Unit, pendingLink: String? = null) {
    @Suppress("UNUSED_VARIABLE") val v = OrbitAgent.version.intValue + WakeWord.version.intValue + Voices.version.intValue
    androidx.compose.runtime.LaunchedEffect(OrbitAgent.paired) { Voices.refresh() }
    var voicesOpen by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Keyed on the link: a new one (scanned while this screen is open) replaces what's in the box.
    var linkText by remember(pendingLink) { mutableStateOf(pendingLink ?: "") }
    var pairStatus by remember { mutableStateOf<String?>(null) }
    var question by remember { mutableStateOf("") }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) WakeWord.setEnabled(true) else pairStatus = "The microphone is needed for \"Hey Orbit\""
    }
    fun pair() {
        val p = PairLink.parse(linkText) ?: run { pairStatus = "That isn't a pairing link. Copy it from your Orbit Server's Pair page."; return }
        pairStatus = "Pairing with ${p.name}…"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { OrbitAgent.link.pair(p, "OrbitWalker on ${android.os.Build.MODEL}") }
            }
            pairStatus = r.fold({ "Paired with ${it.serverName}" }, { "Couldn't pair: ${it.message ?: it.javaClass.simpleName}" })
            if (r.isSuccess) { linkText = ""; AgentNav.pendingLink.value = null; dev.avijit.orbit.agent.Voices.refresh() }
            OrbitAgent.forgetConversation()
        }
    }

    Column(
        Modifier.fillMaxSize().background(Orbit.Bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ORBIT AGENT", color = Orbit.Accent, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
                Text("Talk to your glasses", color = Orbit.Text1, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Text("Done", color = Orbit.Accent, fontSize = 15.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClose).padding(10.dp))
        }
        Text(
            "Say \"Hey Orbit\" or click the mic in the dock, then ask: \"open YouTube\", \"put Chrome on the left and make it bigger\", " +
                "\"start recording\", \"how did I sleep?\". It runs on your own Orbit Server.",
            color = Orbit.Text2, fontSize = 13.sp,
        )

        SectionTitle("Your Orbit Server")
        val l = if (OrbitAgent.paired) OrbitAgent.link.link else null
        if (l != null) {
            StatusLine("Paired with ${l.serverName.ifBlank { "your server" }}")
            GhostButton("Unpair this phone", Modifier.fillMaxWidth()) { OrbitAgent.link.forget(); OrbitAgent.forgetConversation(); pairStatus = null }
        } else {
            Text("On your Orbit Server dashboard, open Pair and copy the OrbitWalker link (or scan its QR with this phone).", color = Orbit.Text3, fontSize = 12.sp)
            Field(linkText, "orbitwalker://pair?…") { linkText = it }
            PairLink.parse(linkText)?.let { p ->
                val host = runCatching { java.net.URI(p.url).host }.getOrNull() ?: p.url
                StatusLine("This connects to $host${if (p.ca) "" else " (certificate ${p.fingerprint?.take(12)}…)"}. Only pair with your own Orbit Server.")
            }
            GradientButton("Pair", Modifier.fillMaxWidth(), enabled = linkText.isNotBlank()) { pair() }
        }
        pairStatus?.let { StatusLine(it) }

        SectionTitle("\"Hey Orbit\"")
        SettingRow(
            "Listen for \"Hey Orbit\"",
            when (WakeWord.state) {
                WakeWord.State.OFF -> "Off. The mic in the dock (or Ctrl+Alt+O) still works."
                else -> WakeWord.detail.ifBlank { WakeWord.state.name.lowercase() }
            },
            trailing = {
                OrbitSwitch(WakeWord.enabled) { on ->
                    if (!on) WakeWord.setEnabled(false)
                    else if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) WakeWord.setEnabled(true)
                    else askMic.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
        )
        Text("Listening happens offline on this phone; only what you say after \"Hey Orbit\" is sent to your server.", color = Orbit.Text3, fontSize = 12.sp)
        Text("Once called, Orbit stays in the conversation: just keep talking. After ${OrbitAgent.SESSION_IDLE_MS / 1000} seconds of quiet " +
            "(or \"that's all\") it steps back until you say \"Hey Orbit\" again.", color = Orbit.Text3, fontSize = 12.sp)

        var buddyOn by remember { mutableStateOf(dev.avijit.orbit.agent.OrbitBuddy.enabled) }
        SettingRow("Show Orbit on the dock", "A little Orbit lives on the dock: it pops up when you call it.", trailing = {
            OrbitSwitch(buddyOn) { on -> buddyOn = on; dev.avijit.orbit.agent.OrbitBuddy.setEnabled(ctx, on) }
        })

        SectionTitle("Orbit's voice")
        SettingRow("Voice", Voices.selectedLabel(), trailing = {
            Text(if (voicesOpen) "Close" else "Change", color = Orbit.Accent, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { voicesOpen = !voicesOpen; if (voicesOpen) Voices.refresh() }.padding(8.dp))
        })
        if (voicesOpen) {
            VoiceOption("Server default", "Whatever your Orbit Server's dashboard picks", Voices.selected == "") { Voices.selected = "" }
            VoiceOption("Phone voice", "This phone's own text-to-speech (works offline)", Voices.selected == Voices.PHONE) { Voices.selected = Voices.PHONE }
            Voices.choices.forEach { c -> VoiceOption(c.name, c.provider, Voices.selected == c.id) { Voices.selected = c.id } }
            if (Voices.listNote.isNotBlank()) StatusLine(Voices.listNote)
        }
        GhostButton("Hear Orbit's voice", Modifier.fillMaxWidth()) { Voices.speak("Hi! I'm Orbit. Just say Hey Orbit whenever you need me.") }
        Text("Voices from OpenAI, ElevenLabs, Inworld and more are set up on your Orbit Server's dashboard (Voices); the keys stay there. " +
            "If a server voice can't be reached, the phone's voice speaks instead.", color = Orbit.Text3, fontSize = 12.sp)

        SectionTitle("Ask by typing")
        Field(question, "e.g. open YouTube and Chrome side by side") { question = it }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GradientButton("Ask", Modifier.weight(1f), enabled = question.isNotBlank() && OrbitAgent.paired) { OrbitAgent.ask(question); question = "" }
            GhostButton("New conversation", Modifier.weight(1f)) { OrbitAgent.forgetConversation() }
        }
        if (OrbitAgent.lastHeard.isNotBlank()) StatusLine("You: ${OrbitAgent.lastHeard}")
        when {
            OrbitAgent.phase == OrbitAgent.Phase.THINKING -> StatusLine("Orbit is working…")
            OrbitAgent.lastError != null -> StatusLine(OrbitAgent.lastError!!)
            OrbitAgent.lastAnswer.isNotBlank() -> StatusLine("Orbit: ${OrbitAgent.lastAnswer}")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Field(value: String, hint: String, onChange: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Orbit.Surface2).padding(14.dp)) {
        if (value.isEmpty()) Text(hint, color = Orbit.Text3, fontSize = 14.sp)
        BasicTextField(value, onChange, textStyle = TextStyle(color = Orbit.Text1, fontSize = 14.sp), cursorBrush = SolidColor(Orbit.Accent), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun VoiceOption(name: String, detail: String, selected: Boolean, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) Orbit.Surface2 else Orbit.Bg)
            .clickable(onClick = onPick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(end = 12.dp).clip(RoundedCornerShape(50)).background(if (selected) Orbit.Accent else Orbit.Surface2).padding(6.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Orbit.Text1, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            Text(detail, color = Orbit.Text3, fontSize = 12.sp)
        }
    }
}
