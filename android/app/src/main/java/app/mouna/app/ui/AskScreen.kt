package app.mouna.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        val path = session.path.joinToString("  ›  ") { it.ask[app.lang.tag] ?: it.ask["en"] ?: it.id }
        Text("ASK · QUESTION ${session.asked}", style = Type.label)
        if (path.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(path, style = Type.mono.copy(fontSize = 12.sp, color = Ink.bone2), maxLines = 1)
        }
        Spacer(Modifier.height(20.dp))
        when (result) {
            null -> {
                val node = session.current!!
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    Column {
                        Text(node.ask[app.lang.tag] ?: node.ask["en"] ?: node.id, style = Type.display.copy(fontSize = 44.sp, lineHeight = 50.sp, color = if (node.urgent) Ink.kumkum else Ink.bone))
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
                Spacer(Modifier.height(12.dp))
                Row {
                    if (session.path.isNotEmpty()) {
                        Text("Back", style = Type.mono.copy(color = Ink.bone2, textDecoration = TextDecoration.Underline), modifier = Modifier.clickable { session.back(); tick++ }.padding(8.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Text("Nod or blink twice = yes · shake = no", style = Type.mono.copy(fontSize = 12.sp, color = Ink.mute), modifier = Modifier.padding(8.dp))
                }
            }
            is AskResult.Answer -> Done(app, app.said?.text ?: "", "Said in ${app.lang.label} · ${session.asked} questions") { round++ }
            is AskResult.None -> Done(app, TREE.none[app.lang.tag] ?: TREE.none["en"] ?: "Not in the list.", "Try the pictures, or teach it as a new phrase") { round++ }
        }
        Spacer(Modifier.height(12.dp))
    }
}

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
private fun androidx.compose.foundation.layout.ColumnScope.Done(app: MounaApp, said: String, note: String, again: () -> Unit) {
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Column {
            Text(said, style = Type.display.copy(fontSize = 40.sp, lineHeight = 46.sp))
            Spacer(Modifier.height(10.dp))
            Text(note, style = Type.mono.copy(fontSize = 12.sp, color = Ink.mute, fontStyle = FontStyle.Italic))
        }
    }
    BigButton("Ask again", Tone.PRIMARY, Modifier.fillMaxWidth(), onClick = again)
}
