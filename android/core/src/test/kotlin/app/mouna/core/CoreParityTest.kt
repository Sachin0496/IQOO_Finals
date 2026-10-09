package app.mouna.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/** Replays harness/vectors/core.json (python -m mouna_harness vectors): Kotlin must decide exactly as Python. */
class CoreParityTest {
    private val v = JSONObject(File(System.getProperty("mouna.vectors")).readText())

    private fun floats(a: JSONArray) = FloatArray(a.length()) { a.getDouble(it).toFloat() }
    private fun strings(a: JSONArray) = List(a.length()) { a.getString(it) }
    private fun num(o: Any): Double = when (o) {
        "inf" -> Double.POSITIVE_INFINITY
        "-inf" -> Double.NEGATIVE_INFINITY
        else -> (o as Number).toDouble()
    }
    private fun close(msg: String, want: Double, got: Double) {
        if (want.isInfinite() || got.isInfinite()) return assertEquals(msg, want, got, 0.0)
        assert(abs(want - got) <= 1e-9 * max(1.0, abs(want))) { "$msg: want $want got $got" }
    }

    private fun learner(): Learner {
        val l = Learner()
        val s = v.getJSONArray("samples")
        for (i in 0 until s.length()) s.getJSONObject(i).let { l.addSample(it.getString("intent"), floats(it.getJSONArray("x"))) }
        val n = v.getJSONArray("negatives")
        for (i in 0 until n.length()) l.addNegative(floats(n.getJSONArray(i)))
        return l
    }

    private fun check(msg: String, want: JSONObject, got: Decision) {
        assertEquals("$msg kind", want.getString("kind"), got.kind.name.lowercase())
        assertEquals("$msg options", strings(want.getJSONArray("options")), got.options)
        assertEquals("$msg why", want.getString("why"), got.why)
        assertEquals("$msg maybe_none", want.getBoolean("maybe_none"), got.maybeNone)
    }

    @Test
    fun learnerRankingAndDecisionsMatchPython() {
        val l = learner()
        close("tau", v.getDouble("tau"), l.tau())
        val sep = v.getJSONArray("separability")
        val got = l.separability()
        assertEquals(sep.length(), got.size)
        for (i in 0 until sep.length()) {
            val w = sep.getJSONObject(i)
            assertEquals(w.getString("a"), got[i].a)
            assertEquals(w.getString("b"), got[i].b)
            close("gap", w.getDouble("gap"), got[i].gap)
            assertEquals(w.getString("level"), got[i].level.name.lowercase())
        }
        val tiersJ = v.getJSONObject("tiers")
        val tiers = tiersJ.keys().asSequence().associateWith { Tier.valueOf(tiersJ.getString(it)) }
        val priorJ = v.getJSONObject("prior")
        val prior = priorJ.keys().asSequence().associateWith { priorJ.getDouble(it) }
        val wide = v.getDouble("wide_q_set")
        val qs = v.getJSONArray("queries")
        for (i in 0 until qs.length()) {
            val q = qs.getJSONObject(i)
            val r = l.predict(floats(q.getJSONArray("x")))
            val w = q.getJSONObject("ranked")
            assertEquals("q$i intents", strings(w.getJSONArray("intents")), r.intents)
            val ws = w.getJSONArray("scores")
            for (j in 0 until ws.length()) close("q$i score $j", ws.getDouble(j), r.scores[j])
            close("q$i negative", num(w.get("negative")), r.negative)
            check("q$i default", q.getJSONObject("default"), decide(r))
            check("q$i careful", q.getJSONObject("careful"), decide(r, careful = true))
            check("q$i tiers", q.getJSONObject("tiers"), decide(r, tiers = tiers))
            check("q$i prior", q.getJSONObject("prior"), decide(r, prior = prior))
            check("q$i wide", q.getJSONObject("wide"), decide(r, qSet = wide))
            check("q$i wide_prior", q.getJSONObject("wide_prior"), decide(r, prior = prior, qSet = wide))
        }
    }

    @Test
    fun activeTeachingMatchesPython() {
        val a = v.getJSONObject("active")
        val pack = strings(a.getJSONArray("pack"))
        val l = Learner()
        val steps = a.getJSONArray("steps")
        for (i in 0 until steps.length()) {
            val s = steps.getJSONObject(i)
            val req = l.nextToTeach(pack, minShots = a.getInt("min_shots"))
            assertEquals("step $i intent", s.getString("intent"), req?.intent)
            assertEquals("step $i reason", s.getString("reason"), req?.reason)
            val ok = l.teach(req!!.intent, floats(s.getJSONArray("x")))
            assertEquals("step $i check", if (s.isNull("check")) null else s.getBoolean("check"), ok)
        }
    }
}
