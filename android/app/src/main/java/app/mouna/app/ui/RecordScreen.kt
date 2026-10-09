package app.mouna.app.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mouna.app.MounaApp
import app.mouna.app.engine.Knowledge

/**
 * Recording for free talk's fine-tune (issue #5 B): one prompted sentence at a time, mouthed silently. Only the 96 px
 * grey mouth crops and the sentence are kept (avsr/train/), for `python -m mouna_encoder avsr-adapt` on the laptop.
 */
@Composable
fun RecordScreen(app: MounaApp, k: Knowledge, bind: (PreviewView) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text("RECORD FOR FREE TALK", style = Type.label)
        Spacer(Modifier.height(8.dp))
        CameraCard(app.engine.live, bind, Modifier.fillMaxWidth().height(200.dp)) { st ->
            val pill = when {
                !k.freeReady -> "Free talk isn’t ready yet"
                !app.recording -> "Not recording"
                !st.face -> "Looking for your face"
                st.hearing -> "Recording…"
                else -> "Mouth the sentence now"
            }
            Pill(pill, if (app.recording && st.face) Ink.leaf else Ink.mute, pulse = st.hearing, modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
        }
        Spacer(Modifier.height(18.dp))
        if (!app.recording) {
            Text(
                "You will see ${app.recordPrompts.size} short sentences, one at a time. Mouth each one silently, then keep " +
                    "your lips still for a moment. Only a small grey picture of your mouth and the sentence are kept, on this " +
                    "phone, to tune free talk to you. Recorded so far this session: ${app.recordCount}.",
                style = Type.body,
            )
            Spacer(Modifier.weight(1f))
            BigButton("Start recording", Tone.YES, Modifier.fillMaxWidth()) { app.startRecording() }
        } else {
            Text("Sentence ${app.recordIndex + 1} of ${app.recordPrompts.size}", style = Type.label)
            Spacer(Modifier.height(8.dp))
            Text(app.recordPrompts[app.recordIndex], style = Type.display.copy(fontSize = 32.sp, lineHeight = 38.sp, color = Ink.turmeric))
            Spacer(Modifier.height(12.dp))
            app.lastRecorded?.let { r ->
                Text("Saved “${r.text}” · read as “${r.read ?: "nothing"}”", style = Type.body.copy(fontSize = 13.sp, color = Ink.mute))
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("Redo last", Tone.NO, Modifier.weight(1f)) { app.recordStep(-1) }
                BigButton("Skip", Tone.NO, Modifier.weight(1f)) { app.recordStep(1) }
                BigButton("Done", Tone.PRIMARY, Modifier.weight(1f)) { app.stopRecording() }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
