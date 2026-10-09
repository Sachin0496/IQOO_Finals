package app.mouna.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Mirrors lab/src/core/core.test.ts so the three implementations agree. */
class LipGeometryTest {
    private fun fakeFace(open: Float, dx: Float = 0f, scale: Float = 1f, roll: Float = 0f): Pair<FloatArray, FloatArray> {
        val xs = FloatArray(478)
        val ys = FloatArray(478)
        fun put(i: Int, x: Float, y: Float) {
            val c = cos(roll)
            val s = sin(roll)
            xs[i] = 0.5f + dx + scale * (x * c - y * s)
            ys[i] = 0.5f + scale * (x * s + y * c)
        }
        put(33, -0.1f, -0.15f)
        put(263, 0.1f, -0.15f)
        put(1, 0f, -0.05f)
        Lips.ALL.forEachIndexed { k, idx ->
            val ring = if (k < 20) 1f else 0.6f
            val a = (2 * PI * (k % 20) / 20).toFloat()
            put(idx, 0.06f * ring * cos(a), 0.1f + (0.02f + open) * ring * sin(a))
        }
        put(61, -0.06f, 0.1f)
        put(291, 0.06f, 0.1f)
        put(13, 0f, 0.1f - open * 0.6f)
        put(14, 0f, 0.1f + open * 0.6f)
        return xs to ys
    }

    @Test
    fun invariantToTranslationScaleAndRoll() {
        val (ax, ay) = fakeFace(0.02f)
        val (bx, by) = fakeFace(0.02f, dx = 0.1f, scale = 0.7f, roll = 0.3f)
        val a = analyseFace(ax, ay, 1000, 1000)
        val b = analyseFace(bx, by, 1000, 1000)
        for (i in a.features.indices) assertEquals(a.features[i], b.features[i], 1e-3f)
        assertEquals(0.3f, b.roll, 1e-4f)
    }

    @Test
    fun apertureGrowsWhenTheMouthOpens() {
        val (cx, cy) = fakeFace(0f)
        val (ox, oy) = fakeFace(0.04f)
        assertTrue(analyseFace(ox, oy, 640, 480).aperture > analyseFace(cx, cy, 640, 480).aperture)
    }

    @Test
    fun gateOpensOnMotionAndClosesAfterHangover() {
        val g = ActivityGate()
        repeat(30) { assertFalse(g.push(0.001f, 0.05f)) }
        var opened = false
        repeat(10) { opened = g.push(0.03f, 0.2f) || opened }
        assertTrue(opened)
        var closed = false
        repeat(40) { if (!g.push(0f, 0.05f)) closed = true }
        assertTrue(closed)
    }
}
