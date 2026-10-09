package app.mouna.app

import app.mouna.app.engine.Isl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** The app's ISL preprocessing must match OpenHands' Python exactly, or the model sees different inputs. */
class IslTest {
    @Test
    fun normalisationMatchesOpenHands() {
        val o = JSONObject(javaClass.getResourceAsStream("/isl_norm.json")!!.readBytes().toString(Charsets.UTF_8))
        val f = o.getJSONArray("frames")
        val frames = List(f.length()) { t -> f.getJSONArray(t).let { r -> FloatArray(r.length()) { r.getDouble(it).toFloat() } } }
        val want = o.getJSONArray("normalised")
        val got = Isl.normalise(frames)
        assertEquals(want.length(), got.size)
        for (i in got.indices) assertEquals("index $i", want.getDouble(i), got[i].toDouble(), 1e-4)
    }

    @Test
    fun labelsBecomeWords() {
        assertEquals("sad", Isl.word("4.sad"))
        assertEquals("energy", Isl.word("10.Energy"))
    }
}
