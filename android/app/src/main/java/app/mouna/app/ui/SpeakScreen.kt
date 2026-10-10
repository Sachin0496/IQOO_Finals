package app.mouna.app.ui

import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mouna.app.Channel
import app.mouna.app.MounaApp
import app.mouna.app.Screen
import app.mouna.app.engine.Knowledge
import app.mouna.app.engine.isEmulator

private val switchOn get() = Type.button.copy(fontSize = 15.sp, color = Ink.bg)
private val switchOff get() = Type.button.copy(fontSize = 15.sp, color = Ink.bone2)

private class Status(val text: String, val dot: Color, val pulse: Boolean = false)

private val saidNote get() = Type.mono.copy(fontSize = 13.sp, color = Ink.mute)
private val wrongLink get() = Type.mono.copy(fontSize = 14.sp, color = Ink.turmeric, textDecoration = TextDecoration.Underline)
private val saidStyle get() = Type.display.copy(fontSize = 32.sp, lineHeight = 36.sp)
private val teachLink get() = Type.body.copy(color = Ink.turmeric, textDecoration = TextDecoration.Underline)
private val waitingStyle get() = Type.display.copy(color = Ink.mute, fontStyle = FontStyle.Italic, fontSize = 32.sp, lineHeight = 36.sp)

/** The one thing to tell the person about this channel, in plain words. */
private fun status(app: MounaApp, k: Knowledge, st: LiveStatus, micHot: Boolean): Status = when (app.channel) {
    Channel.SIGN -> when {
        st.signing -> Status("Seeing your sign…", Ink.turmeric, true)
        !k.islKnown -> Status("Getting ready…", Ink.mute, true)
        !k.islReady -> Status("Sign isn’t available on this phone", Ink.mute)
        st.body && st.handsUp -> Status("Go ahead and sign", Ink.leaf)
        st.body -> Status("Ready for a sign", Ink.leaf)
        else -> Status("Step back so your hands show", Ink.mute)
    }
    Channel.VOICE -> when {
        !app.hearing.ready -> Status("Voice isn’t available on this phone", Ink.mute)
        app.hearingBusy -> Status("Understanding…", Ink.turmeric, true)
        micHot -> Status("Hearing you…", Ink.turmeric, true)
        else -> Status("Listening", Ink.leaf)
    }
    Channel.LIPS -> when {
        !k.freeReady && k.freeLoading -> Status(if ("Setting up" in k.freeTalk) "Setting up lip reading (first time)…" else "Getting ready…", Ink.mute, true)
        !k.freeReady -> Status("Lip reading isn’t available on this phone", Ink.mute)
        !st.face -> Status("Looking for your face", Ink.mute)
        st.hearing -> Status("Reading your lips…", Ink.turmeric, true)
        else -> Status("Mouth a sentence", Ink.leaf)
    }
    Channel.TYPE -> Status("Type below, Mouna speaks it aloud", Ink.leaf)
}

/** What the machine is doing: only on stage-debug. */
private fun techStatus(app: MounaApp, k: Knowledge): Status? = when (app.channel) {
    Channel.SIGN -> when {
        !k.islKnown -> Status("ISL · loading…", Ink.mute, true)
        k.islReady -> Status("ISL · 263 signs", Ink.leaf)
        else -> Status(if (isEmulator) "ISL runs on the phone" else "No ISL model", Ink.mute)
    }
    Channel.VOICE -> if (app.hearing.ready) Status("Whisper", Ink.leaf) else Status("No voice model", Ink.mute)
    Channel.LIPS -> if (k.freeReady) Status("${k.freeModel} · NPU", Ink.leaf) else Status(k.freeTalk, Ink.mute, k.freeLoading)
    Channel.TYPE -> null
}

/** How a phrase came to be said, in words. */
private fun howSaid(via: String): String = when {
    via == "lips" -> "read from your lips, after you confirmed"
    via == "voice" -> "by voice"
    via == "sign" -> "by sign"
    via == "touch" -> "by touch"
    via == "typed" -> "by typing"
    via == "ask" -> "from your answers"
    via == "confirm" -> "after you confirmed"
    via == "switch" -> "by your movement"
    via == "call" -> "on the call"
    via.startsWith("eyes") -> "by eyes"
    else -> ""
}

/**
 * The one screen the person lives on: their face, what Mouna last said, and their phrases as pictures.
 * Mouthing is hands-free (the gate finds the utterance); the pictures are the fallback for anyone who can tap.
 */
@Composable
fun SpeakScreen(app: MounaApp, k: Knowledge, bind: (PreviewView) -> Unit) {
    val micHot by remember { derivedStateOf { app.micLevel > 0.35f } }
    app.voiceStatus // the voice model loads in the background: re-read "ready" when its status changes
    // imePadding at the root (not inside the type box): when the keyboard opens the whole column re-measures
    // in the visible area and the camera card shrinks, instead of leaving a blank gap above the keyboard.
    Column(Modifier.fillMaxSize().imePadding()) {
        CameraCard(
            app.engine.live,
            bind,
            Modifier.padding(horizontal = 20.dp).fillMaxWidth().weight(1f),
        ) { st ->
            if (app.channel == Channel.VOICE) VoiceRing(app, Modifier.align(Alignment.Center))
            Row(
                Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 14.dp, end = 62.dp).fillMaxWidth(), // the flip button sits top right
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val main = status(app, k, st, micHot)
                Pill(main.text, main.dot, main.pulse, Modifier.weight(1f, fill = false))
                if (Stage.debug) techStatus(app, k)?.let { Pill(it.text, it.dot, it.pulse) }
            }
            ChannelSwitch(app, Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp))
        }

        Spacer(Modifier.height(14.dp))
        Box(Modifier.padding(horizontal = 24.dp).fillMaxWidth().height(124.dp)) {
            AnimatedContent(
                targetState = app.said,
                transitionSpec = { (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 4 }) togetherWith fadeOut(tween(100)) },
                label = "said",
            ) { said ->
                if (said == null) {
                    Column {
                        Text(when (app.channel) { Channel.VOICE -> "Say a phrase."; Channel.SIGN -> "Sign a word."; Channel.LIPS -> "Mouth anything, in English."; Channel.TYPE -> "Type anything below." }, style = waitingStyle)
                        if (app.channel == Channel.LIPS && !app.onCall) { // never walk away from a call
                            Text(
                                "A name or word Lips can’t read? Teach it →",
                                style = teachLink,
                                modifier = Modifier.heightIn(min = 48.dp).clickable { app.openWords(Screen.SPEAK) }.wrapContentHeight(Alignment.CenterVertically),
                            )
                        }
                    }
                } else {
                    Column {
                        Text(said.text, style = saidStyle, maxLines = 2)
                        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            val how = howSaid(said.via)
                            Text(
                                if (said.via == "none") "Nothing said" else "Said aloud" + (if (how.isEmpty()) "" else " · $how"),
                                style = saidNote,
                            )
                            if (said.via == "voice") { // lips are confirmed before they are spoken
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    "Wrong?",
                                    style = wrongLink,
                                    modifier = Modifier.heightIn(min = 48.dp).clickable { app.wrong() }.padding(horizontal = 6.dp).wrapContentHeight(Alignment.CenterVertically),
                                )
                            }
                        }
                    }
                }
            }
        }

        SectionLabel("Or tap a picture", Modifier.padding(start = 24.dp))
        if (app.channel == Channel.TYPE) TypeBox(app, Modifier.padding(horizontal = 20.dp))
        val tileH = tileHeight(app.lang)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(bottom = 6.dp),
        ) {
            items(k.pack, key = { it }) { id ->
                app.phrases[id]?.let { p ->
                    PhraseTile(p, app.lang, Modifier.width(128.dp).height(tileH)) { app.speak(id, "touch") }
                }
            }
        }
    }
}

/** Lips, voice, sign or typing: whichever this person can use today. */
@Composable
private fun ChannelSwitch(app: MounaApp, modifier: Modifier) {
    Row(
        modifier.clip(CircleShape).background(Ink.bg.copy(alpha = 0.78f)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(Channel.LIPS to "Lips", Channel.VOICE to "Voice", Channel.SIGN to "Sign", Channel.TYPE to "Type").forEach { (c, label) ->
            val on = app.channel == c
            Text(
                label,
                style = if (on) switchOn else switchOff,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (on) Ink.bone else Ink.bg.copy(alpha = 0f))
                    .clickable { app.chooseChannel(c) }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 12.dp) // every channel must fit a phone's width
                    .wrapContentHeight(Alignment.CenterVertically),
            )
        }
    }
}

/** Anything typed is spoken aloud in the caregiver's language, through the same voice as everything else. */
@Composable
private fun TypeBox(app: MounaApp, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    Column(modifier) {
        SectionLabel("Or type anything")
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextBox(text, { text = it.take(200) }, "Type what to say", Modifier.weight(1f), singleLine = false)
            Spacer(Modifier.width(10.dp))
            BigButton("Speak", Tone.PRIMARY, enabled = text.isNotBlank()) {
                app.sayTyped(text)
                text = ""
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

/** A soft ring that breathes with the voice: the person can see they are being heard. Reads the mic level itself. */
@Composable
private fun VoiceRing(app: MounaApp, modifier: Modifier) {
    val scale by animateFloatAsState(1f + 0.6f * app.micLevel, tween(90), label = "ring")
    val busy = app.hearingBusy
    Box(modifier.size(150.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(150.dp).scale(scale).clip(CircleShape).background(Ink.turmeric.copy(alpha = if (busy) 0.30f else 0.14f)))
        Box(Modifier.size(84.dp).clip(CircleShape).background(Ink.bg.copy(alpha = 0.82f)), contentAlignment = Alignment.Center) {
            Icon(MounaIcons.GraphicEq, null, tint = Ink.turmeric, modifier = Modifier.size(38.dp))
        }
    }
}
