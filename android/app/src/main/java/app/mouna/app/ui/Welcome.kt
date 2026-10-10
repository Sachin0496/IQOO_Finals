package app.mouna.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val welcomeWordmark get() = Type.title.copy(fontSize = 30.sp, fontStyle = FontStyle.Italic)
private val welcomeTitle get() = Type.display.copy(fontSize = 46.sp, lineHeight = 52.sp)
private val welcomeHead get() = Type.phrase.copy(fontSize = 20.sp, lineHeight = 26.sp)
private val welcomeBody get() = Type.body.copy(fontSize = 16.sp, lineHeight = 22.sp)

/**
 * First launch, before the camera permission is asked: what Mouna is, in one screen. The three lines say how it is
 * used and that nothing leaves the phone, so the camera question that follows has an answer.
 */
@Composable
fun Welcome(onContinue: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    Box(
        Modifier
            .fillMaxSize()
            .background(Ink.bg)
            .drawBehind {
                // A soft turmeric glow behind the title: the one warm light on the page.
                drawCircle(
                    Brush.radialGradient(
                        listOf(Ink.turmeric.copy(alpha = 0.16f), Ink.turmeric.copy(alpha = 0f)),
                        center = Offset(size.width * 0.18f, size.height * 0.24f),
                        radius = size.width * 0.9f,
                    ),
                    radius = size.width * 0.9f,
                    center = Offset(size.width * 0.18f, size.height * 0.24f),
                )
            }
            .systemBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("mouna", style = welcomeWordmark)
                Box(Modifier.padding(start = 3.dp, top = 10.dp).size(6.dp).clip(CircleShape).background(Ink.turmeric))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                Spacer(Modifier.height(24.dp))
                Reveal(shown, 0) {
                    Text(
                        buildAnnotatedString {
                            append("Mouna gives you a ")
                            withStyle(SpanStyle(color = Ink.turmeric, fontStyle = FontStyle.Italic)) { append("voice") }
                            append(".")
                        },
                        style = welcomeTitle,
                    )
                }
                Spacer(Modifier.height(36.dp))
                Reveal(shown, 1) { Point(MounaIcons.RecordVoiceOver, "Mouth or sign", "Mouna speaks it aloud, in your caregiver’s language.") }
                Spacer(Modifier.height(24.dp))
                Reveal(shown, 2) { Point(MounaIcons.School, "It learns you", "Sentences you confirm are offered first next time.") }
                Spacer(Modifier.height(24.dp))
                Reveal(shown, 3) { Point(Icons.Rounded.Lock, "Private by design", "Nothing you show the camera leaves this phone.") }
                Spacer(Modifier.height(24.dp))
            }
            Reveal(shown, 4) {
                Column {
                    BigButton("Continue", Tone.PRIMARY, Modifier.fillMaxWidth(), onClick = onContinue)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Next, Mouna asks to use the camera.",
                        style = Type.hint,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

/** Each part of the page settles in a beat after the one before it. */
@Composable
private fun Reveal(shown: Boolean, order: Int, content: @Composable () -> Unit) {
    val a by animateFloatAsState(
        if (shown) 1f else 0f,
        tween(520, delayMillis = 120 + order * 140, easing = FastOutSlowInEasing),
        label = "reveal$order",
    )
    Box(Modifier.alpha(a).padding(top = ((1f - a) * 14f).dp)) { content() }
}

@Composable
private fun Point(icon: ImageVector, head: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(Ink.bone.copy(alpha = 0.07f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Ink.turmeric, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(head, style = welcomeHead)
            Spacer(Modifier.height(2.dp))
            Text(body, style = welcomeBody)
        }
    }
}
