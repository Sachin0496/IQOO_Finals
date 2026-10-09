package app.mouna.app.ui

import android.content.Intent
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import app.mouna.app.CallMode
import app.mouna.app.engine.Qr
import app.mouna.app.engine.WebPhase
import androidx.camera.view.PreviewView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mouna.app.CallTab
import app.mouna.app.MounaApp
import app.mouna.app.engine.CallPhrases
import app.mouna.app.engine.CallState
import app.mouna.app.engine.Knowledge
import app.mouna.app.engine.Phones
import kotlinx.coroutines.delay

/**
 * Phone someone and talk through Mouna. Not on a call: a dial pad, contacts and favourites. On a call: who and how
 * long, hang up, an introduction, quick phrases and typing, or the live Speak screen (lips, sign) underneath. Whatever
 * Mouna says on any screen goes into the call: every spoken word already goes through Voice.
 */
@Composable
fun CallScreen(app: MounaApp, k: Knowledge, bind: (PreviewView) -> Unit) {
    when {
        app.onCall && app.usingWeb && app.callState == CallState.DIALING -> WebRinging(app)
        app.onCall -> InCall(app, k, bind)
        else -> Dialer(app)
    }
}

/** "On call with Amma · 01:23 · on speaker", ticking each second. */
@Composable
fun callStatus(app: MounaApp): String {
    val now by produceNow(app.callState)
    val who = app.callWith.ifEmpty { "call" }
    return when (app.callState) {
        CallState.DIALING -> "Calling $who…"
        CallState.ACTIVE -> "On call with $who · ${Phones.clock(((now - app.callSince) / 1000).toInt().coerceAtLeast(0))} · on speaker"
        CallState.IDLE -> ""
    }
}

@Composable
private fun produceNow(key: Any) = remember(key) { mutableLongStateOf(SystemClock.elapsedRealtime()) }.also { s ->
    LaunchedEffect(key) {
        while (true) {
            s.longValue = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
}

// ---------------- not on a call ----------------

/** The mode switch, then the dial pad (phone number) or the link maker (web link). */
@Composable
private fun Dialer(app: MounaApp) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TabChip("Phone number", app.callMode == CallMode.PHONE) { app.chooseCallMode(CallMode.PHONE) }
            TabChip("Web link", app.callMode == CallMode.WEB) { app.chooseCallMode(CallMode.WEB) }
        }
        Box(Modifier.weight(1f)) {
            if (app.callMode == CallMode.PHONE) PhoneDialer(app) else WebDialer(app)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhoneDialer(app: MounaApp) {
    val number = Phones.normalise(app.dialNumber)
    val saved = app.favourites.any { it.second == number }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text(
                    app.dialName?.let { "$it · " }.orEmpty().let { prefix ->
                        if (app.dialNumber.isEmpty()) "Number" else prefix + Phones.pretty(app.dialNumber)
                    },
                    style = Type.display.copy(fontSize = 32.sp, lineHeight = 36.sp, color = if (app.dialNumber.isEmpty()) Ink.mute else Ink.bone),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.size(48.dp).clip(CircleShape).combinedClickable(onClick = { app.backspace() }, onLongClick = { app.setDial("", null) }),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Rounded.Backspace, "Delete", tint = Ink.bone2) }
            }
            Pad(app)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("Pick contact", Tone.NO, Modifier.weight(1f)) { app.chooseContact() }
                if (app.dialName != null && number != null && !saved) BigButton("Save", Tone.NO) { app.saveFavourite() }
            }
            if (app.favourites.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                SectionLabel("Favourites · hold to remove")
                app.favourites.forEach { (name, num) ->
                    Row(
                        Modifier
                            .padding(bottom = 8.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Ink.card)
                            .border(1.dp, Ink.rule, RoundedCornerShape(18.dp))
                            .combinedClickable(onClick = { app.setDial(num, name) }, onLongClick = { app.removeFavourite(num) })
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, style = Type.phrase.copy(fontSize = 18.sp), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Phones.pretty(num), style = Type.mono)
                    }
                }
            }
            if (!app.simReady) {
                Spacer(Modifier.height(10.dp))
                Text("This phone has no SIM, so it can't place a phone call. Use Web link: the other person opens a link instead.", style = Type.body.copy(color = Ink.turmeric))
            }
            app.callNote?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = Type.body.copy(color = Ink.turmeric))
            }
        }
        Spacer(Modifier.height(10.dp))
        BigButton("Call", Tone.YES, Modifier.fillMaxWidth().padding(bottom = 4.dp), enabled = number != null && app.simReady) { app.dial() }
    }
}

/** Web link, not on a call: who it is for (optional), their saved links, and Start call. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WebDialer(app: MounaApp) {
    val server = app.callServer()
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("Call anyone from their browser", style = Type.title.copy(fontSize = 24.sp, lineHeight = 28.sp))
            Spacer(Modifier.height(6.dp))
            Text(
                "Mouna makes a link and a QR code. The other person opens it on any phone, taps Answer, and talks. Nothing to install, no SIM needed.",
                style = Type.body.copy(fontSize = 14.sp),
            )
            Spacer(Modifier.height(16.dp))
            SectionLabel("Who is it for? (to save their link)")
            TextBox(app.webName, { app.chooseWebName(it) }, "Amma", Modifier.fillMaxWidth())
            if (app.webFavourites.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                SectionLabel("Saved links · hold to remove")
                app.webFavourites.forEach { (name, room) ->
                    Row(
                        Modifier
                            .padding(bottom = 8.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Ink.card)
                            .border(1.dp, Ink.rule, RoundedCornerShape(18.dp))
                            .combinedClickable(onClick = { app.startWebCall(room, name) }, onLongClick = { app.removeWebFavourite(room) })
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("$name's link", style = Type.phrase.copy(fontSize = 18.sp), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("call", style = Type.label.copy(color = Ink.leaf))
                    }
                }
            }
            if (server.isEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("No call server is set. Add its address in Settings > Phone calls.", style = Type.body.copy(color = Ink.turmeric))
            }
            app.callNote?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = Type.body.copy(color = Ink.turmeric))
            }
        }
        Spacer(Modifier.height(10.dp))
        BigButton("Start call", Tone.YES, Modifier.fillMaxWidth().padding(bottom = 4.dp), enabled = server.isNotEmpty()) { app.startWebCall() }
    }
}

// ---------------- web call, waiting for the guest ----------------

/** The QR code and link, until the other person taps Answer. */
@Composable
private fun WebRinging(app: MounaApp) {
    val phase by app.webPhase.collectAsState()
    val room by app.webRoom.collectAsState()
    val context = LocalContext.current
    val url = app.webJoinUrl
    val qr = remember(url) { url?.let { Qr.bitmap(it).asImageBitmap() } }
    val saved = app.webFavourites.any { it.second == room }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Text(
            when (phase) {
                WebPhase.CONNECTING -> "Connecting to the call server…"
                WebPhase.RECONNECTING -> "Reconnecting…"
                WebPhase.GUEST_OPENED -> "They opened the link. Waiting for them to tap Answer…"
                else -> if (app.callWith == "guest") "Waiting for them to open the link…" else "Waiting for ${app.callWith} to open the link…"
            },
            style = Type.phrase.copy(fontSize = 19.sp, lineHeight = 23.sp),
        )
        Spacer(Modifier.height(4.dp))
        Text("Show this code, or send the link. It works in any phone browser.", style = Type.body.copy(fontSize = 13.sp))
        Spacer(Modifier.height(14.dp))
        if (qr != null) {
            Image(
                qr, "QR code of the call link",
                filterQuality = FilterQuality.None,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(app.webShortUrl.orEmpty(), style = Type.mono.copy(fontSize = 14.sp, color = Ink.bone), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("Share link", Tone.PRIMARY, Modifier.weight(1f)) {
                val who = app.callerName.ifBlank { "I" }
                val text = "$who would like to talk with you through Mouna. Mouna speaks for people who can't speak. Open this link and tap Answer: $url"
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, "Share the call link").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            if (!saved && app.webName.isNotBlank()) BigButton("Save as ${app.webName.trim()}'s link", Tone.NO, Modifier.weight(1f)) { app.saveWebFavourite() }
        }
        Spacer(Modifier.height(10.dp))
        BigButton("Cancel", Tone.DANGER, Modifier.fillMaxWidth()) { app.hangUp() }
        Spacer(Modifier.height(16.dp))
    }
}

private val PAD = listOf("123", "456", "789", "*0#")

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Pad(app: MounaApp) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PAD.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { c ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(Ink.card)
                            .combinedClickable(onClick = { app.pressDigit(c) }, onLongClick = { if (c == '0') app.pressDigit('+') }),
                        contentAlignment = Alignment.Center,
                    ) { Text(c.toString(), style = Type.phrase.copy(fontSize = 24.sp)) }
                }
            }
        }
    }
}

// ---------------- on a call ----------------

@Composable
private fun InCall(app: MounaApp, k: Knowledge, bind: (PreviewView) -> Unit) {
    val via by app.voice.via.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(callStatus(app), style = Type.phrase.copy(fontSize = 19.sp, lineHeight = 23.sp), maxLines = 2)
            Text(
                "voice: " + (via ?: "—") + (if (app.callState == CallState.DIALING) " · waiting for an answer" else ""),
                style = Type.label.copy(letterSpacing = 0.sp),
                modifier = Modifier.padding(top = 4.dp, bottom = if (app.usingWeb) 6.dp else 10.dp),
            )
            if (app.usingWeb) GuestMeter(app)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("Hang up", Tone.DANGER, Modifier.weight(1f)) { app.hangUp() }
                BigButton("Intro", Tone.PRIMARY, Modifier.weight(1f)) { app.intro() }
            }
            app.callNote?.let { Text(it, style = Type.body.copy(color = Ink.turmeric, fontSize = 13.sp), modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TabChip("Phrases", app.callTab == CallTab.PHRASES) { app.chooseCallTab(CallTab.PHRASES) }
                TabChip("Mouth · Sign", app.callTab == CallTab.MOUTH) { app.chooseCallTab(CallTab.MOUTH) }
            }
            Spacer(Modifier.height(10.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (app.callTab == CallTab.PHRASES) Phrases(app) else SpeakScreen(app, k, bind)
        }
    }
}

/** "Guest is speaking": a bar that follows the other person's voice, so the person can see they are being heard. */
@Composable
private fun GuestMeter(app: MounaApp) {
    val level by app.guestLevel.collectAsState()
    val phase by app.webPhase.collectAsState()
    val speaking = level > 0.08f
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (speaking) Ink.turmeric else Ink.rule2))
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                phase == WebPhase.RECONNECTING -> "Reconnecting…"
                speaking -> "Guest is speaking"
                else -> "Guest is listening"
            },
            style = Type.label.copy(letterSpacing = 0.sp, color = if (speaking) Ink.turmeric else Ink.mute),
            modifier = Modifier.width(150.dp),
        )
        Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(Ink.rule)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(level.coerceIn(0f, 1f)).background(Ink.turmeric))
        }
    }
}

@Composable
private fun TabChip(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = Type.body.copy(fontSize = 14.sp, color = if (on) Ink.bg else Ink.bone),
        modifier = Modifier
            .clip(CircleShape)
            .then(if (on) Modifier.background(Ink.bone) else Modifier.border(1.dp, Ink.rule2, CircleShape))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Quick phrases and a text box: for anyone who can tap. */
@Composable
private fun Phrases(app: MounaApp) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp)) {
        CallPhrases.quick.chunked(2).forEach { pair ->
            Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { q ->
                    val tone = when (q.id) {
                        "yes" -> Ink.leaf
                        "no" -> Ink.kumkum
                        else -> Ink.rule2
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .height(68.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(if (q.id == "yes" || q.id == "no") tone.copy(alpha = 0.14f) else Ink.card)
                            .border(1.dp, tone, RoundedCornerShape(22.dp))
                            .clickable { app.quick(q) }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(q.say(app.lang), style = Type.phrase.copy(fontSize = 19.sp, lineHeight = 22.sp), textAlign = TextAlign.Center, maxLines = 2) }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        SectionLabel("Or type anything")
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextBox(text, { text = it.take(200) }, "Type what to say", Modifier.weight(1f), singleLine = false)
            Spacer(Modifier.width(10.dp))
            BigButton("Speak", Tone.PRIMARY, enabled = text.isNotBlank()) {
                app.sayTyped(text)
                text = ""
            }
        }
        app.said?.let {
            Spacer(Modifier.height(14.dp))
            Text("“${it.text}”", style = Type.display.copy(fontSize = 22.sp, lineHeight = 26.sp, color = Ink.bone2, fontStyle = FontStyle.Italic), maxLines = 3)
        }
        Spacer(Modifier.height(16.dp))
    }
}
