package app.mouna.app

import app.mouna.app.sense.DoubleBlink
import app.mouna.app.sense.Gesture
import app.mouna.app.sense.HeadGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class HeadTest {
    private fun run(g: HeadGesture, yaw: (Int) -> Float, pitch: (Int) -> Float, frames: Int = 60): List<Gesture> =
        (0 until frames).mapNotNull { i -> g.push(yaw(i), pitch(i), i * 33L) }

    @Test
    fun nodIsYes() {
        // still for 10 frames, then the head dips 0.08 eye distances and comes back over ~0.6 s
        val out = run(HeadGesture(), { 0f }, { i -> if (i in 10..28) (0.08 * sin(PI * (i - 10) / 18)).toFloat() else 0f })
        assertEquals(listOf(Gesture.NOD), out)
    }

    @Test
    fun shakeIsNo() {
        val out = run(HeadGesture(), { i -> if (i in 10..34) (14 * sin(2 * PI * (i - 10) / 24)).toFloat() else 0f }, { 0f })
        assertEquals(listOf(Gesture.SHAKE), out)
    }

    @Test
    fun stillOrMouthingIsNothing() {
        // small wobbles while talking stay under both thresholds
        val out = run(HeadGesture(), { i -> (2 * sin(i / 3.0)).toFloat() }, { i -> (0.01 * sin(i / 4.0)).toFloat() }, 200)
        assertTrue(out.isEmpty())
    }

    private fun blinks(closedFrames: List<IntRange>, frames: Int = 90): Boolean {
        val b = DoubleBlink()
        var fired = false
        for (i in 0 until frames) if (b.push(if (closedFrames.any { i in it }) 0.08f else 0.3f, i * 33L)) fired = true
        return fired
    }

    @Test
    fun twoQuickBlinksAreYesOneIsNot() {
        assertTrue(blinks(listOf(20..24, 34..38)))
        assertFalse(blinks(listOf(20..24)))
        assertFalse(blinks(listOf(20..24, 70..74))) // too far apart: two natural blinks
    }
}
