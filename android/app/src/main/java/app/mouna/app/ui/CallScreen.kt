package app.mouna.app.ui

import android.content.Intent
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.launch
import app.mouna.app.Screen
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
import app.mouna.app.Channel
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

/** "On call with Amma" / "Calling Amma…": the big line of the in-call header. */
@Composable
private fun callTitle(app: MounaApp): String {
    val who = app.callWith.ifEmpty { "call" }
    return if (app.callState == CallState.DIALING) "Calling $who…" else "On call with $who"
}

/** "00:04 · ON SPEAKER": the small line under it. */
@Composable
private fun callClock(app: MounaApp): String {
    val now by produceNow(app.callState)
    return when (app.callState) {
        CallState.ACTIVE -> Phones.clock(((now - app.callSince) / 1000).toInt().coerceAtLeast(0)) + " · ON SPEAKER"
        CallState.DIALING -> "WAITING FOR AN ANSWER"
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
        ModeSwitch(
            listOf(CallMode.PHONE to "Phone number", CallMode.WEB to "Web link"),
            app.callMode,
            app::chooseCallMode,
            Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp),
            fill = true,
        )
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
                ) { Icon(MounaIcons.Backspace, "Delete", tint = Ink.bone2) }
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
                Text("No call server: web link calls are off. Add it in Settings › Advanced › Web calls.", style = Type.body.copy(color = Ink.turmeric))
                Spacer(Modifier.height(4.dp))
                Text(
                    "Open Advanced settings",
                    style = Type.mono.copy(fontSize = 12.sp, color = Ink.turmeric, textDecoration = TextDecoration.Underline),
                    modifier = Modifier.clickable { Stage.debug = true; app.go(Screen.SETTINGS) }.padding(vertical = 8.dp),
                )
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
        val (host, path) = linkLines(app.webShortUrl.orEmpty())
        Text(host, style = Type.mono.copy(fontSize = linkHostSize(host.length).sp, color = Ink.bone2), textAlign = TextAlign.Center, softWrap = false, maxLines = 1, modifier = Modifier.fillMaxWidth())
        if (path.isNotEmpty()) Text(path, style = Type.mono.copy(fontSize = 22.sp, color = Ink.bone), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
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
        BigButton("Cancel", Tone.QUIET, Modifier.fillMaxWidth()) { app.hangUp() }
        Spacer(Modifier.height(16.dp))
    }
}

/** The join link as two lines: the server on one, "/c/ROOM" on the next, so it never breaks inside a domain name. */
internal fun linkLines(short: String): Pair<String, String> {
    val i = short.indexOf("/c/")
    return if (i < 0) short to "" else short.substring(0, i) to short.substring(i)
}

/** Monospace size (sp) for a server name of [chars] characters to stay on one 320 dp line. */
internal fun linkHostSize(chars: Int): Int = (310 / (0.6 * chars.coerceAtLeast(1))).toInt().coerceIn(10, 15)

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
    var hint by remember { mutableStateOf(false) }
    LaunchedEffect(hint) { if (hint) { delay(2200); hint = false } }
    val talk = when {
        app.callTab == CallTab.PHRASES -> Talk.PHRASES
        app.callTab == CallTab.TYPE -> Talk.TYPE
        app.channel == Channel.SIGN -> Talk.SIGN
        else -> Talk.LIPS
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(callTitle(app), style = Type.phrase.copy(fontSize = 20.sp, lineHeight = 24.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(callClock(app), style = Type.label.copy(fontSize = 12.sp, color = Ink.bone2), modifier = Modifier.padding(top = 2.dp))
                }
                Spacer(Modifier.width(12.dp))
                HoldToHangUp(onHangUp = { app.hangUp() }, onEarlyRelease = { hint = true })
            }
            if (Stage.debug) {
                Text("voice: " + (via ?: "—"), style = Type.label.copy(letterSpacing = 0.sp), modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(10.dp))
            if (app.usingWeb) GuestMeter(app)
            BigButton("Introduce me", Tone.PRIMARY, Modifier.fillMaxWidth().height(54.dp)) { app.intro() }
            if (hint) Text("Hold the button to hang up", style = Type.body.copy(color = Ink.turmeric, fontSize = 13.sp), modifier = Modifier.padding(top = 8.dp))
            app.callNote?.let { Text(it, style = Type.body.copy(color = Ink.turmeric, fontSize = 13.sp), modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(12.dp))
            // One switch for every way to talk on a call: no second switch inside the camera.
            ModeSwitch(Talk.entries.map { it to it.label }, talk, { t ->
                t.channel?.let { app.chooseChannel(it) }
                app.chooseCallTab(t.tab)
            }, fill = true)
            Spacer(Modifier.height(14.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (talk) {
                Talk.PHRASES -> Phrases(app)
                Talk.TYPE -> TypeToSay(app)
                else -> SpeakScreen(app, k, bind, inCall = true)
            }
        }
    }
}

/** The ways to talk on a call, in switch order: mouth, sign, type or tap a phrase. [tab] is the lower half it shows. */
private enum class Talk(val label: String, val channel: Channel?, val tab: CallTab) {
    LIPS("Lips", Channel.LIPS, CallTab.MOUTH),
    SIGN("Sign", Channel.SIGN, CallTab.MOUTH),
    TYPE("Type", null, CallTab.TYPE),
    PHRASES("Phrases", null, CallTab.PHRASES),
}

/** How long the button must be held to end a call: long enough that a brush or a stray tap does nothing. */
private const val HOLD_MS = 600

/** A small, separate hang-up: it fills while held and only ends the call when full. A quick tap just explains itself. */
@Composable
private fun HoldToHangUp(onHangUp: () -> Unit, onEarlyRelease: () -> Unit) {
    val fill = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .height(48.dp)
            .clip(CircleShape)
            .border(1.dp, Ink.kumkum, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val job = scope.launch {
                        fill.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
                        onHangUp()
                    }
                    tryAwaitRelease()
                    if (fill.value < 1f) {
                        job.cancel()
                        scope.launch { fill.snapTo(0f) }
                        onEarlyRelease()
                    }
                })
            }
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.matchParentSize().fillMaxHeight()) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fill.value).background(Ink.kumkum.copy(alpha = 0.55f)))
        }
        Text("Hang up", style = Type.button.copy(fontSize = 15.sp, color = Ink.bone))
    }
}

/** "Amma is speaking": a bar that follows the other person's voice, so the person can see they are being heard. */
@Composable
private fun GuestMeter(app: MounaApp) {
    val level by app.guestLevel.collectAsState()
    val phase by app.webPhase.collectAsState()
    val speaking = level > 0.08f
    val who = app.callWith.takeIf { it.isNotBlank() && it != "guest" }
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (speaking) Ink.turmeric else Ink.rule2))
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                phase == WebPhase.RECONNECTING -> "Reconnecting…"
                speaking -> guestLine(who, true)
                else -> guestLine(who, false)
            },
            style = Type.label.copy(letterSpacing = 0.sp, fontSize = 12.sp, color = if (speaking) Ink.turmeric else Ink.bone2),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.6f),
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(0.4f).height(6.dp).clip(CircleShape).background(Ink.rule)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(level.coerceIn(0f, 1f)).background(Ink.turmeric))
        }
    }
}

/** "Amma is speaking" by name, "They're speaking" when the call has no name. */
internal fun guestLine(name: String?, speaking: Boolean): String {
    val what = if (speaking) "speaking" else "listening"
    return if (name.isNullOrBlank()) "They're $what" else "$name is $what"
}

/** Quick phrases: for anyone who can tap. */
@Composable
private fun Phrases(app: MounaApp) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp)) {
        CallPhrases.quick.chunked(2).forEach { pair ->
            // Both buttons of a row are as tall as the taller one, so a long Tamil phrase never makes a ragged row.
            Row(Modifier.padding(bottom = 10.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { q ->
                    val tone = when (q.id) {
                        "yes" -> Ink.leaf
                        "no" -> Ink.kumkum
                        else -> Ink.rule2
                    }
                    val say = q.say(app.lang)
                    val size = CallPhrases.quickSize(say)
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .heightIn(min = 68.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(if (q.id == "yes" || q.id == "no") tone.copy(alpha = 0.14f) else Ink.card)
                            .border(1.dp, tone, RoundedCornerShape(22.dp))
                            .clickable { app.quick(q) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            say,
                            style = Type.phrase.copy(fontSize = size.sp, lineHeight = (size * 1.25f).sp),
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        LastSaid(app)
        Spacer(Modifier.height(16.dp))
    }
}

/** Typing on a call: a roomy box, one big Speak button, and the last thing said. */
@Composable
private fun TypeToSay(app: MounaApp) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        SectionLabel("Type what to say")
        TextBox(text, { text = it.take(200) }, "Type anything", Modifier.fillMaxWidth().heightIn(min = 132.dp), singleLine = false)
        Spacer(Modifier.height(12.dp))
        BigButton("Speak", Tone.PRIMARY, Modifier.fillMaxWidth(), enabled = text.isNotBlank()) {
            app.sayTyped(text)
            text = ""
        }
        LastSaid(app)
        Spacer(Modifier.height(16.dp))
    }
}

/** The last thing Mouna said into the call, so the person can check it went. */
@Composable
private fun LastSaid(app: MounaApp) {
    app.said?.let {
        Spacer(Modifier.height(14.dp))
        Text("“${it.text}”", style = Type.display.copy(fontSize = 22.sp, lineHeight = 26.sp, color = Ink.bone2, fontStyle = FontStyle.Italic), maxLines = 3)
    }
}
