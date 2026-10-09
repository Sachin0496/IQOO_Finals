package app.mouna.core

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

/** The app's preprocessing must build the same encoder input as PyTorch (encoder/mouna_encoder/selftest.py). */
class SelfTestParityTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/mouna/selftest/$name")!!.readBytes()

    @Test
    fun encoderInputMatchesPyTorch() {
        val d = JSONObject(String(res("selftest.json")))
        val t = d.getJSONArray("t_ms").let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
        val x = EncoderInput.build(res("clip.u8"), d.getInt("frames"), d.getInt("size"), t)
        assertArrayEquals(EncoderInput.SHAPE, d.getJSONArray("input_shape").let { a -> IntArray(a.length()) { a.getInt(it) } })
        val idx = d.getJSONArray("input_sample_index")
        val want = d.getJSONArray("input_sample_value")
        var worst = 0.0
        for (i in 0 until idx.length()) worst = maxOf(worst, abs(x[idx.getInt(i)] - want.getDouble(i)))
        assert(worst < 1e-5) { "max abs diff $worst" }
        assertEquals(d.getDouble("input_mean"), x.average(), 1e-5)
    }

    @Test
    fun int8EmbeddingPassesTheSelfTestAgainstFp32() {
        val d = JSONObject(String(res("selftest.json")))
        val f = d.getJSONArray("embedding_fp32").let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
        val q = d.getJSONArray("embedding_int8").let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
        assert(cosine(f, q) >= d.getDouble("pass_cosine"))
    }
}

class SelfTestLoadTest {
    @Test
    fun bundledSelfTestLoadsAndPassesOnItsOwnEmbedding() {
        val s = SelfTest.load()
        assertEquals(EncoderInput.WINDOW * EncoderInput.STACK * EncoderInput.INPUT * EncoderInput.INPUT, s.input.size)
        assert(s.passes(s.expected) && s.passes(s.expectedInt8, int8 = true))
    }
}
