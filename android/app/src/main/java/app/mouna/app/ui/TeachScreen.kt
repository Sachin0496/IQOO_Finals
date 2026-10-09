package app.mouna.app.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import app.mouna.app.engine.PhrasePack
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mouna.app.MounaApp
import app.mouna.app.Screen
import app.mouna.app.engine.Knowledge
import app.mouna.app.engine.Listen
import app.mouna.core.CoreConstants
import app.mouna.core.Level
import kotlinx.coroutines.delay

private const val NEGATIVES_WANTED = 5 // measured: 5 "none of these" lifts taught-and-spoken 55.0% -> 64.8% (E17)

/**
 * Active teaching (PLAN.md §3): one example of each phrase, a second so Mouna can check itself, and more only where
 * it is still unsure or two phrases look alike for this person. "Mouna learns only what it still needs to learn."
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TeachScreen(app: MounaApp, k: Knowledge, bind: (PreviewView) -> Unit) {
    var session by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf<String?>(null) } // the phrase being confirmed for removal
    val busy = k.listen == Listen.TEACH || k.listen == Listen.NEGATIVE
    val taught = app.lastTeach

    // Keep going: after each example, arm the next one Mouna asks for, until the pack is safe.
    LaunchedEffect(app.teachSeq, session) {
        if (!session || taught == null) return@LaunchedEffect
        delay(1300)
        val now = app.engine.knowledge.value
        if (now.listen != Listen.PAUSED) return@LaunchedEffect // already listening for one
        if (now.next == null) session = false else app.engine.listen(Listen.TEACH, now.next.intent)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Text("Teach Mouna", style = Type.title)
        Spacer(Modifier.height(6.dp))
        Text("Mouth each phrase the way you’ll use it. Any language works. Mouna asks only for what it still needs.", style = Type.body)
        Spacer(Modifier.height(18.dp))

        Card {
            Column {
                CameraCard(app.engine.live, bind, Modifier.fillMaxWidth().height(210.dp)) { st ->
                    Box(Modifier.align(Alignment.TopStart).padding(12.dp)) {
                        when {
                            !st.face -> Pill("Looking for your face")
                            busy && st.hearing -> Pill("Reading your lips…", Ink.turmeric, pulse = true)
                            busy -> Pill("Mouth it now", Ink.turmeric, pulse = true)
                            else -> Pill("Ready", Ink.leaf)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                val next = k.next
                if (next == null && k.pack.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = Ink.leaf, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Your pack is ready", style = Type.phrase.copy(fontSize = 22.sp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Mouna can tell these ${k.pack.size} phrases apart for you.", style = Type.body)
                } else if (next != null) {
                    val p = app.phrases[next.intent]
                    SectionLabel(if (k.listen == Listen.NEGATIVE) "None of these" else "Next")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).clip(CircleShape).background(Ink.bone.copy(alpha = 0.07f)), contentAlignment = Alignment.Center) {
                            Icon(iconFor(next.intent), null, tint = Ink.bone, modifier = Modifier.size(26.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(p?.say(app.lang) ?: next.intent, style = Type.phrase.copy(fontSize = 22.sp))
                            Text(reason(next.reason, app), style = reasonStyle)
                        }
                    }
                }
                taught?.let { t ->
                    Spacer(Modifier.height(12.dp))
                    val name = app.phrases[t.intent]?.say(app.lang) ?: t.intent
                    Text(
                        when (t.check) {
                            null -> "Learned “$name”."
                            true -> "Recognised “$name” before learning it ✓"
                            false -> "Missed “$name” that time; learned it anyway."
                        },
                        style = if (t.check == false) taughtMiss else taughtOk,
                    )
                }
                Spacer(Modifier.height(16.dp))
                when {
                    busy -> BigButton("Stop", Tone.NO, Modifier.fillMaxWidth()) {
                        session = false
                        app.engine.listen(Listen.PAUSED)
                    }
                    next != null -> BigButton(if (taught == null) "Start teaching" else "Continue", Tone.PRIMARY, Modifier.fillMaxWidth(), enabled = k.ready) {
                        session = true
                        app.engine.listen(Listen.TEACH, next.intent)
                    }
                    else -> BigButton("Try it on Speak", Tone.YES, Modifier.fillMaxWidth()) { app.go(Screen.SPEAK) }
                }
            }
        }

        val close = k.pairs.filter { it.level != Level.GREEN && it.a in k.pack && it.b in k.pack }
        if (close.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Card {
                Column {
                    SectionLabel("Look alike for you")
                    close.take(4).forEach { pr ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(if (pr.level == Level.RED) Ink.kumkum else Ink.turmeric))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "${app.phrases[pr.a]?.say(app.lang) ?: pr.a}  ↔  ${app.phrases[pr.b]?.say(app.lang) ?: pr.b}",
                                style = Type.body.copy(color = Ink.bone, fontSize = 14.sp),
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "Rephrase",
                                style = reasonStyle,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .clickable { app.engine.reteach(if ((k.counts[pr.a] ?: 0) <= (k.counts[pr.b] ?: 0)) pr.a else pr.b) }
                                    .padding(horizontal = 8.dp)
                                    .wrapContentHeight(Alignment.CenterVertically),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Red pairs get mixed up. Mouna asks for more of these, or tap Rephrase and mouth that one differently.", style = Type.hint)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Card {
            Column {
                SectionLabel("None of these")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Mouth a few things that are not your phrases.", style = Type.body.copy(color = Ink.bone))
                        Spacer(Modifier.height(8.dp))
                        Dots(minOf(k.negatives, NEGATIVES_WANTED), NEGATIVES_WANTED, Ink.leaf)
                    }
                    Spacer(Modifier.width(12.dp))
                    BigButton("Record", Tone.NO, enabled = k.ready && !busy) {
                        session = false
                        app.engine.listen(Listen.NEGATIVE)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("So Mouna says “not taught” instead of guessing.", style = Type.hint)
            }
        }

        Spacer(Modifier.height(14.dp))
        OwnWords(app)

        Spacer(Modifier.height(14.dp))
        VoiceCard(app, k.pack)

        Spacer(Modifier.height(14.dp))
        Card {
            Column {
                SectionLabel("Your phrases")
                k.pack.mapNotNull { app.phrases[it] }.forEach { p ->
                    val id = p.id
                    val n = k.counts[id] ?: 0
                    if (asking == id) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Remove “${p.say(app.lang)}” and what Mouna learned for it?",
                                style = Type.body.copy(color = Ink.bone, fontSize = 14.sp),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            ChipButton("Keep", Ink.bone2) { asking = null }
                            Spacer(Modifier.width(6.dp))
                            ChipButton("Remove", Ink.kumkumInk) {
                                asking = null
                                if (PhrasePack.isCustom(id)) app.removeCustom(id) else app.engine.setPack(k.pack - id)
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(iconFor(id), null, tint = if (p.urgent) Ink.kumkumInk else Ink.bone2, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(p.say(app.lang), style = Type.body.copy(color = Ink.bone), modifier = Modifier.weight(1f))
                            Dots(minOf(n, CoreConstants.MAX_SHOTS))
                            Spacer(Modifier.width(6.dp))
                            IconButtonSoft(Icons.Rounded.Remove, "Remove ${p.say(app.lang)}") {
                                // Removing forgets the examples too, so ask first unless nothing was taught.
                                if (n > 0 || PhrasePack.isCustom(id)) asking = id
                                else app.engine.setPack(k.pack - id)
                            }
                        }
                    }
                }
                val more = app.phrases.phrases.map { it.id }.filter { it !in k.pack }
                if (more.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    SectionLabel("Add")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        more.forEach { id ->
                            Row(
                                Modifier
                                    .heightIn(min = 48.dp)
                                    .clip(CircleShape)
                                    .border(1.dp, Ink.rule2, CircleShape)
                                    .clickable { app.engine.setPack(k.pack + id) }
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.Add, null, tint = Ink.bone2, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(app.phrases[id]?.say(app.lang) ?: id, style = chipText)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Examples stay on this phone. Nothing is uploaded; no video is kept.",
            style = footnote,
        )
        Spacer(Modifier.height(24.dp))
    }
}

private fun reason(r: String, app: MounaApp): String = when {
    r == "first" -> "First example"
    r == "check" -> "Once more, so Mouna can check itself"
    r == "missed" -> "Missed last time: once more"
    r.startsWith("close_to:") -> "Looks like “${app.phrases[r.removePrefix("close_to:")]?.say(app.lang) ?: r.removePrefix("close_to:")}”: one more helps"
    else -> r
}

/** The family types what they most want to hear ("I love you, Amma"); the person teaches Mouna to say it. */
@Composable
private fun OwnWords(app: MounaApp) {
    var text by remember { mutableStateOf("") }
    Card {
        Column {
            SectionLabel("Your own words")
            Text("Anything you want to be able to say. Mouna will say it in your phone’s voice.", style = Type.body.copy(fontSize = 13.sp))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(80) },
                    placeholder = { Text("I love you, Amma", style = Type.phrase.copy(color = Ink.mute, fontSize = 17.sp)) },
                    textStyle = Type.phrase.copy(fontSize = 17.sp),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Ink.turmeric,
                        unfocusedBorderColor = Ink.rule2,
                        cursorColor = Ink.turmeric,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                BigButton("Add", Tone.PRIMARY, enabled = text.isNotBlank()) {
                    app.addCustom(text)
                    text = ""
                }
            }
        }
    }
}

private val taughtOk = Type.mono.copy(fontSize = 13.sp, color = Ink.leaf)
private val taughtMiss = Type.mono.copy(fontSize = 13.sp, color = Ink.kumkumInk)
private val reasonStyle = Type.mono.copy(fontSize = 13.sp, color = Ink.turmeric)
private val chipText = Type.body.copy(fontSize = 14.sp, color = Ink.bone2)
private val footnote = Type.hint.copy(fontStyle = FontStyle.Italic)

/** A small outlined text button, 48dp tall. */
@Composable
private fun ChipButton(text: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        text,
        style = Type.mono.copy(color = color, fontSize = 13.sp),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .border(1.dp, Ink.rule2, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp)
            .wrapContentHeight(Alignment.CenterVertically),
    )
}

/**
 * Speech recognition, for someone who can still say a little: say each phrase a couple of times. Mouna keeps the
 * words it understood (not the audio), so a soft or slurred "water" still finds water next time. No model is trained.
 */
@Composable
private fun VoiceCard(app: MounaApp, pack: List<String>) {
    app.voiceStatus // re-read "ready" when the model finishes loading
    Card {
        Column {
            SectionLabel("Speech recognition")
            Text(
                if (app.hearing.ready) "If you can say a few words, even softly or unclearly, say each phrase twice. Mouna learns how you sound; it keeps the words it understood, not the audio."
                else "Speech recognition isn’t available on this phone.",
                style = Type.body.copy(fontSize = 14.sp),
            )
            if (!app.hearing.ready) {
                if (Stage.debug) Text(app.voiceStatus, style = Type.mono.copy(fontSize = 11.sp, color = Ink.mute))
                return@Column
            }
            Spacer(Modifier.height(8.dp))
            pack.mapNotNull { app.phrases[it] }.forEach { p ->
                val teaching = app.voiceTeaching == p.id
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(iconFor(p.id), null, tint = Ink.bone2, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.say(app.lang), style = Type.body.copy(color = Ink.bone, fontSize = 14.sp))
                        app.voiceTemplates[p.id]?.lastOrNull()?.let { Text("heard: “$it”", style = Type.mono.copy(fontSize = 12.sp, color = Ink.mute), maxLines = 1) }
                    }
                    Dots(minOf(app.voiceTemplates[p.id]?.size ?: 0, 3), 3, Ink.leaf)
                    Spacer(Modifier.width(8.dp))
                    ChipButton(
                        when {
                            teaching && app.hearingBusy -> "…"
                            teaching -> "Say it"
                            else -> "Record"
                        },
                        if (teaching) Ink.turmeric else Ink.bone2,
                    ) { if (teaching) app.stopVoiceTeaching() else app.teachVoice(p.id) }
                }
            }
        }
    }
}
