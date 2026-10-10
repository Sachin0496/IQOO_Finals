package app.mouna.app.ui

import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.mouna.app.engine.Lang
import app.mouna.app.engine.Live
import app.mouna.app.engine.Phrase
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The slow-changing part of [Live]: what the status words depend on. Equal values recompose nothing. */
@Immutable
data class LiveStatus(
    val face: Boolean = false,
    val hearing: Boolean = false,
    val body: Boolean = false,
    val handsUp: Boolean = false,
    val signing: Boolean = false,
)

private fun Live.status() = LiveStatus(face, hearing, body, handsRaised, signing)

/** Status words for a screen: recomposes only when one of them changes, not at the camera's frame rate. */
@Composable
fun StateFlow<Live>.collectStatus(): State<LiveStatus> {
    val flow = this
    val slow = remember(flow) { flow.map { it.status() }.distinctUntilChanged() }
    return slow.collectAsState(flow.value.status())
}

/** One slice of [Live] (e.g. the gaze zone), recomposing only when that slice changes. */
@Composable
fun <T> StateFlow<Live>.collectSlice(select: (Live) -> T): State<T> {
    val flow = this
    val slow = remember(flow) { flow.map(select).distinctUntilChanged() }
    return slow.collectAsState(select(flow.value))
}

private val CardShape = RoundedCornerShape(28.dp)

/** The camera feeding Mouna: whether it is the selfie camera, and a way to turn it round. Provided by MainActivity. */
@Immutable
data class CameraFacing(val front: Boolean = true, val canFlip: Boolean = false, val flip: () -> Unit = {})

val LocalCameraFacing = compositionLocalOf { CameraFacing() }
private val LipFaint get() = Ink.bone.copy(alpha = 0.45f)

/** The camera, softly framed, with the lip contour drawn in turmeric while Mouna is hearing. */
@Composable
fun CameraCard(
    liveFlow: StateFlow<Live>,
    bind: (PreviewView) -> Unit,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.(LiveStatus) -> Unit = {},
) {
    val status by liveFlow.collectStatus() // slow: only when face / hearing / hands change
    val frame = liveFlow.collectAsState() // read only inside the draw lambda, so only the lip line redraws per frame
    val ring by animateColorAsState(if (status.hearing) Ink.turmeric else Ink.rule, tween(220), label = "ring")
    Box(
        modifier
            .clip(CardShape)
            .background(Ink.raised)
            .border(1.5.dp, ring, CardShape),
    ) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE // TextureView: clips to the card
                    bind(this)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        val camera = LocalCameraFacing.current
        LipLine(frame, mirrored = camera.front)
        overlay(status)
        if (camera.canFlip) FlipButton(camera, Modifier.align(Alignment.TopEnd).padding(8.dp))
    }
}

/** Selfie camera <-> back camera, so a caregiver can hold the phone and point it at the person. */
@Composable
private fun FlipButton(camera: CameraFacing, modifier: Modifier) {
    Box(
        modifier.size(48.dp).clip(CircleShape).background(Ink.bg.copy(alpha = 0.78f)).clickable(onClick = camera.flip),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Refresh,
            if (camera.front) "Use the back camera" else "Use the selfie camera",
            tint = Ink.bone2,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** Outer lip contour over the fill-centre preview (mirrored for the selfie camera). Reads the per-frame state only while drawing. */
@Composable
private fun LipLine(frame: State<Live>, mirrored: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        val live = frame.value
        val outer = live.outer
        if (!live.face || outer.isEmpty() || live.imageW == 0) return@Canvas
        val s = maxOf(size.width / live.imageW, size.height / live.imageH)
        val dx = (size.width - live.imageW * s) / 2
        val dy = (size.height - live.imageH * s) / 2
        val path = Path()
        for (i in outer.indices) {
            val px = outer[i].first * live.imageW * s + dx
            val x = if (mirrored) size.width - px else px
            val y = outer[i].second * live.imageH * s + dy
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(
            path,
            if (live.hearing) Ink.turmeric else LipFaint,
            style = Stroke(width = if (live.hearing) 3.dp.toPx() else 1.5.dp.toPx(), join = StrokeJoin.Round),
        )
    }
}

/** Small status capsule: a dot and a few plain words, always one line, always the same height. */
@Composable
fun Pill(text: String, dot: Color = Ink.mute, pulse: Boolean = false, modifier: Modifier = Modifier) {
    val alpha = if (pulse) {
        val t = rememberInfiniteTransition(label = "pulse")
        t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "a").value
    } else 1f
    Row(
        modifier
            .heightIn(min = 36.dp)
            .clip(CircleShape)
            .background(Ink.bg.copy(alpha = 0.78f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot.copy(alpha = alpha)))
        Spacer(Modifier.width(8.dp))
        Text(text, style = Type.pill, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val TileShape = RoundedCornerShape(22.dp)
private val BigTileShape = RoundedCornerShape(28.dp)
private val phraseStyle get() = Type.phrase
private val tileSub get() = Type.label.copy(letterSpacing = 0.sp, fontSize = 11.sp, lineHeight = 14.sp)
private const val TILE_LINES = 3

/** The starting size for a phrase in [lang]: Indic scripts are wider and taller than Latin at the same size. */
private fun baseSp(lang: Lang, big: Boolean): Float = when {
    big -> 26f
    lang == Lang.EN -> 16f
    lang == Lang.HI -> 15f
    else -> 14f
}

/** Row height that holds three lines of [lang]'s script plus the English line, so every tile in a row is equal. */
fun tileHeight(lang: Lang): Dp = if (lang == Lang.EN) 148.dp else 176.dp

/** Height of the two-column prompt tiles: equal for every tile, enough for three lines of the script plus English. */
fun gridTileHeight(lang: Lang): Dp = if (lang == Lang.EN) 160.dp else 204.dp

/** A phrase as a picture: icon in a soft circle, the words in the caregiver's language, English beneath. */
@Composable
fun PhraseTile(
    phrase: Phrase,
    lang: Lang,
    modifier: Modifier = Modifier,
    big: Boolean = false,
    /** For the two-column prompt grids: a larger icon and text than the Speak row. */
    roomy: Boolean = false,
    selected: Boolean = false,
    accent: Color = Ink.turmeric,
    onClick: (() -> Unit)? = null,
) {
    val border by animateColorAsState(if (selected) accent else Ink.rule, tween(160), label = "border")
    val shape = if (big) BigTileShape else TileShape
    val measurer = rememberTextMeasurer()
    val text = phrase.say(lang)
    val circle: Dp = if (big) 92.dp else if (roomy) 60.dp else 46.dp
    BoxWithConstraints(
        modifier
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.12f) else Ink.card)
            .border(if (selected) 2.dp else 1.dp, border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(if (big) 20.dp else if (roomy) 16.dp else 12.dp),
    ) {
        val availPx = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
        val base = baseSp(lang, big) + if (roomy) 4f else 0f
        // The phrase is never cut with an ellipsis: take the largest size at which it fits in three lines and no word breaks.
        val sp = remember(text, availPx, base, big) {
            val min = if (big) 16f else 11f
            if (availPx <= 0) return@remember base
            val words = text.split(' ', '\n').filter { it.isNotEmpty() }
            var size = base
            while (size > min) {
                val st = phraseStyle.copy(fontSize = size.sp, lineHeight = (size * 1.3f).sp)
                val lines = measurer.measure(text, st, constraints = Constraints(maxWidth = availPx)).lineCount
                val widest = words.maxOfOrNull { measurer.measure(it, st, softWrap = false).size.width } ?: 0
                if (lines <= TILE_LINES && widest <= availPx) break
                size -= 0.5f
            }
            size
        }
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (big) Arrangement.Center else Arrangement.Top,
        ) {
            Box(
                Modifier.size(circle).clip(CircleShape).background(if (phrase.urgent) Ink.kumkum.copy(alpha = 0.16f) else Ink.bone.copy(alpha = 0.07f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(iconFor(phrase.id), null, tint = if (phrase.urgent) Ink.kumkumInk else Ink.bone, modifier = Modifier.size(circle * 0.5f))
            }
            Spacer(Modifier.height(if (big) 16.dp else if (roomy) 12.dp else 8.dp))
            Text(
                text,
                style = phraseStyle.copy(fontSize = sp.sp, lineHeight = (sp * 1.3f).sp),
                textAlign = TextAlign.Center,
                maxLines = TILE_LINES + 1, // a safety net only: the size above is chosen so it fits in three
                overflow = TextOverflow.Clip,
            )
            if (lang != Lang.EN) {
                Spacer(Modifier.height(4.dp))
                Text(phrase.say(Lang.EN), style = tileSub, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

enum class Tone { PRIMARY, YES, NO, QUIET, DANGER }

/** A large, calm button: the person may be tired, the caregiver may be in a hurry. */
@Composable
fun BigButton(text: String, tone: Tone = Tone.PRIMARY, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val (bg, fg) = when (tone) {
        Tone.PRIMARY -> Ink.turmeric to Ink.bg
        Tone.YES -> Ink.leaf to Ink.bg
        Tone.NO -> Ink.card to Ink.bone
        Tone.QUIET -> Color.Transparent to Ink.bone2
        Tone.DANGER -> Ink.kumkumDeep to Ink.bone
    }
    Box(
        modifier
            .height(60.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(if (enabled) bg else Ink.card)
            .then(if (tone == Tone.NO || tone == Tone.QUIET) Modifier.border(1.dp, Ink.rule2, RoundedCornerShape(30.dp)) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.button.copy(color = if (enabled) fg else Ink.mute, fontSize = 17.sp))
    }
}

/** A text box in the app's look: for names, keys and words to speak. */
@Composable
fun TextBox(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder, style = Type.phrase.copy(color = Ink.mute, fontSize = 17.sp)) },
        textStyle = Type.phrase.copy(fontSize = 17.sp),
        singleLine = singleLine,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
        shape = RoundedCornerShape(18.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Ink.turmeric,
            unfocusedBorderColor = Ink.rule2,
            cursorColor = Ink.turmeric,
        ),
        modifier = modifier,
    )
}

/** ●●●○○ examples taught, out of the most Mouna will ask for. */
@Composable
fun Dots(n: Int, of: Int = 5, color: Color = Ink.turmeric) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(of) { i ->
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (i < n) color else Ink.rule2))
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = Type.label, modifier = modifier.padding(bottom = 10.dp))
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Ink.raised)
            .border(1.dp, Ink.rule, RoundedCornerShape(22.dp))
            .padding(18.dp),
    ) { content() }
}
