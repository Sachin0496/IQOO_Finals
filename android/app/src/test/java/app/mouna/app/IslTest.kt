package app.mouna.app

import app.mouna.app.engine.Isl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals("thank you", Isl.word("55.Thankyou"))
        assertEquals("good afternoon", Isl.word("52.Goodafternoon"))
        assertEquals("you all", Isl.word("46.you(plural)"))
        assertEquals("t-shirt", Isl.word("T-Shirt"))
    }

    /** Every label the app can show reads as words: no run-together INCLUDE spellings or brackets left. */
    @Test
    fun everyLabelReadsAsWords() {
        val labels = org.json.JSONArray(java.io.File("../../models/isl/isl_include_labels.json").readText())
        val words = List(labels.length()) { Isl.word(labels.getString(it)) }
        for (w in words) {
            assertFalse(w, '(' in w)
            assertFalse(w, w.length >= 11 && ' ' !in w && w !in setOf("transportation", "grandfather", "grandmother"))
        }
        assertEquals(262, words.toSet().size) // "Second" and "Second(Number)" are one word
    }

    @Test
    fun sameWordLabelsAddUp() {
        val g = Isl.top(floatArrayOf(0.5f, 0.3f, 0.2f), listOf("Second", "Second(Number)", "1.Dog"))
        assertEquals("second", g[0].word)
        assertEquals(0.8f, g[0].p, 1e-6f)
        assertEquals(2, g.size)
    }
}
