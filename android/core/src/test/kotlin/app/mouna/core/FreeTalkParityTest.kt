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
    fun activeSpanMatchesPython() {
        val cases = v.getJSONArray("span")
        val n = FreeTalk.CROP * FreeTalk.CROP
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val a = c.getInt("still_a"); val moving = c.getInt("moving"); val amp = c.getInt("amp")
            val total = a + moving + c.getInt("still_b")
            val crops = List(total) { f ->
                ByteArray(n) { px ->
                    val base = (px * 7) % 200
                    val wobble = if (f >= a && f < a + moving) (amp * kotlin.math.sin(f * 1.3 + px * 0.01)).toLong().toInt() else 0
                    (base + wobble).coerceIn(0, 255).toByte()
                }
            }
            val span = FreeTalk.activeSpan(crops)
            assertEquals("span $i start", c.getInt("start"), span.first)
            assertEquals("span $i end", c.getInt("end"), span.last + 1)
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

class PersonalParityTest {
    private val v = JSONObject(File(System.getProperty("mouna.freetalk")).readText())
    private val p = v.getJSONObject("personal")
    private fun ints(a: JSONArray) = IntArray(a.length()) { a.getInt(it) }
    private val joint = v.getJSONObject("joint")
    private val frames = joint.getInt("frames")
    private val units = joint.getInt("units")
    private val ctc = joint.getJSONArray("ctc").let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }

    @Test
    fun tokenizerMatchesPython() {
        val toks = p.getJSONArray("tokens").let { a -> List(a.length()) { a.getString(it) } }
        val pj = p.getJSONObject("pieces")
        val pieces = pj.keys().asSequence().associateWith { pj.getDouble(it).toFloat() }
        val spm = Spm(pieces, toks)
        val cases = p.getJSONArray("spm")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertArrayEquals("\"${c.getString("text")}\"", ints(c.getJSONArray("ids")), spm.encode(c.getString("text")))
        }
    }

    @Test
    fun ctcSequenceAndScoreMatchPython() {
        val seq = p.getJSONArray("ctc_seq")
        for (i in 0 until seq.length()) {
            val c = seq.getJSONObject(i)
            assertEquals("ctc seq $i", c.getDouble("logp"), Personal.ctcSequence(ctc, frames, units, ints(c.getJSONArray("ids"))), 1e-6)
        }
        val sc = p.getJSONArray("score")
        for (i in 0 until sc.length()) {
            val c = sc.getJSONObject(i)
            val att = c.getJSONArray("att").let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
            assertEquals("score $i", c.getDouble("score"), Personal.score(ctc, frames, units, ints(c.getJSONArray("ids")), att), 1e-6)
        }
    }

    @Test
    fun rankMatchesPython() {
        val cases = v.getJSONArray("personal_rank")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertEquals("rank $i", c.getDouble("rank"), Personal.rank(c.getDouble("score"), c.getDouble("prior")), 1e-9)
        }
    }

    @Test
    fun mergeMatchesPython() {
        fun opts(a: JSONArray, personal: Boolean) = List(a.length()) {
            a.getJSONArray(it).let { o -> Personal.Option(o.getString(0), o.getDouble(1), personal, if (o.length() > 2) o.getDouble(2) else o.getDouble(1)) }
        }
        val cases = p.getJSONArray("merge")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val got = Personal.merge(
                opts(c.getJSONArray("open"), false), opts(c.getJSONArray("listed"), true),
                firstWithin = c.optDouble("first_within", Personal.FIRST_WITHIN),
            )
            val want = c.getJSONArray("out")
            assertEquals("merge $i size", want.length(), got.size)
            for (k in 0 until want.length()) {
                val w = want.getJSONArray(k)
                assertEquals("merge $i/$k text", w.getString(0), got[k].text)
                assertEquals("merge $i/$k personal", w.getBoolean(2), got[k].personal)
            }
        }
    }
}
