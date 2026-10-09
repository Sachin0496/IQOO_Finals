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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.mouna.app.engine.Lang
import app.mouna.app.engine.Live
import app.mouna.app.engine.Phrase
import kotlinx.coroutines.flow.StateFlow

/** The camera, softly framed, with the lip contour drawn in turmeric while Mouna is hearing. */
@Composable
fun CameraCard(
    liveFlow: StateFlow<Live>,
    bind: (PreviewView) -> Unit,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.(Live) -> Unit = {},
) {
    val live by liveFlow.collectAsState() // collected here, so only the card redraws at camera rate
    val ring by animateColorAsState(if (live.hearing) Ink.turmeric else Ink.rule, tween(220), label = "ring")
    Box(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Ink.raised)
            .border(1.5.dp, ring, RoundedCornerShape(28.dp)),
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
        LipLine(live)
        overlay(live)
    }
}

/** Outer lip contour over the mirrored, fill-centre preview. */
@Composable
private fun LipLine(live: Live) {
    if (!live.face || live.outer.isEmpty() || live.imageW == 0) return
    val color = if (live.hearing) Ink.turmeric else Ink.bone.copy(alpha = 0.45f)
    Canvas(Modifier.fillMaxSize()) {
        val s = maxOf(size.width / live.imageW, size.height / live.imageH)
        val dx = (size.width - live.imageW * s) / 2
        val dy = (size.height - live.imageH * s) / 2
        fun at(p: Pair<Float, Float>) = Offset(size.width - (p.first * live.imageW * s + dx), p.second * live.imageH * s + dy)
        val path = Path().apply {
            moveTo(at(live.outer[0]).x, at(live.outer[0]).y)
            for (p in live.outer.drop(1)) lineTo(at(p).x, at(p).y)
            close()
        }
        drawPath(path, color, style = Stroke(width = if (live.hearing) 3.dp.toPx() else 1.5.dp.toPx(), join = StrokeJoin.Round))
    }
}

/** Small status capsule: a dot and a few words. */
@Composable
fun Pill(text: String, dot: Color = Ink.mute, pulse: Boolean = false, modifier: Modifier = Modifier) {
    val alpha = if (pulse) {
        val t = rememberInfiniteTransition(label = "pulse")
        t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "a").value
    } else 1f
    Row(
        modifier
            .clip(CircleShape)
            .background(Ink.bg.copy(alpha = 0.72f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot.copy(alpha = alpha)))
        Spacer(Modifier.width(8.dp))
        Text(text, style = Type.mono.copy(fontSize = 12.sp, color = Ink.bone))
    }
}

/** A phrase as a picture: icon in a soft circle, the words in the caregiver's language, English beneath. */
@Composable
fun PhraseTile(
    phrase: Phrase,
    lang: Lang,
    modifier: Modifier = Modifier,
    big: Boolean = false,
    selected: Boolean = false,
    accent: Color = Ink.turmeric,
    onClick: (() -> Unit)? = null,
) {
    val border by animateColorAsState(if (selected) accent else Ink.rule, tween(160), label = "border")
    Column(
        modifier
            .clip(RoundedCornerShape(if (big) 28.dp else 22.dp))
            .background(if (selected) accent.copy(alpha = 0.12f) else Ink.card)
            .border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(if (big) 28.dp else 22.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(if (big) 20.dp else 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val circle: Dp = if (big) 92.dp else 52.dp
        Box(
            Modifier.size(circle).clip(CircleShape).background(if (phrase.urgent) Ink.kumkum.copy(alpha = 0.16f) else Ink.bone.copy(alpha = 0.07f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(iconFor(phrase.id), null, tint = if (phrase.urgent) Ink.kumkum else Ink.bone, modifier = Modifier.size(circle * 0.5f))
        }
        Spacer(Modifier.height(if (big) 16.dp else 10.dp))
        Text(
            phrase.say(lang),
            style = if (big) Type.phrase.copy(fontSize = 24.sp, lineHeight = 28.sp) else Type.phrase.copy(fontSize = 15.sp, lineHeight = 19.sp),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (lang != Lang.EN) {
            Spacer(Modifier.height(4.dp))
            Text(phrase.say(Lang.EN), style = Type.label.copy(letterSpacing = 0.sp), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

enum class Tone { PRIMARY, YES, NO, QUIET }

/** A large, calm button: the person may be tired, the caregiver may be in a hurry. */
@Composable
fun BigButton(text: String, tone: Tone = Tone.PRIMARY, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val (bg, fg) = when (tone) {
        Tone.PRIMARY -> Ink.turmeric to Ink.bg
        Tone.YES -> Ink.leaf to Ink.bg
        Tone.NO -> Ink.card to Ink.bone
        Tone.QUIET -> Color.Transparent to Ink.bone2
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
