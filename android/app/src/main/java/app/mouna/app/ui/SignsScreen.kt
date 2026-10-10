package app.mouna.app.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mouna.app.MounaApp
import app.mouna.app.engine.Knowledge
import app.mouna.app.engine.Lang
import app.mouna.app.engine.PhrasePack
import app.mouna.app.engine.SignBook
import app.mouna.app.engine.SignDemos

/** ISLRTC's dictionary channel, searched for a word: for phrases INCLUDE has no sign for. */
private fun lookUp(word: String) =
    Uri.parse("https://www.youtube.com/channel/UC3AcGIlqVI4nJWCwHgHFXtg/search?query=" + Uri.encode(word))

/** The word to look up for a phrase with no demo. */
private val LOOK_UP = mapOf(
    "water" to "water", "pain" to "pain", "breathe" to "breathe", "sit_up" to "sit", "yes" to "yes", "no" to "no",
    "love" to "love", "scared" to "fear", "stay" to "stay", "hold_hand" to "hold",
)

/**
 * Teach Sign the person's own signs. Mouna lists its phrases; pick one, watch how it is signed (a real INCLUDE signer as
 * a stick figure, where INCLUDE has the sign), then sign it three times. Custom signs: type what Mouna should say. The
 * INCLUDE model knows its training signers, not this person, so Sign matches the person's own examples (SignBook).
 */
@Composable
fun SignsScreen(app: MounaApp, k: Knowledge, bind: (PreviewView) -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf<String?>(null) } // the custom sign being confirmed for removal
    val teaching = app.signTeaching
    val names = k.signs.toMap()
    val usable = k.islReady && k.signsTeachable
    // every phrase Mouna can say; the ones with a demo first (most people learning to sign start from those)
    val phraseIds = app.phrases.phrases.map { it.id }.filter { it !in PhrasePack.ACTIONS }
        .sortedBy { if (SignDemos.FOR_PHRASE.containsKey(it)) 0 else 1 }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Text("Teach your signs", style = Type.title)
        Spacer(Modifier.height(6.dp))
        Text(
            "Pick a phrase, watch how it is signed, then sign it ${SignBook.SHOTS} times: hands up, the sign, hands down.",
            style = Type.body,
        )
        Spacer(Modifier.height(18.dp))

        if (teaching != null) {
            val phraseId = teaching.removePrefix("p_").takeIf { teaching.startsWith("p_") }
            val demo = phraseId?.let { SignDemos.forPhrase(context, it) }
            val n = k.signCounts[teaching] ?: 0
            Card {
                Column {
                    SectionLabel("Sign this")
                    Text("“${phraseId?.let { app.phrases[it]?.say(Lang.EN) } ?: names[teaching].orEmpty()}”", style = Type.phrase.copy(fontSize = 26.sp, lineHeight = 30.sp))
                    Spacer(Modifier.height(12.dp))
                    if (demo != null) {
                        Box(
                            Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(22.dp)).border(1.dp, Ink.rule, RoundedCornerShape(22.dp)),
                        ) {
                            SignFigure(demo, Modifier.fillMaxSize().padding(12.dp))
                            Text(
                                "SIGN: ${demo.sign.uppercase()}",
                                style = Type.label.copy(color = Ink.turmeric),
                                modifier = Modifier.align(Alignment.TopStart).padding(14.dp),
                            )
                        }
                        Text(SignDemos.CREDIT, style = Type.hint.copy(fontSize = 11.sp), modifier = Modifier.padding(top = 6.dp))
                    } else {
                        Text(
                            "No demo for this one. Use any sign you can repeat the same way, or look one up.",
                            style = Type.body.copy(fontSize = 14.sp),
                        )
                        LOOK_UP[phraseId]?.let { word ->
                            Text(
                                "Look up “$word” in the ISL dictionary →",
                                style = Type.body.copy(color = Ink.turmeric, textDecoration = TextDecoration.Underline),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, lookUp(word)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                    .wrapContentHeight(Alignment.CenterVertically),
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    SectionLabel("You")
                    CameraCard(app.engine.live, bind, Modifier.fillMaxWidth().height(240.dp)) { st ->
                        Box(Modifier.align(Alignment.TopStart).padding(12.dp)) {
                            when {
                                st.signing -> Pill("Seeing your sign…", Ink.turmeric, pulse = true)
                                st.body && st.handsUp -> Pill("Sign it now", Ink.turmeric, pulse = true)
                                st.body -> Pill("Raise your hands to sign", Ink.leaf)
                                else -> Pill("Step back so your hands show")
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dots(minOf(n, SignBook.SHOTS), SignBook.SHOTS, Ink.leaf)
                        Spacer(Modifier.width(10.dp))
                        Text(app.signNote ?: "Sign it, then lower your hands", style = noteStyle, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(14.dp))
                    BigButton("Stop", Tone.NO, Modifier.fillMaxWidth()) { app.stopTeachingSign() }
                }
            }
            Spacer(Modifier.height(14.dp))
        } else {
            app.signNote?.let {
                Text(it, style = noteStyle)
                Spacer(Modifier.height(12.dp))
            }
            if (!usable) {
                Text(
                    when {
                        !k.islKnown -> "Getting the sign model ready…"
                        !k.islReady -> "Sign isn’t available on this phone: the sign model is missing."
                        else -> "This sign model can’t learn signs: it needs the newer export (models/isl/export_isl.py)."
                    },
                    style = Type.body.copy(color = Ink.turmeric),
                )
                Spacer(Modifier.height(12.dp))
            }
        }

        // Mouna's phrases, each with its sign
        Card {
            Column {
                SectionLabel("Mouna’s phrases")
                phraseIds.forEach { pid ->
                    val id = "p_$pid"
                    val n = k.signCounts[id] ?: 0
                    val hasDemo = SignDemos.FOR_PHRASE.containsKey(pid)
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(app.phrases[pid]?.say(Lang.EN).orEmpty(), style = rowStyle)
                            Text(if (hasDemo) "with a demo" else "no demo · your own sign", style = Type.hint.copy(fontSize = 11.sp, color = if (hasDemo) Ink.leaf else Ink.mute))
                        }
                        Dots(minOf(n, SignBook.MAX_SHOTS), SignBook.MAX_SHOTS)
                        Spacer(Modifier.width(8.dp))
                        if (teaching == null && usable && n < SignBook.MAX_SHOTS) {
                            ChipButton(if (n < SignBook.SHOTS) "Teach" else "More", Ink.bone2) { app.teachPhraseSign(pid) }
                        } else if (teaching == null && n > 0) {
                            IconButtonSoft(MounaIcons.Remove, "Forget this sign") { app.removeSign(id) }
                        }
                    }
                }
            }
        }

        // the person's own signs, with any words
        Spacer(Modifier.height(14.dp))
        Card {
            Column {
                SectionLabel("Your own signs")
                Text("A sign for anything else: a name, a word, a home sign. Type what Mouna should say.", style = Type.body.copy(fontSize = 13.sp))
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextBox(text, { text = it.take(60) }, "Amma, I’m hungry", Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    BigButton("Teach", Tone.PRIMARY, enabled = text.isNotBlank() && usable && teaching == null) {
                        val id = app.addSign(text)
                        text = ""
                        app.teachSign(id)
                    }
                }
                k.signs.filter { !it.first.startsWith("p_") }.forEach { (id, word) ->
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
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("Once any sign is taught, Sign listens for yours only. Each extra example makes it surer.", style = Type.hint)
        Spacer(Modifier.height(20.dp))
        Text(
            "Examples stay on this phone as numbers. Nothing is uploaded; no video is kept.",
            style = Type.hint.copy(fontStyle = FontStyle.Italic),
            textAlign = TextAlign.Start,
        )
        Spacer(Modifier.height(24.dp))
    }
}

private val noteStyle get() = Type.mono.copy(fontSize = 13.sp, color = Ink.leaf)
private val rowStyle get() = Type.body.copy(color = Ink.bone)
