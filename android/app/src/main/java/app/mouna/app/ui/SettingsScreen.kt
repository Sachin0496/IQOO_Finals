package app.mouna.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mouna.app.MounaApp
import app.mouna.app.Screen
import app.mouna.app.engine.CallUsage
import app.mouna.app.engine.Knowledge
import app.mouna.app.engine.Lang
import app.mouna.app.engine.Sarvam
import app.mouna.core.DecisionKind

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(app: MounaApp, k: Knowledge, openProbe: () -> Unit) {
    var wipeArmed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Text("Settings", style = Type.title)
        Spacer(Modifier.height(18.dp))

        Card {
            Column {
                SectionLabel("Caregiver's language")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Lang.entries.forEach { l -> Chip(l.label, l == app.lang) { app.chooseLang(l) } }
                }
                Spacer(Modifier.height(16.dp))
                SectionLabel("Voice")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    app.voice.voices.forEach { v -> Chip(v.label, v.id == app.voiceId) { app.chooseVoice(v.id); app.speak("water", "test") } }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Careful mode", style = Type.phrase)
                    Text("Speak only when very sure; otherwise show pictures.", style = Type.body.copy(fontSize = 13.sp))
                }
                Switch(
                    checked = app.careful,
                    onCheckedChange = { app.chooseCareful(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Ink.turmeric, checkedThumbColor = Ink.bg),
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Card {
            Column {
                SectionLabel("Beyond the lips")
                SetupRow("Your movement", if (k.switchReady) "Taught · it means yes" else "A raised eyebrow, a half smile… as your yes", k.switchReady,
                    if (k.switchReady) "Redo" else "Teach") { app.go(Screen.SWITCH) }
                Spacer(Modifier.height(12.dp))
                SetupRow("Look to choose", if (k.gazeReady) "Calibrated · look left or right" else "Pick between two pictures with your eyes", k.gazeReady,
                    if (k.gazeReady) "Redo" else "Set up") { app.go(Screen.EYES) }
            }
        }

        Spacer(Modifier.height(14.dp))
        Card {
            Column {
                SectionLabel("Phone calls")
                Text("Your name, said in the introduction when you call someone.", style = Type.body.copy(fontSize = 13.sp))
                Spacer(Modifier.height(8.dp))
                var name by remember { mutableStateOf(app.callerName) }
                TextBox(name, { name = it.take(40); app.chooseCallerName(name) }, "Your name", Modifier.fillMaxWidth())
                Spacer(Modifier.height(14.dp))
                Text("Call server, for web link calls: the address of the relay (call-server/), like https://calls.example.com.", style = Type.body.copy(fontSize = 13.sp))
                Spacer(Modifier.height(8.dp))
                var server by remember { mutableStateOf(app.store.callServer) }
                var serverRefused by remember { mutableStateOf<String?>(null) }
                TextBox(server, { server = it; serverRefused = app.chooseCallServer(it) }, "https://…", Modifier.fillMaxWidth(), keyboard = KeyboardType.Uri)
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        serverRefused != null -> "Not saved. $serverRefused"
                        server.isNotBlank() -> "Using the address saved here."
                        app.callServer().isNotBlank() -> "Using the address built into this app."
                        else -> "No call server: web link calls are off."
                    },
                    style = Type.mono.copy(fontSize = 12.sp),
                )
                Spacer(Modifier.height(14.dp))
                Text("Sarvam API key, for a natural voice on calls. Only the words to speak are sent, never audio or video.", style = Type.body.copy(fontSize = 13.sp))
                Spacer(Modifier.height(8.dp))
                var key by remember { mutableStateOf(app.store.sarvamKey) }
                TextBox(key, { key = it; app.store.sarvamKey = it }, "Sarvam API key", Modifier.fillMaxWidth(), secret = true)
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        key.isNotBlank() -> "Using the key saved here."
                        app.voice.sarvamKey().isNotBlank() -> "Using the key built into this app."
                        else -> "No key: the phone's own voice speaks."
                    },
                    style = Type.mono.copy(fontSize = 12.sp),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "Sentences spoken in the natural voice are kept on this phone (the newest ${Sarvam.CACHE_KEEP}, typed ones too) so a repeated phrase is instant. Nothing else is stored.",
                    style = Type.body.copy(fontSize = 13.sp),
                )
                Spacer(Modifier.height(8.dp))
                var kept by remember { mutableStateOf(app.voice.cacheStats()) }
                Text("${kept.first} sentences kept · ${(kept.second + 1023) / 1024} KB", style = Type.mono.copy(fontSize = 12.sp))
                Spacer(Modifier.height(8.dp))
                BigButton("Clear voice cache", Tone.NO, Modifier.fillMaxWidth(), enabled = kept.first > 0) {
                    app.clearVoiceCache()
                    kept = app.voice.cacheStats()
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Card {
            Column {
                SectionLabel("Lip encoder")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(if (k.ready) k.encoder.label else "Loading…", if (!k.ready) Ink.mute else if (k.encoder.label.startsWith("NPU")) Ink.leaf else if (k.encoder.label.startsWith("CPU")) Ink.turmeric else Ink.mute)
                    k.encoder.warmMs?.let { Text("  ${"%.1f".format(it)} ms / window", style = Type.mono) }
                }
                Spacer(Modifier.height(10.dp))
                Text(k.encoder.detail, style = Type.mono.copy(fontSize = 12.sp))
                k.encoder.selfTestCosine?.let { Text("self-test cosine ${"%.5f".format(it)} (pass ≥ 0.99)", style = Type.mono.copy(fontSize = 12.sp)) }
                k.lastMs?.let { Text("last decision ${it.toInt()} ms after the lips stopped", style = Type.mono.copy(fontSize = 12.sp)) }
                Text("voice: ${app.voiceStatus}", style = Type.mono.copy(fontSize = 12.sp))
                Spacer(Modifier.height(8.dp))
                Text(
                    "To use the NPU: adb push qnn_ctx_fp16.onnx /sdcard/Android/data/app.mouna/files/encoder/ and restart.",
                    style = Type.mono.copy(fontSize = 11.sp, color = Ink.mute),
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Card {
            Column {
                SectionLabel("QA tools")
                Text(
                    "Probe: camera fps, landmark ms, the 96 px mouth crop, installed voices, permissions and the 30-minute soak.",
                    style = Type.body.copy(fontSize = 13.sp),
                )
                Spacer(Modifier.height(10.dp))
                BigButton("Open the probe", Tone.NO, Modifier.fillMaxWidth(), onClick = openProbe)
                Spacer(Modifier.height(14.dp))
                Text("On a call, play Mouna's voice as (which one the microphone hears best differs by phone):", style = Type.body.copy(fontSize = 13.sp))
                Spacer(Modifier.height(8.dp))
                var usage by remember { mutableStateOf(app.voice.callUsage) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CallUsage.entries.forEach { u -> Chip(if (u == CallUsage.VOICE) "call audio" else "media", u == usage) { usage = u; app.voice.callUsage = u } }
                }
                Spacer(Modifier.height(14.dp))
                Text("Preview what Mouna shows when it isn't sure:", style = Type.body.copy(fontSize = 13.sp))
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(DecisionKind.CONFIRM, DecisionKind.RESCUE, DecisionKind.CHOOSE, DecisionKind.NOT_TAUGHT).forEach { kind ->
                        Chip(kind.name.lowercase().replace('_', ' '), false) { app.preview(kind) }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        BigButton(if (wipeArmed) "Tap again to forget everything" else "Start over with a new person", if (wipeArmed) Tone.PRIMARY else Tone.QUIET, Modifier.fillMaxWidth()) {
            if (wipeArmed) {
                app.engine.wipe()
                wipeArmed = false
            } else {
                wipeArmed = true
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = Type.body.copy(fontSize = 14.sp, color = if (on) Ink.bg else Ink.bone),
        modifier = Modifier
            .clip(CircleShape)
            .then(if (on) Modifier.background(Ink.bone) else Modifier.border(1.dp, Ink.rule2, CircleShape))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
private fun SetupRow(title: String, note: String, done: Boolean, action: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = Type.phrase.copy(fontSize = 18.sp))
                if (done) {
                    Spacer(Modifier.width(8.dp))
                    Text("✓", style = Type.phrase.copy(color = Ink.leaf, fontSize = 18.sp))
                }
            }
            Text(note, style = Type.body.copy(fontSize = 13.sp))
        }
        Spacer(Modifier.width(10.dp))
        BigButton(action, Tone.NO, onClick = onClick)
    }
}
