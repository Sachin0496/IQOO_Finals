package app.mouna.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import app.mouna.app.engine.SignDemos
import kotlinx.coroutines.delay

// The 27 points (Isl.V): 0 nose, 1-2 eyes, 3-4 shoulders, 5-6 elbows, then each hand: wrist, thumb tip, index base,
// index tip, middle base, middle tip, ring base, ring tip, little base, little tip.
private const val LEFT_HAND = 7
private const val RIGHT_HAND = 17
private val FINGERS = listOf(1 to 0, 2 to 0, 3 to 2, 4 to 0, 5 to 4, 6 to 0, 7 to 6, 8 to 0, 9 to 8, 2 to 4, 4 to 6, 6 to 8)

/** A sign, played as a stick figure on a loop: head, shoulders, arms, and the fingers in turmeric. As a video shows the signer. */
@Composable
fun SignFigure(demo: SignDemos.Demo, modifier: Modifier = Modifier) {
    var i by remember(demo) { mutableIntStateOf(0) }
    LaunchedEffect(demo) {
        while (true) {
            delay(1000L / demo.fps)
            // hold the last pose a moment before starting again
            i = if (i + 1 >= demo.frames.size + demo.fps / 2) 0 else i + 1
        }
    }
    val body = Ink.bone
    val hand = Ink.turmeric
    Canvas(modifier) {
        val f = demo.frames[i.coerceAtMost(demo.frames.size - 1)]
        // shoulders at 40% of the height, one shoulder width = a quarter of the smaller side
        val unit = minOf(size.width, size.height) / 4.2f
        val cx = size.width / 2
        val cy = size.height * 0.42f
        fun at(v: Int): Offset? = f.getOrNull(v)?.let { Offset(cx + it[0] * unit, cy + it[1] * unit) }
        val stroke = unit * 0.11f
        // head: a circle around the nose, and the neck
        at(0)?.let { nose ->
            drawCircle(body, radius = unit * 0.42f, center = nose, style = Stroke(stroke))
            val l = at(3); val r = at(4)
            if (l != null && r != null) line(body, nose.copy(y = nose.y + unit * 0.42f), (l + r) / 2f, stroke)
        }
        // torso: shoulders, and the sides down to the bottom of the card
        val l = at(3); val r = at(4)
        if (l != null && r != null) {
            line(body, l, r, stroke)
            line(body, l, Offset(l.x + (r.x - l.x) * 0.08f, size.height), stroke, Color.Unspecified)
            line(body, r, Offset(r.x - (r.x - l.x) * 0.08f, size.height), stroke, Color.Unspecified)
        }
        // arms: shoulder -> elbow -> wrist (the hand's first point)
        for ((shoulder, elbow, wrist) in listOf(Triple(3, 5, LEFT_HAND), Triple(4, 6, RIGHT_HAND))) {
            val s = at(shoulder); val e = at(elbow); val w = at(wrist)
            if (s != null && e != null) line(body, s, e, stroke)
            if (e != null && w != null) line(body, e, w, stroke)
        }
        // hands
        for (base in listOf(LEFT_HAND, RIGHT_HAND)) for ((a, b) in FINGERS) {
            val p = at(base + a); val q = at(base + b)
            if (p != null && q != null) line(hand, p, q, stroke * 0.6f)
        }
    }
}

private fun DrawScope.line(c: Color, a: Offset, b: Offset, w: Float, fade: Color? = null) {
    drawLine(if (fade == null) c else c.copy(alpha = 0.35f), a, b, strokeWidth = w, cap = StrokeCap.Round)
}
