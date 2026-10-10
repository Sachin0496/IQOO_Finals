package app.mouna.app.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mouna.app.MounaApp
import app.mouna.app.engine.Knowledge
import app.mouna.app.engine.Words
import app.mouna.core.CoreConstants

/**
 * Teach Lips a word it can't read: a name, a Kannada or Tamil word, a family word. Type what Mouna should say, mouth
 * it three times; from then on it is offered in "Did you mean…?" when Lips sees it (Words.kt). Every confirmation
 * teaches it once more, up to five examples.
 */
@Composable
fun WordsScreen(app: MounaApp, k: Knowledge, bind: (PreviewView) -> Unit) {
    var text by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf<String?>(null) } // the word being confirmed for removal
    val teaching = app.wordTeaching
    val armed = k.teachingWord != null
    val names = k.words.toMap()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Text("Teach Lips a word", style = Type.title)
        Spacer(Modifier.height(6.dp))
        Text(
            "For words Lips can’t read: a name, a word in Kannada or Tamil, a word only your family uses. " +
                "Type what Mouna should say, then mouth it ${Words.SHOTS} times.",
            style = Type.body,
        )
        Spacer(Modifier.height(18.dp))

        Card {
            Column {
                CameraCard(app.engine.live, bind, Modifier.fillMaxWidth().height(210.dp)) { st ->
                    Box(Modifier.align(Alignment.TopStart).padding(12.dp)) {
                        when {
                            !k.ready -> Pill("Getting ready…", Ink.mute, pulse = true)
                            teaching == null -> Pill("Ready", Ink.leaf)
                            !st.face -> Pill("Looking for your face")
                            st.hearing -> Pill("Reading your lips…", Ink.turmeric, pulse = true)
                            armed -> Pill("Mouth it now", Ink.turmeric, pulse = true)
                            else -> Pill("Got it", Ink.leaf)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (teaching != null) {
                    val n = k.wordCounts[teaching] ?: 0
                    SectionLabel("Mouth it, then keep your lips still")
                    Text("“${names[teaching] ?: ""}”", style = Type.phrase.copy(fontSize = 26.sp))
                    Spacer(Modifier.height(8.dp))
                    Dots(minOf(n, Words.SHOTS), Words.SHOTS, Ink.leaf)
                    app.wordNote?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = noteStyle)
                    }
                    Spacer(Modifier.height(16.dp))
                    BigButton("Stop", Tone.NO, Modifier.fillMaxWidth()) { app.stopTeachingWord() }
                } else {
                    app.wordNote?.let {
                        Text("Learned. Lips will offer it when it sees it.", style = noteStyle)
                        Spacer(Modifier.height(12.dp))
                    }
                    SectionLabel("New word")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it.take(60) },
                            placeholder = { Text("Maadhav, ನೀರು", style = Type.phrase.copy(color = Ink.mute, fontSize = 17.sp)) },
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
                        BigButton("Teach", Tone.PRIMARY, enabled = text.isNotBlank() && k.ready) {
                            val id = app.addWord(text)
                            text = ""
                            app.teachWord(id)
                        }
                    }
                }
            }
        }

        if (k.words.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Card {
                Column {
                    SectionLabel("Your words")
                    k.words.forEach { (id, word) ->
                        val n = k.wordCounts[id] ?: 0
                        if (asking == id) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Remove “$word” and what Mouna learned for it?", style = rowStyle.copy(fontSize = 14.sp), modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                ChipButton("Keep", Ink.bone2) { asking = null }
                                Spacer(Modifier.width(6.dp))
                                ChipButton("Remove", Ink.kumkumInk) {
                                    asking = null
                                    app.removeWord(id)
                                }
                            }
                        } else {
                            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(word, style = rowStyle, modifier = Modifier.weight(1f))
                                Dots(minOf(n, CoreConstants.MAX_SHOTS))
                                Spacer(Modifier.width(8.dp))
                                if (teaching == null && n < CoreConstants.MAX_SHOTS) {
                                    ChipButton(if (n < Words.SHOTS) "Teach" else "More", Ink.bone2) { app.teachWord(id) }
                                    Spacer(Modifier.width(4.dp))
                                }
                                IconButtonSoft(MounaIcons.Remove, "Remove $word") { asking = id }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Each time you say yes to a word, Mouna learns it a little better.", style = Type.hint)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("Examples stay on this phone. Nothing is uploaded; no video is kept.", style = Type.hint.copy(fontStyle = FontStyle.Italic))
        Spacer(Modifier.height(24.dp))
    }
}

private val noteStyle get() = Type.mono.copy(fontSize = 13.sp, color = Ink.leaf)
private val rowStyle get() = Type.body.copy(color = Ink.bone)

/** A small outlined text button, 48dp tall. */
@Composable
internal fun ChipButton(text: String, color: Color, onClick: () -> Unit) {
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
