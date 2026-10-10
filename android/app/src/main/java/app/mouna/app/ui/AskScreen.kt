package app.mouna.app.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mouna.app.MounaApp
import app.mouna.app.engine.Lang
import app.mouna.core.AskResult
import app.mouna.core.AskSession
import app.mouna.core.AskTree

private val TREE by lazy { AskTree.bundled() }

/** Where an Ask question comes from: the ward list, typed by the caregiver, or spoken by them. */
private enum class AskSource(val label: String) { COMMON("Common"), TYPE("Type"), VOICE("Voice") }

/**
 * Ask mode: Mouna asks, the person answers yes or no with anything they have (tap, their switch, a nod).
 * The camera stays on so the person sees themselves and the nods are watched; the question comes from the
 * ward list, or the caregiver's own words by typing or voice. A custom answer is said aloud like any other.
 */
@Composable
fun AskScreen(app: MounaApp, bind: (PreviewView) -> Unit) {
    var round by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    val session = remember(round) { AskSession(TREE.tree) }
    val start = remember(round) { app.switchPresses }
    val startNo = remember(round) { app.noSignals }
    var source by remember { mutableIntStateOf(AskSource.COMMON.ordinal) }
    var draft by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf<String?>(null) }
    var customDone by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var missed by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { app.stopQuestion() } }
    if (source == AskSource.VOICE.ordinal) LaunchedEffect(Unit) { app.preloadVoice() }

    fun yes() { session.yes(); tick++ }
    fun no() { session.no(); tick++ }
    fun answerCustomQ(yes: Boolean) {
        custom?.let { app.answerCustom(it, yes) }
        customDone = true
    }
    val cStart = remember(custom, round) { app.switchPresses }
    val cStartNo = remember(custom, round) { app.noSignals }

    // The person's own movement answers "yes"; "no" is waiting it out or a tap. Only the visible question listens.
    val treeOpen = source == AskSource.COMMON.ordinal
    val customOpen = source != AskSource.COMMON.ordinal && custom != null && !customDone
    LaunchedEffect(app.switchPresses) {
        if (treeOpen && app.switchPresses != start && session.result == null) yes()
        else if (customOpen && app.switchPresses != cStart) answerCustomQ(true)
    }
    LaunchedEffect(app.noSignals) {
        if (treeOpen && app.noSignals != startNo && session.result == null) no()
        else if (customOpen && app.noSignals != cStartNo) answerCustomQ(false)
    }

    val result = tick.let { session.result }
    LaunchedEffect(result) { (result as? AskResult.Answer)?.let { app.speakAsk(it.node) } }
    val face by app.engine.live.collectSlice { it.face } // nods and blinks need the face in view

    Column(Modifier.fillMaxSize()) {
        CameraCard(app.engine.live, bind, Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(168.dp)) {
            Row(
                Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 14.dp, end = 14.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (face) Pill("Watching for nods", Ink.leaf) else Pill("Can’t see your face: tap to answer", Ink.mute)
                if (Stage.debug && treeOpen) Text("ASK · QUESTION ${session.asked}", style = Type.label)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            SourcePill(source, {
                source = it
                app.stopQuestion()
                listening = false
            })
        }
        Spacer(Modifier.height(6.dp))
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)) {
            if (treeOpen) TreeBody(app, session, result, { round++ }, ::yes, ::no, { tick++ })
            else CustomBody(app, draft, { draft = it }, custom, customDone, listening, missed,
                { listening = it }, { missed = it },
                { q -> custom = q; customDone = false; missed = false },
                { custom = null; customDone = false }, ::answerCustomQ)
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Common questions, or the caregiver's own by typing or voice. */
@Composable
private fun SourcePill(picked: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(CircleShape).background(Ink.bg.copy(alpha = 0.78f)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AskSource.entries.forEach { s ->
            val on = picked == s.ordinal
            Text(
                s.label,
                style = (if (on) Type.button.copy(fontSize = 15.sp, color = Ink.bg) else Type.button.copy(fontSize = 15.sp, color = Ink.bone2)),
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (on) Ink.bone else Ink.bg.copy(alpha = 0f))
                    .clickable { onPick(s.ordinal) }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 14.dp)
                    .wrapContentHeight(Alignment.CenterVertically),
            )
        }
    }
}

/** The ward list: crumbs, one big question, Yes / No, then what was said. */
@Composable
private fun ColumnScope.TreeBody(
    app: MounaApp,
    session: AskSession,
    result: AskResult?,
    again: () -> Unit,
    yes: () -> Unit,
    no: () -> Unit,
    advanced: () -> Unit,
) {
    // The crumb row keeps its height whatever it holds, so nothing below it ever shifts.
    Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.CenterStart) {
        val crumbs = session.path.map { (it.ask[app.lang.tag] ?: it.ask["en"] ?: it.id).trimEnd('?', '？', ' ') }
        if (crumbs.isNotEmpty() && result == null) {
            Text(crumbs.joinToString(" → ") + " → Yes", style = crumbStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Spacer(Modifier.height(8.dp))
    when (result) {
        null -> {
            val node = session.current!!
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    // Urgent questions are marked by a turmeric headline, never an alarm red.
                    Text(node.ask[app.lang.tag] ?: node.ask["en"] ?: node.id, style = if (node.urgent) askUrgent else askStyle)
                    if (app.lang != Lang.EN) {
                        Spacer(Modifier.height(10.dp))
                        Text(node.ask["en"] ?: "", style = Type.body)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Answer("No", Icons.Rounded.Close, Ink.card, Ink.bone, Modifier.weight(1f)) { no() }
                Answer("Yes", Icons.Rounded.Check, Ink.leaf, Ink.bg, Modifier.weight(1f)) { yes() }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Nod or blink twice for yes. Shake your head for no.", style = Type.hint, modifier = Modifier.weight(1f))
                if (session.path.isNotEmpty()) {
                    Text(
                        "Back",
                        style = backStyle,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clickable { session.back(); advanced() }
                            .padding(horizontal = 14.dp)
                            .wrapContentHeight(Alignment.CenterVertically),
                    )
                }
            }
        }
        is AskResult.Answer -> Done(app.said?.text ?: "", if (Stage.debug) "Said in ${app.lang.label} · ${session.asked} questions" else "Said aloud") { again() }
        is AskResult.None -> Done(TREE.none[app.lang.tag] ?: TREE.none["en"] ?: "I want to say something else.", "Nothing on the list matched.") { again() }
    }
}

/** The caregiver's own question: type it or say it, then the person answers yes or no the usual way. */
@Composable
private fun ColumnScope.CustomBody(
    app: MounaApp,
    draft: String,
    onDraft: (String) -> Unit,
    custom: String?,
    customDone: Boolean,
    listening: Boolean,
    missed: Boolean,
    onListening: (Boolean) -> Unit,
    onMissed: (Boolean) -> Unit,
    ask: (String) -> Unit,
    discard: () -> Unit,
    answer: (Boolean) -> Unit,
) {
    if (custom == null) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (draft.isEmpty() && !listening) {
                    Text("Ask your own question.", style = askStyle.copy(fontSize = 32.sp, lineHeight = 38.sp))
                    Spacer(Modifier.height(8.dp))
                    Text("The person answers yes or no, the same way: tap, nod, blink, or their movement.", style = Type.body)
                    Spacer(Modifier.height(16.dp))
                }
                TypeRow(draft, onDraft, ask)
                Spacer(Modifier.height(14.dp))
                VoiceRow(app, listening, missed, onListening, onMissed, ask)
            }
        }
    } else if (!customDone) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(custom, style = askStyle.copy(fontSize = 36.sp, lineHeight = 42.sp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Answer("No", Icons.Rounded.Close, Ink.card, Ink.bone, Modifier.weight(1f)) { answer(false) }
            Answer("Yes", Icons.Rounded.Check, Ink.leaf, Ink.bg, Modifier.weight(1f)) { answer(true) }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Nod or blink twice for yes. Shake your head for no.", style = Type.hint, modifier = Modifier.weight(1f))
            Text(
                "Not this",
                style = backStyle,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable { discard() }
                    .padding(horizontal = 14.dp)
                    .wrapContentHeight(Alignment.CenterVertically),
            )
        }
    } else {
        Done(app.said?.text ?: custom, "Said aloud") { discard() }
    }
}

/** The caregiver types the question; it is shown as-is, in whatever language it is written. */
@Composable
private fun TypeRow(draft: String, onDraft: (String) -> Unit, ask: (String) -> Unit) {
    SectionLabel("Type the question")
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextBox(draft, { onDraft(it.take(140)) }, "Are you cold?", Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        BigButton("Ask", Tone.PRIMARY, enabled = draft.isNotBlank()) {
            ask(draft.trim())
            onDraft("")
        }
    }
}

/** Or says it: one utterance, transcribed on the phone, never leaving it. */
@Composable
private fun VoiceRow(
    app: MounaApp,
    listening: Boolean,
    missed: Boolean,
    onListening: (Boolean) -> Unit,
    onMissed: (Boolean) -> Unit,
    ask: (String) -> Unit,
) {
    SectionLabel("Or say it")
    if (!app.hearing.ready) {
        Text(
            if (app.voiceLoading) "Getting the voice model…" else "Voice typing needs the voice model: ${app.hearing.status}",
            style = Type.body.copy(fontSize = 13.sp),
        )
    } else if (!listening) {
        BigButton("Listen", Tone.PRIMARY, Modifier.fillMaxWidth()) {
            onMissed(false)
            onListening(true)
            app.listenQuestion { text ->
                onListening(false)
                if (text.isBlank()) onMissed(true) else ask(text)
            }
        }
        if (missed) {
            Spacer(Modifier.height(8.dp))
            Text("Didn't catch that. Try again, closer to the phone.", style = Type.body.copy(color = Ink.turmeric))
        }
    } else {
        Text("Listening… ask now.", style = Type.phrase)
        Spacer(Modifier.height(10.dp))
        BigButton("Stop", Tone.QUIET, Modifier.fillMaxWidth()) {
            app.stopQuestion()
            onListening(false)
        }
    }
}

private val crumbStyle get() = Type.mono.copy(fontSize = 14.sp, color = Ink.bone2)
private val backStyle get() = Type.mono.copy(color = Ink.bone2, fontSize = 14.sp, textDecoration = TextDecoration.Underline)
private val askStyle get() = Type.display.copy(fontSize = 44.sp, lineHeight = 50.sp)
private val askUrgent get() = askStyle.copy(color = Ink.turmeric)
private val doneStyle get() = Type.display.copy(fontSize = 40.sp, lineHeight = 46.sp)
private val doneNote get() = Type.hint.copy(fontStyle = FontStyle.Italic)

@Composable
private fun Answer(text: String, icon: ImageVector, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(150.dp).clip(RoundedCornerShape(30.dp)).background(bg).clickable(onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(6.dp))
        Text(text, style = Type.title.copy(color = fg))
    }
}

@Composable
private fun ColumnScope.Done(said: String, note: String, again: () -> Unit) {
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Column {
            Text(said, style = doneStyle)
            Spacer(Modifier.height(10.dp))
            Text(note, style = doneNote)
        }
    }
    BigButton("Ask again", Tone.PRIMARY, Modifier.fillMaxWidth(), onClick = again)
}
