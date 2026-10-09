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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mouna.app.MounaApp
import app.mouna.app.Prompt
import app.mouna.app.Screen
import app.mouna.app.engine.Knowledge
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

/** Free talk: the sentence Mouna read, the other readings below; nothing is spoken until the person says yes. */
@Composable
private fun ColumnScope.DidYouMean(app: MounaApp, p: Prompt.Read) {
    Header(app, "FREE TALK: READ FROM YOUR LIPS", "Did you mean…?")
    Text("“${p.sentences[p.index]}”", style = Type.display.copy(fontSize = 34.sp, lineHeight = 40.sp, color = Ink.turmeric))
    if (p.personal.getOrElse(p.index) { false }) Text("ONE OF YOUR SENTENCES", style = Type.label.copy(color = Ink.leaf))
    val others = p.sentences.indices.filter { it != p.index }
    if (others.isNotEmpty()) {
        Spacer(Modifier.height(20.dp))
        Text("Or…", style = Type.label)
        Spacer(Modifier.height(8.dp))
        others.forEach { i ->
            val s = p.sentences[i]
            Text(
                if (p.personal.getOrElse(i) { false }) "$s  ·  yours" else s,
                style = Type.body.copy(fontSize = 20.sp),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { app.sayRead(s) }.padding(vertical = 10.dp),
            )
        }
    }
    Spacer(Modifier.weight(1f))
    Hint("Nod or blink twice to say it, shake your head for the next reading, or tap another.")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BigButton("No", Tone.NO, Modifier.weight(1f)) { app.nextRead() }
        BigButton("Yes, say it", Tone.YES, Modifier.weight(1.4f)) { app.sayRead(p.sentences[p.index]) }
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

private val signWord = Type.display.copy(fontSize = 30.sp)

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
