package app.mouna.app.ui

import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mouna.app.MounaApp
import app.mouna.app.engine.Knowledge
import app.mouna.app.engine.SignBook

/**
 * Teach Sign the person's own signs. The INCLUDE model knows its training signers, not this person, so they type
 * what Mouna should say and sign it three times; from then on Sign matches their signs (SignBook). Any movement works
 * if it is the same each time: an ISL sign, a home sign, a gesture.
 */
@Composable
fun SignsScreen(app: MounaApp, k: Knowledge, bind: (PreviewView) -> Unit) {
    var text by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf<String?>(null) } // the sign being confirmed for removal
    val teaching = app.signTeaching
    val names = k.signs.toMap()
    val usable = k.islReady && k.signsTeachable

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Text("Teach your signs", style = Type.title)
        Spacer(Modifier.height(6.dp))
        Text(
            "Type what Mouna should say, then sign it ${SignBook.SHOTS} times: hands up for the sign, then down. " +
                "Any sign works if you make it the same way each time.",
            style = Type.body,
        )
        Spacer(Modifier.height(18.dp))

        Card {
            Column {
                CameraCard(app.engine.live, bind, Modifier.fillMaxWidth().height(280.dp)) { st ->
                    Box(Modifier.align(Alignment.TopStart).padding(12.dp)) {
                        when {
                            !k.islKnown -> Pill("Getting ready…", Ink.mute, pulse = true)
                            !k.islReady -> Pill("Sign isn’t available on this phone", Ink.mute)
                            !k.signsTeachable -> Pill("This sign model can’t learn signs", Ink.mute)
                            teaching == null -> Pill("Ready", Ink.leaf)
                            st.signing -> Pill("Seeing your sign…", Ink.turmeric, pulse = true)
                            st.body && st.handsUp -> Pill("Sign it now", Ink.turmeric, pulse = true)
                            st.body -> Pill("Raise your hands to sign", Ink.leaf)
                            else -> Pill("Step back so your hands show")
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (teaching != null) {
                    val n = k.signCounts[teaching] ?: 0
                    SectionLabel("Sign it, then lower your hands")
                    Text("“${names[teaching] ?: ""}”", style = Type.phrase.copy(fontSize = 26.sp))
                    Spacer(Modifier.height(8.dp))
                    Dots(minOf(n, SignBook.SHOTS), SignBook.SHOTS, Ink.leaf)
                    app.signNote?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = noteStyle)
                    }
                    Spacer(Modifier.height(16.dp))
                    BigButton("Stop", Tone.NO, Modifier.fillMaxWidth()) { app.stopTeachingSign() }
                } else {
                    app.signNote?.let {
                        Text(it, style = noteStyle)
                        Spacer(Modifier.height(12.dp))
                    }
                    SectionLabel("New sign")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextBox(text, { text = it.take(60) }, "Water, Amma, I’m in pain", Modifier.weight(1f))
                        Spacer(Modifier.width(10.dp))
                        BigButton("Teach", Tone.PRIMARY, enabled = text.isNotBlank() && usable) {
                            val id = app.addSign(text)
                            text = ""
                            app.teachSign(id)
                        }
                    }
                }
            }
        }

        if (k.signs.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Card {
                Column {
                    SectionLabel("Your signs")
                    k.signs.forEach { (id, word) ->
                        val n = k.signCounts[id] ?: 0
                        if (asking == id) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Remove “$word” and its examples?", style = rowStyle.copy(fontSize = 14.sp), modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                ChipButton("Keep", Ink.bone2) { asking = null }
                                Spacer(Modifier.width(6.dp))
                                ChipButton("Remove", Ink.kumkumInk) {
                                    asking = null
                                    app.removeSign(id)
                                }
                            }
                        } else {
                            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(word, style = rowStyle, modifier = Modifier.weight(1f))
                                Dots(minOf(n, SignBook.MAX_SHOTS), SignBook.MAX_SHOTS)
                                Spacer(Modifier.width(8.dp))
                                if (teaching == null && usable && n < SignBook.MAX_SHOTS) {
                                    ChipButton(if (n < SignBook.SHOTS) "Teach" else "More", Ink.bone2) { app.teachSign(id) }
                                    Spacer(Modifier.width(4.dp))
                                }
                                IconButtonSoft(MounaIcons.Remove, "Remove $word") { asking = id }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Once you teach signs, Sign listens for yours only. More examples make it surer.", style = Type.hint)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("Examples stay on this phone as numbers. Nothing is uploaded; no video is kept.", style = Type.hint.copy(fontStyle = FontStyle.Italic))
        Spacer(Modifier.height(24.dp))
    }
}

private val noteStyle get() = Type.mono.copy(fontSize = 13.sp, color = Ink.leaf)
private val rowStyle get() = Type.body.copy(color = Ink.bone)
