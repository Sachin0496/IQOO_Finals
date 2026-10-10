package app.mouna.app.ui

import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mouna.app.MounaApp
import app.mouna.app.Screen
import app.mouna.app.sense.Frame
import app.mouna.core.GazeCalibration
import app.mouna.core.SwitchTeachResult
import app.mouna.core.calibrateGaze
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val setupTitle get() = Type.title
private val cueTitle get() = Type.display
private val targetText get() = Type.title.copy(fontSize = 24.sp)
private val noFace get() = Type.body.copy(color = Ink.bone, fontSize = 16.sp, lineHeight = 22.sp, textAlign = TextAlign.Center)
private val recognised get() = Type.mono.copy(fontSize = 14.sp)

/**
 * The frame both setup flows share, so the camera is the same size in the same place and the main button is always at
 * the bottom: the camera, a content area that fills the middle, and a button slot (empty while something is being measured).
 * With no face in view the camera says so, in words.
 */
@Composable
private fun SetupFrame(
    app: MounaApp,
    bind: (PreviewView) -> Unit,
    button: (@Composable () -> Unit)?,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp)) {
        CameraCard(app.engine.live, bind, Modifier.fillMaxWidth().height(220.dp)) { st ->
            if (!st.face) {
                Box(Modifier.fillMaxSize().background(Ink.bg.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
                    Text("I can’t see your face.\nMove closer to the light.", style = noFace, modifier = Modifier.padding(24.dp))
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Box(Modifier.weight(1f).fillMaxWidth(), content = content)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(60.dp)) { button?.invoke() }
        Spacer(Modifier.height(8.dp))
    }
}

/** Look to choose: three looks (middle, left picture, right picture), two seconds each. */
@Composable
fun EyesSetup(app: MounaApp, bind: (PreviewView) -> Unit) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) } // 0 intro, 1 centre, 2 left, 3 right, 4 done
    var message by remember { mutableStateOf<String?>(null) }
    var learned by remember { mutableStateOf(false) }

    fun run() = scope.launch {
        message = null
        learned = false
        val got = mutableListOf<List<Double>>()
        for (s in 1..3) {
            step = s
            delay(700) // time to move the eyes there
            got += app.engine.capture(1500).map { it.iris }.filter { !it.isNaN() }
        }
        when (val r = calibrateGaze(got[0], got[1], got[2])) {
            is GazeCalibration.Ready -> {
                app.engine.setGaze(r.model)
                learned = true
                message = "Done. When Mouna shows two pictures, look at one and hold."
            }
            is GazeCalibration.Failed -> message = r.message
        }
        step = 4
    }

    val measuring = step in 1..3
    SetupFrame(
        app, bind,
        button = if (measuring) null else ({
            if (learned) BigButton("Finish", Tone.YES, Modifier.fillMaxWidth()) { app.go(Screen.SETTINGS) }
            else BigButton(if (step == 0) "Start" else "Try again", Tone.PRIMARY, Modifier.fillMaxWidth()) { run() }
        }),
    ) {
        if (!measuring) {
            Column(Modifier.align(Alignment.TopStart).verticalScroll(rememberScrollState())) {
                Text("Look to choose", style = setupTitle)
                Spacer(Modifier.height(8.dp))
                Text(message ?: "Keep your head still and move only your eyes: the middle, then a picture on the left, then one on the right.", style = Type.body)
            }
        } else {
            val align = when (step) { 1 -> Alignment.Center; 2 -> Alignment.CenterStart; else -> Alignment.CenterEnd }
            Target(Modifier.align(align))
            Text(
                when (step) { 1 -> "Look at the dot"; 2 -> "Look left"; else -> "Look right" },
                style = targetText,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun Target(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "target")
    val s by t.animateFloat(0.85f, 1.1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "s")
    Box(modifier.size(84.dp).scale(s).clip(CircleShape).background(Ink.turmeric.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Ink.turmeric))
    }
}

/**
 * Personal switch: any movement the person can repeat becomes their "yes" (one-sided smile after a stroke, an
 * eyebrow, a cheek puff). Rest face first, then the movement three times on cue; Mouna finds the blendshapes that
 * move. Then a live meter to try it.
 */
@Composable
fun SwitchSetup(app: MounaApp, bind: (PreviewView) -> Unit) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf("intro") } // intro, rest, ready, move, result
    var rep by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var learned by remember { mutableStateOf(false) }
    val presses = app.switchPresses
    val start = remember(learned) { app.switchPresses }

    fun run() = scope.launch {
        message = null
        learned = false
        step = "rest"
        val rest = app.engine.capture(3000)
        val moves = mutableListOf<List<Frame>>()
        for (i in 1..3) {
            rep = i
            step = "ready"
            delay(1200)
            step = "move"
            moves += app.engine.capture(1800)
        }
        when (val r = app.engine.learnSwitch(rest, moves)) {
            is SwitchTeachResult.Learned -> {
                learned = true
                message = "Learned: " + r.model.channels.joinToString(", ") { it.name }
            }
            is SwitchTeachResult.Failed -> message = r.message
        }
        step = "result"
    }

    val busy = step == "rest" || step == "ready" || step == "move"
    SetupFrame(
        app, bind,
        button = if (busy) null else ({
            when {
                step == "intro" -> BigButton("Start", Tone.PRIMARY, Modifier.fillMaxWidth()) { run() }
                learned -> BigButton("Finish", Tone.YES, Modifier.fillMaxWidth()) { app.go(Screen.SETTINGS) }
                else -> BigButton("Try again", Tone.PRIMARY, Modifier.fillMaxWidth()) { run() }
            }
        }),
    ) {
        Column(Modifier.align(Alignment.TopStart).verticalScroll(rememberScrollState())) {
            when (step) {
                "intro" -> {
                    Text("Your movement", style = setupTitle)
                    Spacer(Modifier.height(8.dp))
                    Text("Choose one movement you can repeat: raise your eyebrows, a half smile, puff a cheek, look up. It becomes your “yes”.", style = Type.body)
                }
                "rest" -> Cue("Relax your face", "Three seconds. Mouth something if you like: Mouna must not mistake talking for your movement.")
                "ready" -> Cue("Get ready… ($rep of 3)", "Relax.")
                "move" -> Cue("Now!", "Do your movement once.", Ink.turmeric)
                else -> {
                    Text(if (learned) "Try it" else "Let’s try again", style = setupTitle)
                    Spacer(Modifier.height(8.dp))
                    Text(message ?: "", style = Type.body)
                    if (learned) {
                        Spacer(Modifier.height(20.dp))
                        Meter(app)
                        Spacer(Modifier.height(10.dp))
                        Text("Recognised ${presses - start} times", style = recognised)
                    }
                }
            }
        }
    }
}

@Composable
private fun Cue(title: String, note: String, color: Color = Ink.bone) {
    Text(title, style = cueTitle.copy(color = color))
    Spacer(Modifier.height(8.dp))
    Text(note, style = Type.body)
}

/** Live switch level: 0 at rest, 1 at the taught movement; it fires above 0.6. */
@Composable
private fun Meter(app: MounaApp) {
    val raw by app.engine.live.collectSlice { it.switchLevel }
    val level by animateFloatAsState(raw.toFloat().coerceIn(0f, 1.2f) / 1.2f, tween(80), label = "level")
    Box(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(9.dp)).background(Ink.card)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(level).clip(RoundedCornerShape(9.dp)).background(if (raw >= 0.6) Ink.leaf else Ink.turmeric))
    }
}
