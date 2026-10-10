package app.mouna.app

import app.mouna.app.engine.SignSegmenter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app must cut signs where the Python reference does (models/isl/eval/signs.py, measured on INCLUDE's held-out
 * signers). Vectors: real keypoint streams of signers signing several words in a row (include_eval.py vectors).
 */
class SignSegmenterTest {
    private fun floats(a: Any?): FloatArray? = (a as? JSONArray)?.let { r -> FloatArray(r.length()) { r.getDouble(it).toFloat() } }

    @Test
    fun cutsTheSameSignsAsTheReference() {
        val o = JSONObject(javaClass.getResourceAsStream("/sign_segments.json")!!.readBytes().toString(Charsets.UTF_8))
        val streams = o.getJSONArray("streams")
        assertTrue(streams.length() > 0)
        for (s in 0 until streams.length()) {
            val v = streams.getJSONObject(s)
            val pts = v.getJSONArray("points")
            val wr = v.getJSONArray("wrists")
            val steps = List(pts.length()) { SignSegmenter.Step(floats(pts.opt(it)), floats(wr.opt(it))) }
            val seg = SignSegmenter()
            val got = mutableListOf<List<Int>>()
            for (step in steps) seg.push(step)?.let { frames -> got += frames.map { f -> steps.indexOfFirst { it.points === f } } }
            val want = v.getJSONArray("signs").let { a -> List(a.length()) { i -> a.getJSONArray(i).let { r -> List(r.length()) { r.getInt(it) } } } }
            assertEquals("stream $s", want, got)
        }
    }

    /** 27 points with shoulders at y = 1 and one shoulder width apart; wrists [d] shoulder widths below them. */
    private fun step(left: Float, right: Float = 2.8f) = SignSegmenter.Step(
        FloatArray(54).also { it[6] = 0f; it[7] = 1f; it[8] = 1f; it[9] = 1f },
        floatArrayOf(1f + left, 1f + right, 0.99f, 0.99f),
    )

    @Test
    fun restingHandsNeverStartASign() {
        val seg = SignSegmenter()
        repeat(400) { assertNull(seg.push(step(2.8f))) }
        assertFalse(seg.recording)
    }

    @Test
    fun aRaisedHandIsOneSignWithItsLeadInAndOut() {
        val seg = SignSegmenter()
        val out = mutableListOf<List<FloatArray>>()
        repeat(20) { seg.push(step(2.8f))?.let(out::add) }
        repeat(30) { seg.push(step(0.5f))?.let(out::add) }
        repeat(20) { seg.push(step(2.8f))?.let(out::add) }
        assertEquals(1, out.size)
        assertEquals(SignSegmenter.PAD + 30 + SignSegmenter.PAD, out[0].size)
    }

    @Test
    fun aBlipIsNotASign() {
        val seg = SignSegmenter()
        val out = mutableListOf<List<FloatArray>>()
        repeat(10) { seg.push(step(2.8f))?.let(out::add) }
        repeat(3) { seg.push(step(0.5f))?.let(out::add) } // starts, but too short once the hands come down
        repeat(20) { seg.push(step(2.8f))?.let(out::add) }
        assertTrue(out.isEmpty())
    }

    @Test
    fun noBodyOrHiddenWristIsNotRaised() {
        assertFalse(SignSegmenter.raised(SignSegmenter.Step(null, floatArrayOf(0f, 0f, 1f, 1f))))
        assertFalse(SignSegmenter.raised(step(0.5f).let { SignSegmenter.Step(it.points, floatArrayOf(1.5f, 1.5f, 0.1f, 0.1f)) }))
        assertTrue(SignSegmenter.raised(step(2.8f, right = 0.2f)))
    }
}
