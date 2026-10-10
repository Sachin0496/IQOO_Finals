package app.mouna.app.ui

import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mouna.app.MounaApp
import app.mouna.app.Screen
import app.mouna.app.sense.Frame
import app.mouna.app.sense.GazeFit
import app.mouna.app.sense.GazeMap
import app.mouna.app.sense.GazePoint
import app.mouna.app.sense.GazeSmoother
import app.mouna.app.sense.gazeFeatures
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Collections
import kotlin.math.roundToInt

/** Quick: the middle, then once round the edge, where the buttons are (9 dots, about 20 s). */
private val QUICK = listOf(
    0.5f to 0.5f, 0f to 0f, 0.5f to 0f, 1f to 0f, 1f to 0.5f, 1f to 1f, 0.5f to 1f, 0f to 1f, 0f to 0.5f,
)

/**
 * Accurate: the middle, then a 4 x 5 grid row by row, snaking so each move is short (21 dots, about 45 s). Enough dots
 * for the map to bend towards the edges (GazeMap.CURVED_MIN).
 */
private val ACCURATE = listOf(0.5f to 0.5f) + (0..4).flatMap { r ->
    val row = (0..3).map { c -> c / 3f to r / 4f }
    if (r % 2 == 0) row else row.reversed()
}

/** How long the eyes get to reach a dot before it is measured, and how long it is measured. */
private const val SETTLE_MS = 650L
private const val MEASURE_MS = 1100L

/** Millimetres, for the result in words. */
internal fun pxToMm(px: Double, xdpi: Float) = px / xdpi * 25.4

/**
 * Eye control's calibration: the person follows a dot round the screen with their eyes (20 or 45 s), Mouna learns where
 * they look, then shows where it thinks they look so a caregiver can check it before it is turned on. Full screen, so
 * the dots reach the corners where the Back and Settings buttons are.
 */
@Composable
fun EyeControlSetup(app: MounaApp, bind: (PreviewView) -> Unit) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val density = LocalDensity.current
    var step by remember { mutableIntStateOf(0) } // 0 intro, 1 following dots, 2 check, 3 failed
    var dots by remember { mutableStateOf(ACCURATE) }
    var done by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var fitted by remember { mutableStateOf<GazeMap?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) } // this screen in the root view
    var size by remember { mutableStateOf(IntSize.Zero) }
    val dot = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val dotSize = remember { Animatable(28f) }

    /** A point in this screen to screen pixels, the space the gaze map works in. */
    fun toScreen(p: Offset): Offset {
        val loc = IntArray(2).also { view.getLocationOnScreen(it) }
        return p + origin + Offset(loc[0].toFloat(), loc[1].toFloat())
    }

    fun dotAt(fx: Float, fy: Float): Offset {
        val m = with(density) { 44.dp.toPx() }
        return Offset(m + fx * (size.width - 2 * m), m + fy * (size.height - 2 * m))
    }

    fun run(which: List<Pair<Float, Float>>) = scope.launch {
        dots = which
        message = null
        fitted = null
        done = 0
        step = 1
        dot.snapTo(dotAt(0.5f, 0.5f))
        val points = mutableListOf<GazePoint>()
        for ((fx, fy) in which) {
            val at = dotAt(fx, fy)
            dotSize.snapTo(28f)
            dot.animateTo(at, tween(450))
            delay(SETTLE_MS)
            // The dot shrinks while it is measured: the eyes settle on its centre, not somewhere on a big disc.
            launch { dotSize.animateTo(10f, tween(MEASURE_MS.toInt())) }
            val frames = record(app, MEASURE_MS)
            done++
            // Blinks are not looks: drop frames whose lids are well under this dot's usual opening.
            val open = frames.map { it.eyeOpen }.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
            val samples = frames.filter { it.eyeOpen >= 0.75f * open }.mapNotNull { gazeFeatures(it) }
            val s = toScreen(at)
            points += GazePoint(s.x.toDouble(), s.y.toDouble(), samples)
        }
        when (val r = GazeMap.fit(points, maxErrorPx = 0.45 * size.width)) {
            is GazeFit.Ready -> {
                fitted = r.map
                step = 2
            }
            is GazeFit.Failed -> {
                message = r.message
                step = 3
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Ink.bg)
            .onGloballyPositioned {
                origin = it.positionInRoot()
                size = it.size
            },
    ) {
        when (step) {
            1 -> {
                Dot(dot.value, sizeDp = dotSize.value)
                Text(
                    "Follow the dot with your eyes\n$done of ${dots.size}",
                    style = Type.phrase.copy(color = Ink.bone2),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(top = 120.dp),
                )
            }
            2 -> {
                val map = fitted ?: return@Box
                for ((fx, fy) in dots) Dot(dotAt(fx, fy), faint = true)
                LiveGaze(app, map) { s ->
                    val loc = IntArray(2).also { view.getLocationOnScreen(it) }
                    s - origin - Offset(loc[0].toFloat(), loc[1].toFloat())
                }
                val mm = pxToMm(map.errorPx, view.resources.displayMetrics.xdpi).roundToInt()
                Column(Modifier.align(Alignment.Center).padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Look around", style = Type.title, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The ring should follow your eyes. Mouna was about $mm mm off at the dots; buttons are outlined by the nearest one, so big buttons work best.",
                        style = Type.body,
                        textAlign = TextAlign.Center,
                    )
                }
                Row(
                    Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(horizontal = 20.dp, vertical = 90.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BigButton("Again", Tone.NO, Modifier.weight(1f)) { run(dots) }
                    BigButton("Turn on", Tone.YES, Modifier.weight(1.4f)) {
                        app.calibratedEyes(map)
                        app.go(Screen.SETTINGS)
                    }
                }
            }
            else -> Column(
                Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text("Eye control", style = Type.title)
                Spacer(Modifier.height(8.dp))
                Text(
                    message ?: "Hold the phone where you will use it and keep your head still. A dot will move round the screen: follow it with your eyes. Accurate takes about 45 seconds and outlines the right button more often; Quick takes 20.",
                    style = Type.body,
                )
                Spacer(Modifier.height(16.dp))
                CameraCard(app.engine.live, bind, Modifier.fillMaxWidth().height(220.dp)) { st ->
                    if (!st.face) {
                        Box(Modifier.fillMaxSize().background(Ink.bg.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
                            Text("I can’t see your face.\nMove closer to the light.", style = Type.body.copy(color = Ink.bone, fontSize = 16.sp), textAlign = TextAlign.Center)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Then: look at a button to outline it. Close your eyes for about half a second to press it. Blink twice quickly to go back.",
                    style = Type.body.copy(fontSize = 14.sp),
                )
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigButton("Cancel", Tone.NO, Modifier.weight(1f)) { app.go(Screen.SETTINGS) }
                    BigButton("Quick · 9 dots", Tone.NO, Modifier.weight(1.2f)) { run(QUICK) }
                }
                Spacer(Modifier.height(12.dp))
                BigButton(if (step == 0) "Accurate · 21 dots" else "Try again · 21 dots", Tone.PRIMARY, Modifier.fillMaxWidth()) { run(ACCURATE) }
            }
        }
    }
}

/** Every frame with a face for [ms], from eye control's own tap (no blendshapes needed, unlike Engine.capture). */
private suspend fun record(app: MounaApp, ms: Long): List<Frame> {
    val out = Collections.synchronizedList(mutableListOf<Frame>())
    val stop = app.engine.watch { if (it.face) out.add(it) }
    try {
        delay(ms)
    } finally {
        stop()
    }
    return synchronized(out) { out.toList() }
}

@Composable
private fun Dot(at: Offset, faint: Boolean = false, sizeDp: Float = 28f) {
    val d = if (faint) 14.dp else sizeDp.dp
    val half = with(LocalDensity.current) { (d / 2).toPx() }
    Box(
        Modifier
            .offset { IntOffset((at.x - half).roundToInt(), (at.y - half).roundToInt()) }
            .size(d)
            .clip(CircleShape)
            .background(if (faint) Ink.rule2 else Ink.turmeric),
    )
}

/** The check step: where Mouna thinks the person is looking, as a ring, live. */
@Composable
private fun LiveGaze(app: MounaApp, map: GazeMap, toLocal: (Offset) -> Offset) {
    val gaze = remember(map) { MutableStateFlow<Offset?>(null) }
    DisposableEffect(map) {
        val smoother = GazeSmoother()
        val stop = app.engine.watch { f ->
            gazeFeatures(f)?.let { feats ->
                if (f.eyeOpen < map.closedBelow / 0.6 * 0.8) return@let // blinking: hold still
                val (x, y) = map.predict(feats)
                val (sx, sy) = smoother.push(x, y)
                gaze.value = Offset(sx.toFloat(), sy.toFloat())
            }
        }
        onDispose { stop() }
    }
    val at by gaze.collectAsState()
    val p = at?.let(toLocal) ?: return
    val half = with(LocalDensity.current) { 30.dp.toPx() }
    Box(
        Modifier
            .offset { IntOffset((p.x - half).roundToInt(), (p.y - half).roundToInt()) }
            .size(60.dp)
            .border(3.dp, Ink.turmeric, CircleShape),
    )
}
