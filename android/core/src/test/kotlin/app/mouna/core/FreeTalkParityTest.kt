package app.mouna.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Replays harness/vectors/freetalk.json (python -m mouna_encoder avsr-vectors): free talk input and decoding as Python. */
class FreeTalkParityTest {
    private val v = JSONObject(File(System.getProperty("mouna.freetalk")).readText())

    private fun floats(a: JSONArray) = FloatArray(a.length()) { a.getDouble(it).toFloat() }
    private fun doubles(a: JSONArray) = DoubleArray(a.length()) { a.getDouble(it) }
    private fun ints(a: JSONArray) = IntArray(a.length()) { a.getInt(it) }

    @Test
    fun cropMatrixMatchesPython() {
        val cases = v.getJSONArray("crop")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val m = FreeTalk.cropMatrix(FreeTalk.stablePoints(floats(c.getJSONArray("xs")), floats(c.getJSONArray("ys"))))
            assertArrayEquals("case $i", doubles(c.getJSONArray("matrix")), m, 1e-3)
        }
    }

    @Test
    fun inputMatchesPython() {
        val c = v.getJSONObject("input")
        val t = c.getJSONArray("t_ms").let { a -> LongArray(a.length()) { a.getLong(it) } }
        val n = FreeTalk.CROP * FreeTalk.CROP
        val crops = List(t.size) { f -> ByteArray(n) { ((37 * f + 11 * it) % 256).toByte() } } // as the Python vectors
        val (x, valid, frames) = FreeTalk.input(crops, t)
        assertEquals(c.getInt("frames"), frames)
        assertEquals(c.getInt("bucket"), valid.size)
        assertEquals(c.getDouble("x_sum"), x.sumOf { it.toDouble() }, 1e-2)
        val r = FreeTalk.ROI
        assertArrayEquals(floats(c.getJSONArray("x_first")), x.copyOfRange(0, 8), 1e-5f)
        val last = (frames - 1) * r * r + (r - 1) * r + r - 8
        assertArrayEquals(floats(c.getJSONArray("x_last")), x.copyOfRange(last, last + 8), 1e-5f)
    }

    @Test
    fun ctcMatchesPython() {
        val cases = v.getJSONArray("ctc")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val frames = c.getInt("frames")
            val units = c.getInt("units")
            val logp = floats(c.getJSONArray("logp"))
            assertArrayEquals("greedy $i", ints(c.getJSONArray("greedy")), Ctc.greedy(logp, frames, units))
            val want = c.getJSONArray("beam")
            val got = Ctc.prefixBeam(logp, frames, units, beam = 6, prune = -8f, top = 4)
            assertEquals("beam size $i", want.length(), got.size)
            for (k in 0 until want.length()) {
                val w = want.getJSONObject(k)
                assertArrayEquals("beam $i/$k ids", ints(w.getJSONArray("ids")), got[k].ids)
                assertEquals("beam $i/$k logp", w.getDouble("logp"), got[k].logp, 1e-4)
            }
        }
    }

    @Test
    fun detokenizeJoinsPieces() {
        val d = v.getJSONObject("detok")
        val toks = d.getJSONArray("tokens").let { a -> List(a.length()) { a.getString(it) } }
        assertEquals(d.getString("text"), Ctc.detokenize(ints(d.getJSONArray("ids")), toks))
    }
}

class JointBeamParityTest {
    private val v = JSONObject(File(System.getProperty("mouna.freetalk")).readText()).getJSONObject("joint")

    @Test
    fun jointSearchMatchesPython() {
        val frames = v.getInt("frames")
        val units = v.getInt("units")
        val maxLen = v.getInt("max_len")
        val ctc = v.getJSONArray("ctc").let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
        val tab = v.getJSONArray("table").let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
        val decode = { ps: List<IntArray> ->
            ps.map { p -> val o = (p.last() * (maxLen + 1) + p.size - 1) * units; tab.copyOfRange(o, o + units) }
        }
        val got = JointBeam.search(ctc, frames, units, sos = units - 1, decode = decode, beam = 3, preBeam = 4, maxLen = maxLen)
        val want = v.getJSONArray("hyps")
        assertEquals(want.length(), got.size)
        for (k in 0 until want.length()) {
            val w = want.getJSONObject(k)
            val ids = w.getJSONArray("ids").let { a -> IntArray(a.length()) { a.getInt(it) } }
            assertArrayEquals("hyp $k ids", ids, got[k].ids)
            assertEquals("hyp $k score", w.getDouble("score"), got[k].score, 1e-6)
        }
    }
}
