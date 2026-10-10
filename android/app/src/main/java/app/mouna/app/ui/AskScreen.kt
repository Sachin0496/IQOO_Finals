package app.mouna.app.ui

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
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

/**
 * Ask mode: Mouna asks, the person answers yes or no with anything they have (tap, their switch, a nod).
 * The 30-answer ward tree from the Lab; the caregiver can read the question aloud too.
 */
@Composable
fun AskScreen(app: MounaApp) {
    var round by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    val session = remember(round) { AskSession(TREE.tree) }
    val start = remember(round) { app.switchPresses }
    val startNo = remember(round) { app.noSignals }

    fun yes() { session.yes(); tick++ }
    fun no() { session.no(); tick++ }

    // The person's own movement answers "yes"; "no" is waiting it out or a tap.
    LaunchedEffect(app.switchPresses) { if (app.switchPresses != start && session.result == null) yes() }
    LaunchedEffect(app.noSignals) { if (app.noSignals != startNo && session.result == null) no() }

    val result = tick.let { session.result }
    LaunchedEffect(result) { (result as? AskResult.Answer)?.let { app.speakAsk(it.node) } }
    val face by app.engine.live.collectSlice { it.face } // nods and blinks need the face in view

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        // Both rows keep their height whatever they hold, so nothing below them ever shifts.
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            if (face) Pill("Watching for nods", Ink.leaf) else Pill("Can’t see your face: tap to answer", Ink.mute)
            if (Stage.debug) {
                Spacer(Modifier.width(12.dp))
                Text("ASK · QUESTION ${session.asked}", style = Type.label)
            }
        }
        Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.CenterStart) {
            val crumbs = session.path.map { (it.ask[app.lang.tag] ?: it.ask["en"] ?: it.id).trimEnd('?', '？', ' ') }
            if (crumbs.isNotEmpty() && result == null) {
                Text(crumbs.joinToString(" → ") + " → Yes", style = crumbStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(12.dp))
        when (result) {
            null -> {
                val node = session.current!!
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    Column {
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
                                .clickable { session.back(); tick++ }
                                .padding(horizontal = 14.dp)
                                .wrapContentHeight(Alignment.CenterVertically),
                        )
                    }
                }
            }
            is AskResult.Answer -> Done(app.said?.text ?: "", if (Stage.debug) "Said in ${app.lang.label} · ${session.asked} questions" else "Said aloud") { round++ }
            is AskResult.None -> Done(TREE.none[app.lang.tag] ?: TREE.none["en"] ?: "I want to say something else.", "Nothing on the list matched.") { round++ }
        }
        Spacer(Modifier.height(12.dp))
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
