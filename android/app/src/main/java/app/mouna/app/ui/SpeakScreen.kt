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
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.Icons
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

/**
 * The one screen the person lives on: their face, what Mouna last said, and their phrases as pictures.
 * Mouthing is hands-free (the gate finds the utterance); the pictures are the fallback for anyone who can tap.
 */
@Composable
fun SpeakScreen(app: MounaApp, k: Knowledge, bind: (PreviewView) -> Unit) {
    val taught = k.pack.count { (k.counts[it] ?: 0) > 0 }
    Column(Modifier.fillMaxSize()) {
        CameraCard(
            app.engine.live,
            bind,
            Modifier.padding(horizontal = 20.dp).fillMaxWidth().weight(1f),
        ) { live ->
            val voice = app.channel == Channel.VOICE
            val sign = app.channel == Channel.SIGN
            if (voice) VoiceRing(app.micLevel, app.hearingBusy, Modifier.align(Alignment.Center))
            Row(
                Modifier.align(Alignment.TopStart).padding(14.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                when {
                    sign && live.signing -> Pill("Seeing your sign", Ink.turmeric, pulse = true)
                    sign && live.body -> Pill(if (live.hands > 0) "Hands up: sign" else "Ready for a sign", Ink.leaf)
                    sign -> Pill("Step back so your hands show", Ink.mute)
                    voice && app.hearingBusy -> Pill("Understanding", Ink.turmeric, pulse = true)
                    voice && app.micLevel > 0.35f -> Pill("Hearing", Ink.turmeric, pulse = true)
                    voice -> Pill("Listening for your voice", Ink.leaf)
                    !k.ready -> Pill("Starting", Ink.mute, pulse = true)
                    !live.face -> Pill("Looking for your face", Ink.mute)
                    live.hearing -> Pill("Hearing", Ink.turmeric, pulse = true)
                    else -> Pill("Watching your lips", Ink.leaf)
                }
                if (sign) {
                    if (!k.islKnown) Pill("ISL · Loading…", Ink.mute, pulse = true)
                    else Pill(if (k.islReady) "ISL · 263 signs" else if (isEmulator) "ISL runs on the phone" else "No ISL model", if (k.islReady) Ink.leaf else Ink.mute)
                } else if (voice) {
                    Pill(if (app.hearing.ready) "Whisper" else "No voice model", if (app.hearing.ready) Ink.leaf else Ink.mute)
                } else {
                    Pill(
                        if (k.ready) k.encoder.label else "Encoder · Loading…",
                        when {
                            !k.ready -> Ink.mute
                            k.encoder.label.startsWith("NPU") -> Ink.leaf
                            k.encoder.label.startsWith("CPU") -> Ink.turmeric
                            else -> Ink.mute
                        },
                        pulse = !k.ready,
                    )
                }
            }
            ChannelSwitch(app, Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp))
        }

        Spacer(Modifier.height(18.dp))
        Box(Modifier.padding(horizontal = 24.dp).fillMaxWidth().height(112.dp)) {
            if (taught == 0 && app.said == null && app.channel == Channel.LIPS) {
                Column {
                    Text("Teach Mouna your phrases first.", style = Type.title.copy(fontSize = 26.sp, fontStyle = FontStyle.Italic))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Two examples each, about a minute in all →",
                        style = Type.body.copy(color = Ink.turmeric, textDecoration = TextDecoration.Underline),
                        modifier = Modifier.clickable { app.go(Screen.TEACH) },
                    )
                }
            } else {
                AnimatedContent(
                    targetState = app.said,
                    transitionSpec = { (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 4 }) togetherWith fadeOut(tween(100)) },
                    label = "said",
                ) { said ->
                    if (said == null) {
                        Text(when (app.channel) { Channel.VOICE -> "Say a phrase."; Channel.SIGN -> "Sign a word."; else -> "Mouth a phrase." }, style = Type.display.copy(color = Ink.mute, fontStyle = FontStyle.Italic, fontSize = 34.sp))
                    } else {
                        Column {
                            Text(said.text, style = Type.display.copy(fontSize = 34.sp, lineHeight = 38.sp), maxLines = 2)
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val ms = k.lastMs?.takeIf { said.via == "lips" }?.let { " · ${it.toInt()} ms" } ?: ""
                                Text("SAID · ${said.via.uppercase()}$ms", style = Type.label)
                                if (said.via == "lips" || said.via == "voice") {
                                    Spacer(Modifier.width(14.dp))
                                    Text(
                                        "Wrong?",
                                        style = Type.mono.copy(color = Ink.turmeric, textDecoration = TextDecoration.Underline),
                                        modifier = Modifier.clickable { app.wrong() },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        SectionLabel("Or tap a picture", Modifier.padding(start = 24.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(bottom = 6.dp),
        ) {
            items(k.pack) { id ->
                app.phrases[id]?.let { p ->
                    PhraseTile(p, app.lang, Modifier.width(118.dp).height(148.dp)) { app.speak(id, "touch") }
                }
            }
        }
    }
}

/** Lips or voice: whichever this person can use today. */
@Composable
private fun ChannelSwitch(app: MounaApp, modifier: Modifier) {
    Row(
        modifier.clip(CircleShape).background(Ink.bg.copy(alpha = 0.78f)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(Channel.LIPS to "Lips", Channel.VOICE to "Voice", Channel.SIGN to "Sign").forEach { (c, label) ->
            val on = app.channel == c
            Text(
                label,
                style = Type.button.copy(fontSize = 14.sp, color = if (on) Ink.bg else Ink.bone2),
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (on) Ink.bone else Ink.bg.copy(alpha = 0f))
                    .clickable { app.chooseChannel(c) }
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            )
        }
    }
}

/** A soft ring that breathes with the voice: the person can see they are being heard. */
@Composable
private fun VoiceRing(level: Float, busy: Boolean, modifier: Modifier) {
    val scale by animateFloatAsState(1f + 0.6f * level, tween(90), label = "ring")
    Box(modifier.size(150.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(150.dp).scale(scale).clip(CircleShape).background(Ink.turmeric.copy(alpha = if (busy) 0.30f else 0.14f)))
        Box(Modifier.size(84.dp).clip(CircleShape).background(Ink.bg.copy(alpha = 0.82f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.GraphicEq, null, tint = Ink.turmeric, modifier = Modifier.size(38.dp))
        }
    }
}
