package app.mouna.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.SystemClock
import app.mouna.BuildConfig
import app.mouna.app.MounaApp
import app.mouna.app.Screen
import app.mouna.app.engine.CallUsage
import app.mouna.app.engine.Knowledge
import app.mouna.app.engine.Lang
import app.mouna.app.engine.ModelStore
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.mouna.app.engine.Sarvam
import app.mouna.core.DecisionKind

/**
 * Counts quick taps on the version line, like Android's developer options: [needed] taps, each within [gapMs] of the
 * one before. Pure (the caller supplies the clock) so it is unit-tested on the JVM.
 */
class TapCounter(private val needed: Int = 7, private val gapMs: Long = 1500) {
    private var count = 0
    private var last = 0L

    /** Registers a tap at [now]; returns how many more are needed, 0 when this one unlocks (the count then starts over). */
    fun tap(now: Long): Int {
        count = if (count > 0 && now - last <= gapMs) count + 1 else 1
        last = now
        if (count >= needed) {
            count = 0
            return 0
        }
        return needed - count
    }
}

/** What each preview of "Mouna isn't sure" is called, in plain words. */
internal fun previewName(kind: DecisionKind): String = when (kind) {
    DecisionKind.CONFIRM -> "Did-you-mean prompt"
    DecisionKind.RESCUE -> "Two look alike"
    DecisionKind.CHOOSE -> "Choose from a few"
    DecisionKind.NOT_TAUGHT -> "Not one of my phrases"
    else -> kind.name.lowercase().replace('_', ' ')
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(app: MounaApp, k: Knowledge, openProbe: () -> Unit) {
    var confirmWipe by remember { mutableStateOf(false) } // a dialog, never an armed button: leaving Settings forgets it
    val context = LocalContext.current
    val taps = remember { TapCounter() }
    var toast by remember { mutableStateOf<Toast?>(null) }
    fun say(text: String) {
        toast?.cancel()
        toast = Toast.makeText(context, text, Toast.LENGTH_SHORT).also { it.show() }
    }

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
                    app.voice.voices.forEach { v -> Chip(v.label, v.id == app.voiceId) { app.chooseVoice(v.id) } } // choosing is silent
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HearIt(enabled = !app.onCall) { app.hearVoice() }
                    if (app.onCall) {
                        Spacer(Modifier.width(10.dp))
                        Text("Not during a call", style = Type.body.copy(fontSize = 13.sp, color = Ink.mute))
                    }
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
                Spacer(Modifier.width(12.dp))
                MounaSwitch(app.careful) { app.chooseCareful(it) }
            }
        }

        Spacer(Modifier.height(14.dp))
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Learn from clear matches", style = Type.phrase)
                    Text("Keeps very clear matches you don't correct as extra examples, up to 5 per phrase.", style = Type.body.copy(fontSize = 13.sp))
                }
                Spacer(Modifier.width(12.dp))
                MounaSwitch(app.selfTrain) { app.chooseSelfTrain(it) }
            }
        }

        Spacer(Modifier.height(14.dp))
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Light mode", style = Type.phrase)
                    Text("Warm paper theme for bright rooms and sunlight.", style = Type.body.copy(fontSize = 13.sp))
                }
                Spacer(Modifier.width(12.dp))
                MounaSwitch(app.light) { app.chooseLight(it) }
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
                Spacer(Modifier.height(12.dp))
                val recorded = app.recordedCount
                SetupRow("Teach Lips", if (recorded > 0) "$recorded sentences recorded · they are offered first" else "Mouth sentences you want to say, so Lips learns you",
                    recorded > 0, "Record") { app.go(Screen.RECORD) }
                Spacer(Modifier.height(12.dp))
                val taught = k.words.count { (k.wordCounts[it.first] ?: 0) > 0 }
                SetupRow("Teach Lips a word", if (taught > 0) "$taught word${if (taught == 1) "" else "s"} · names, any language" else "Names and words Lips can’t read, in any language",
                    taught > 0, if (taught > 0) "Edit" else "Teach") { app.openWords(Screen.SETTINGS) }
            }
        }

        val models = remember(k.freeModelId, k.freeLoading) { app.freeTalkModels() }
        if (models.size > 1) {
            Spacer(Modifier.height(14.dp))
            Card {
                Column {
                    SectionLabel("Lips model")
                    Text(
                        "The original reads anyone. A model tuned to one person reads that person better, and others less well.",
                        style = Type.body.copy(fontSize = 13.sp),
                    )
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        models.forEach { m ->
                            Chip(m.label, m.id == (k.freeModelId ?: models.first().id)) {
                                if (m.id != k.freeModelId && !k.freeLoading) app.chooseFreeTalkModel(m.id)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val status = when {
                        k.freeLoading -> k.freeTalk
                        k.freeReady -> "In use: ${k.freeModel}"
                        else -> k.freeTalk
                    }
                    Text(status, style = Type.body.copy(fontSize = 13.sp, color = if (k.freeReady) Ink.leaf else Ink.mute))
                    models.filter { !it.ready }.takeIf { it.isNotEmpty() }?.let { notReady ->
                        Text(
                            "First use of ${notReady.joinToString { it.label }} sets it up on the NPU (about 25 min, keep the phone unlocked).",
                            style = Type.body.copy(fontSize = 12.sp, color = Ink.mute),
                        )
                    }
                }
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
            }
        }

        Spacer(Modifier.height(14.dp))
        Card {
            // Re-read when coming back from Android's settings page.
            var kept by remember { mutableStateOf(ModelStore.kept()) }
            LifecycleResumeEffect(Unit) {
                kept = ModelStore.kept()
                onPauseOrDispose {}
            }
            SetupRow(
                "Keep models",
                if (kept) "Kept in /sdcard/Mouna: reinstalling Mouna won't delete them" else "Lips, sign and voice models are deleted if Mouna is reinstalled. Allow \"All files access\" to keep them",
                kept,
                if (kept) "Change" else "Allow",
            ) { ModelStore.askAccess(context) }
        }

        if (Stage.debug) {
            Spacer(Modifier.height(14.dp))
            Advanced(app, k, openProbe, off = {
                Stage.debug = false
                say("Advanced settings off")
            })
        }

        Spacer(Modifier.height(14.dp))
        BigButton(
            "Start over with a new person",
            Tone.QUIET,
            Modifier.fillMaxWidth(),
            enabled = !app.onCall,
        ) { confirmWipe = true }
        if (app.onCall) Text("Not during a call.", style = Type.body.copy(fontSize = 13.sp, color = Ink.mute), modifier = Modifier.padding(top = 6.dp))

        // The version line: seven quick taps open Advanced, as Android's developer options do.
        Text(
            "Mouna · version ${BuildConfig.VERSION_NAME}",
            style = Type.label.copy(letterSpacing = 0.sp),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .heightIn(min = 48.dp)
                .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {
                    if (Stage.debug) {
                        say("Advanced settings are on")
                    } else {
                        val left = taps.tap(SystemClock.elapsedRealtime())
                        if (left == 0) {
                            Stage.debug = true
                            say("Advanced settings on")
                        } else if (left <= 3) {
                            say(if (left == 1) "1 more tap for Advanced settings" else "$left more taps for Advanced settings")
                        }
                    }
                }
                .padding(top = 14.dp),
        )
        Spacer(Modifier.height(20.dp))
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            containerColor = Ink.raised,
            titleContentColor = Ink.bone,
            textContentColor = Ink.bone2,
            title = { Text("Start over with a new person?", style = Type.title.copy(fontSize = 24.sp, lineHeight = 28.sp)) },
            text = {
                Column {
                    Text("This deletes, from this phone:", style = Type.body)
                    Spacer(Modifier.height(8.dp))
                    listOf(
                        "everything Mouna learned: lip, voice and sign examples",
                        "voice templates",
                        "favourites and saved links",
                        "your own phrases",
                        "settings: language, voice, your name, and anything typed in Advanced",
                    ).forEach { Text("•  $it", style = Type.body.copy(fontSize = 14.sp), modifier = Modifier.padding(bottom = 4.dp)) }
                    Spacer(Modifier.height(8.dp))
                    Text("It can't be undone.", style = Type.body.copy(color = Ink.bone))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    app.startOver()
                    say("Mouna is ready for a new person")
                }) { Text("Delete everything", style = Type.button.copy(color = Ink.kumkum)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) { Text("Keep everything", style = Type.button) }
            },
        )
    }
}

/** Everything technical, shown only after the version line has been tapped seven times (see [Stage]). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Advanced(app: MounaApp, k: Knowledge, openProbe: () -> Unit, off: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Advanced", style = Type.phrase)
                    Text("For setting up and testing. Also shows technical status on the other screens.", style = Type.body.copy(fontSize = 13.sp))
                }
                Spacer(Modifier.width(12.dp))
                MounaSwitch(true) { if (!it) off() }
            }
        }

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
                    "To use the NPU: adb push qnn_ctx_fp16.onnx to /sdcard/Mouna/encoder/ (Keep models on) or /sdcard/Android/data/app.mouna/files/encoder/, and restart.",
                    style = Type.mono.copy(fontSize = 11.sp, color = Ink.mute),
                )
            }
        }

        Card {
            Column {
                SectionLabel("Web calls")
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
            }
        }

        Card {
            Column {
                SectionLabel("Natural voice")
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
                Text("Show what Mouna shows when it isn't sure:", style = Type.body.copy(fontSize = 13.sp))
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(DecisionKind.CONFIRM, DecisionKind.RESCUE, DecisionKind.CHOOSE, DecisionKind.NOT_TAUGHT).forEach { kind ->
                        Chip(previewName(kind), false) { app.preview(kind) }
                    }
                }
                if (app.onCall) Text("Not during a call.", style = Type.body.copy(fontSize = 13.sp, color = Ink.mute), modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** A switch whose off state can be seen on the dark cards: a bone outline and thumb on a lifted track. */
@Composable
private fun MounaSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = Ink.turmeric,
            checkedThumbColor = Ink.bg,
            checkedBorderColor = Ink.turmeric,
            uncheckedTrackColor = Ink.rule2,
            uncheckedThumbColor = Ink.bone2,
            uncheckedBorderColor = Ink.bone2,
        ),
    )
}

/** A small play button: the only way Settings makes a sound. */
@Composable
private fun HearIt(enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .border(1.dp, if (enabled) Ink.bone2 else Ink.rule, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 10.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.PlayArrow, null, tint = if (enabled) Ink.bone else Ink.mute, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text("Hear it", style = Type.body.copy(fontSize = 14.sp, color = if (enabled) Ink.bone else Ink.mute))
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
