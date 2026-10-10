package app.mouna.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mouna.app.MounaApp
import app.mouna.app.Prompt
import app.mouna.app.Screen
import app.mouna.app.engine.Knowledge
import app.mouna.app.engine.PhoneContact
import app.mouna.app.engine.PhoneFlow
import app.mouna.app.engine.PhoneVerb
import app.mouna.app.engine.Phones
import app.mouna.core.DecisionKind
import app.mouna.core.Zone
import kotlinx.coroutines.delay

private const val NONE = "__none__"
private const val SCAN_MS = 1600L

/**
 * Mouna wasn't sure enough to speak, so it asks the cheapest question that still gets the answer:
 * one picture to confirm, two to look between, three or four to pick from, or the yes/no tree.
 */
@Composable
fun PromptOverlay(app: MounaApp, prompt: Prompt, k: Knowledge) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Ink.bg)
            .clickable(enabled = false) {}
            .systemBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            when (prompt) {
                is Prompt.PickAny -> Pick(app, k, "What did you mean?", "PICK THE RIGHT ONE", k.pack, noneFirst = false)
                is Prompt.Heard -> SayClearly(app, prompt.text)
                is Prompt.Signed -> DidYouSign(app, prompt.words)
                is Prompt.Read -> DidYouMean(app, prompt)
                is Prompt.PhoneWhat -> PhoneWhat(app, k)
                is Prompt.PhoneWho -> PhoneWho(app, prompt, k)
                is Prompt.PhoneBody -> PhoneBody(app, prompt, k)
                is Prompt.PhoneConfirm -> PhoneConfirm(app, prompt, k)
                is Prompt.FromCore -> {
                    val d = prompt.d
                    when (d.kind) {
                        DecisionKind.CONFIRM -> {
                            val first = d.options.firstOrNull()
                            if (first != null) Confirm(app, first, d.why, k.switchReady) else NotTaught(app, k, emptyList(), d.maybeNone)
                        }
                        DecisionKind.RESCUE -> Rescue(app, d.options, k)
                        DecisionKind.CHOOSE -> Pick(app, k, "Which one?", "A FEW ARE POSSIBLE", d.options, d.maybeNone)
                        DecisionKind.ASK -> Pick(app, k, "Which one?", "MANY ARE POSSIBLE", d.options, d.maybeNone, offerAsk = true)
                        DecisionKind.NOT_TAUGHT -> NotTaught(app, k, d.options, d.maybeNone)
                        DecisionKind.SPEAK -> Unit
                    }
                }
            }
        }
    }
}

/** A small label with the close button on its row (so it never lands on the gear), then the title. */
@Composable
private fun ColumnScope.Header(app: MounaApp, label: String, title: String) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.label, modifier = Modifier.weight(1f))
        IconButtonSoft(Icons.Rounded.Close, "Close") { app.close() }
    }
    Spacer(Modifier.height(4.dp))
    Text(title, style = Type.display)
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun ColumnScope.Confirm(app: MounaApp, id: String, why: String, switchReady: Boolean) {
    val action = why.startsWith("action")
    Header(app, if (action) "AN ACTION: ALWAYS CONFIRMED" else "FAIRLY SURE", "Did you mean…?")
    app.phrases[id]?.let { PhraseTile(it, app.lang, Modifier.fillMaxWidth().height(300.dp), big = true, selected = true, accent = if (action) Ink.kumkum else Ink.turmeric) }
    Spacer(Modifier.weight(1f))
    Hint(if (switchReady) "Use your movement or nod for yes, shake your head for no." else "Nod or blink twice for yes, shake your head for no.")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BigButton("No", Tone.NO, Modifier.weight(1f)) { app.wrong() }
        BigButton("Yes, say it", Tone.YES, Modifier.weight(1.4f)) { app.choose(id, "confirm") }
    }
}

/** Two pictures, left and right: the person looks at one (and holds, or uses their switch). */
@Composable
private fun ColumnScope.Rescue(app: MounaApp, options: List<String>, k: Knowledge) {
    val zone by app.engine.live.collectSlice { it.gazeZone } // not the whole 30 Hz frame: only the gaze side
    Header(app, "TWO LOOK ALIKE", "Which one?")
    Row(Modifier.fillMaxWidth().height(330.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        options.take(2).forEachIndexed { i, id ->
            val side = if (i == 0) Zone.LEFT else Zone.RIGHT
            app.phrases[id]?.let {
                PhraseTile(it, app.lang, Modifier.weight(1f).fillMaxSize(), big = true, selected = zone == side) { app.choose(id, "touch") }
            }
        }
    }
    Spacer(Modifier.weight(1f))
    Hint(
        when {
            !k.gazeReady -> "Tap the one you mean, or set up eyes in Settings to choose by looking."
            k.switchReady -> "Look at one, then use your movement."
            else -> "Look at one and hold your gaze."
        },
    )
    BigButton("Neither", Tone.NO, Modifier.fillMaxWidth()) { app.wrong() }
}

/** Three or four pictures; with a personal switch, Mouna highlights each in turn and the switch picks. */
@Composable
private fun ColumnScope.Pick(
    app: MounaApp,
    k: Knowledge,
    title: String,
    label: String,
    options: List<String>,
    noneFirst: Boolean,
    offerAsk: Boolean = false,
) {
    val items = if (noneFirst) listOf(NONE) + options else options + NONE
    val hi = scanning(app, items, k.switchReady)
    Header(app, label, title)
    val tileH = gridTileHeight(app.lang)
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { id ->
                    val i = items.indexOf(id)
                    if (id == NONE) {
                        NoneTile(Modifier.weight(1f).height(tileH), i == hi) { app.noneOfThese() }
                    } else {
                        app.phrases[id]?.let {
                            PhraseTile(it, app.lang, Modifier.weight(1f).height(tileH), roomy = true, selected = i == hi) { app.choose(id, "touch") }
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    if (k.switchReady) Hint("Use your movement when the right one lights up.")
    if (offerAsk) BigButton("Ask me yes / no instead", Tone.PRIMARY, Modifier.fillMaxWidth()) { app.go(Screen.ASK) }
}

/** Sign mode wasn't sure: the likeliest ISL words, as big buttons. */
@Composable
private fun ColumnScope.DidYouSign(app: MounaApp, words: List<String>) {
    Header(app, "NOT SURE", "Did you sign…?")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        words.forEach { w ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .border(1.dp, Ink.rule2, RoundedCornerShape(22.dp))
                    .clickable { app.sayWord(w) }
                    .padding(horizontal = 22.dp, vertical = 20.dp),
            ) { Text(w.replaceFirstChar { it.uppercase() }, style = signWord) }
        }
    }
    Spacer(Modifier.weight(1f))
    BigButton("None of these", Tone.NO, Modifier.fillMaxWidth()) { app.close() }
}

/** Voice mode: words that are none of the person's phrases. Mouna offers to say exactly those words, clearly. */
@Composable
private fun ColumnScope.SayClearly(app: MounaApp, text: String) {
    Header(app, "I HEARD", "Did you say…?")
    Text("“$text”", style = Type.display.copy(fontSize = 34.sp, lineHeight = 40.sp, color = Ink.turmeric))
    Spacer(Modifier.weight(1f))
    Hint("Mouna will say these words clearly for you.")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BigButton("No", Tone.NO, Modifier.weight(1f)) { app.close() }
        BigButton("Yes, say it", Tone.YES, Modifier.weight(1.4f)) { app.sayHeard(text) }
    }
}

/** Lips: the sentence (or taught word) Mouna read, the others below; nothing is spoken until the person says yes. */
@Composable
private fun ColumnScope.DidYouMean(app: MounaApp, p: Prompt.Read) {
    Header(app, "READ FROM YOUR LIPS", "Did you mean…?")
    val top = p.options[p.index]
    Text("“${top.text}”", style = Type.display.copy(fontSize = 34.sp, lineHeight = 40.sp, color = Ink.turmeric))
    when {
        top.word != null -> Text("A WORD YOU TAUGHT", style = Type.label.copy(color = Ink.leaf))
        top.personal -> Text("ONE OF YOUR SENTENCES", style = Type.label.copy(color = Ink.leaf))
    }
    val others = p.options.indices.filter { it != p.index }
    if (others.isNotEmpty()) {
        Spacer(Modifier.height(20.dp))
        Text("Or…", style = Type.label)
        Spacer(Modifier.height(8.dp))
        others.forEach { i ->
            val s = p.options[i]
            Text(
                when {
                    s.word != null -> "${s.text}  ·  your word"
                    s.personal -> "${s.text}  ·  yours"
                    else -> s.text
                },
                style = Type.body.copy(fontSize = 20.sp),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { app.sayRead(s) }.padding(vertical = 10.dp),
            )
        }
    }
    Spacer(Modifier.weight(1f))
    Hint("Nod or blink twice to say it, shake your head for the next reading, or tap another.")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BigButton("No", Tone.NO, Modifier.weight(1f)) { app.nextRead() }
        BigButton("Yes, say it", Tone.YES, Modifier.weight(1.4f)) { app.sayRead(top) }
    }
}

/** One pick on a phone screen: a name, and a line under it (a number, "web call") when there is one. */
private class PhoneOption(val title: String, val sub: String? = null)

/** Two big tiles side by side, as Rescue: the one under the gaze is lit, and a tap picks either. One tile keeps half the row. */
@Composable
private fun ColumnScope.PhonePair(app: MounaApp, options: List<PhoneOption>, onPick: (Int) -> Unit) {
    val zone by app.engine.live.collectSlice { it.gazeZone } // not the whole 30 Hz frame: only the gaze side
    Row(Modifier.fillMaxWidth().height(330.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        options.take(2).forEachIndexed { i, o ->
            val side = if (i == 0) Zone.LEFT else Zone.RIGHT
            PhoneTile(o, selected = zone == side, Modifier.weight(1f).fillMaxSize()) { onPick(i) }
        }
        if (options.size == 1) Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun PhoneTile(o: PhoneOption, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Ink.card)
            .border(if (selected) 2.dp else 1.dp, if (selected) Ink.turmeric else Ink.rule2, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(o.title, style = Type.display.copy(fontSize = 30.sp, lineHeight = 34.sp), textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
        o.sub?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = Type.mono.copy(fontSize = 15.sp), textAlign = TextAlign.Center)
        }
    }
}

/** Call or message: two big tiles, the call on the left and the message on the right. */
@Composable
private fun ColumnScope.PhoneWhat(app: MounaApp, k: Knowledge) {
    Header(app, "PHONE", "Call or message?")
    PhonePair(app, listOf(PhoneOption("Call"), PhoneOption("Message"))) { app.phonePick(it) }
    Spacer(Modifier.weight(1f))
    Hint(pickHint(k, more = false))
    BigButton("Cancel", Tone.NO, Modifier.fillMaxWidth()) { app.close() }
}

/** Who to call or message: two people at a time, from the phone book (the Call screen's favourites, both kinds). */
@Composable
private fun ColumnScope.PhoneWho(app: MounaApp, p: Prompt.PhoneWho, k: Knowledge) {
    Header(app, if (p.verb == PhoneVerb.CALL) "CALL" else "MESSAGE", "Who?")
    if (p.people.isEmpty()) {
        Text("No one saved yet.", style = Type.body.copy(fontSize = 18.sp))
        Spacer(Modifier.weight(1f))
        BigButton("Add people", Tone.PRIMARY, Modifier.fillMaxWidth()) { app.go(Screen.CALL) }
        BigButton("Cancel", Tone.NO, Modifier.fillMaxWidth()) { app.close() }
        return
    }
    val more = p.people.size > 2
    PhonePair(app, PhoneFlow.pair(p.people, p.page).map { PhoneOption(it.name, personLine(p.verb, it)) }) { app.phonePick(it) }
    Spacer(Modifier.weight(1f))
    Hint(pickHint(k, more))
    MoreOrCancel(app, more)
}

/** The line under a name: "web call" for a call by web link, else the number, else that there is none. */
private fun personLine(verb: PhoneVerb, c: PhoneContact): String = when {
    verb == PhoneVerb.CALL && c.room != null -> "web call"
    c.number != null -> Phones.pretty(c.number)
    else -> "no number saved"
}

/** The text of a message, two choices at a time: what Mouna last said, then the quick messages. */
@Composable
private fun ColumnScope.PhoneBody(app: MounaApp, p: Prompt.PhoneBody, k: Knowledge) {
    val more = p.options.size > 2
    Header(app, "MESSAGE ${p.c.name.uppercase()}", "What should it say?")
    PhonePair(app, PhoneFlow.pair(p.options, p.page).map { PhoneOption(it) }) { app.phonePick(it) }
    Spacer(Modifier.weight(1f))
    Hint(pickHint(k, more))
    MoreOrCancel(app, more)
}

/** The one action, always confirmed. Nothing is dialled or sent until the person says yes here. */
@Composable
private fun ColumnScope.PhoneConfirm(app: MounaApp, p: Prompt.PhoneConfirm, k: Knowledge) {
    val call = p.verb == PhoneVerb.CALL
    Header(app, "AN ACTION: ALWAYS CONFIRMED", if (call) "Call ${p.c.name}?" else "Message ${p.c.name}?")
    p.body?.let { Text("“$it”", style = Type.display.copy(fontSize = 34.sp, lineHeight = 40.sp, color = Ink.kumkum)) }
    Spacer(Modifier.weight(1f))
    if (!call) Text("You'll tap Send in WhatsApp/Messages.", style = Type.body.copy(fontSize = 15.sp))
    Hint(if (k.switchReady) "Use your movement or nod for yes, shake your head for no." else "Nod or blink twice for yes, shake your head for no.")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BigButton("No", Tone.NO, Modifier.weight(1f)) { app.phoneCancel() }
        BigButton(if (call) "Yes, call" else "Yes, open message", Tone.YES, Modifier.weight(1.4f)) { app.phoneGo() }
    }
}

/** How to pick on a phone screen: by look (held), by look then the person's movement, or by tap when eyes are not set up. */
private fun pickHint(k: Knowledge, more: Boolean): String {
    val pick = when {
        !k.gazeReady -> "Tap one."
        k.switchReady -> "Look at one, then use your movement."
        else -> "Look at one and hold your gaze, or tap."
    }
    return if (more) "$pick Shake your head for more." else pick
}

/** More (only with more to see) and Cancel, side by side. */
@Composable
private fun ColumnScope.MoreOrCancel(app: MounaApp, more: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (more) BigButton("More", Tone.PRIMARY, Modifier.weight(1f)) { app.phoneNext() }
        BigButton("Cancel", Tone.NO, Modifier.weight(1f)) { app.close() }
    }
}

@Composable
private fun ColumnScope.NotTaught(app: MounaApp, k: Knowledge, maybe: List<String>, maybeNone: Boolean) {
    Header(app, "I DIDN’T CATCH THAT", "Not one of your phrases")
    if (maybe.isNotEmpty()) {
        Text("Maybe one of these?", style = Type.body)
        Spacer(Modifier.height(10.dp))
        val tileH = gridTileHeight(app.lang)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            maybe.take(2).forEach { id ->
                app.phrases[id]?.let { PhraseTile(it, app.lang, Modifier.weight(1f).height(tileH), roomy = true) { app.choose(id, "touch") } }
            }
        }
    }
    Spacer(Modifier.weight(1f))
    if (maybeNone) Hint("This looked like one of your “none of these” examples.")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BigButton("Ask me yes / no", Tone.PRIMARY, Modifier.fillMaxWidth()) { app.go(Screen.ASK) }
        BigButton("Something else", Tone.NO, Modifier.fillMaxWidth()) { app.noneOfThese() }
    }
}

private val signWord get() = Type.display.copy(fontSize = 30.sp)

@Composable
private fun NoneTile(modifier: Modifier, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) Ink.turmeric else Ink.rule2, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("None of these", style = Type.phrase.copy(fontStyle = FontStyle.Italic, color = Ink.bone2), textAlign = TextAlign.Center)
    }
}

@Composable
private fun ColumnScope.Hint(text: String) {
    Text(text, style = Type.hint, modifier = Modifier.padding(bottom = 12.dp))
}

/** Switch scanning: the highlight steps through [items]; a press of the person's switch picks the lit one. */
@Composable
private fun scanning(app: MounaApp, items: List<String>, on: Boolean): Int {
    var hi by remember(items) { mutableIntStateOf(if (on) 0 else -1) }
    val start = remember(items) { app.switchPresses }
    LaunchedEffect(items, on) {
        if (!on) return@LaunchedEffect
        while (true) {
            delay(SCAN_MS)
            hi = (hi + 1) % items.size
        }
    }
    LaunchedEffect(app.switchPresses) {
        if (on && app.switchPresses != start && hi >= 0) {
            val id = items[hi]
            if (id == NONE) app.noneOfThese() else app.choose(id, "switch")
        }
    }
    return hi
}
