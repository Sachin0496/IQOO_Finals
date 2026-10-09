package app.mouna.core

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Free talk decoding (docs/open-vocab-plan.md): CTC over Auto-AVSR's 5,049 units (0 blank, 1..5047 SentencePiece
 * pieces, 5048 sos/eos). Port of `encoder/mouna_encoder/avsr.py` (ctc_greedy, ctc_prefix_beam, detokenize).
 */
object Ctc {
    const val BLANK = 0

    data class Hyp(val ids: IntArray, val logp: Double)

    /** Best path: argmax per frame, repeats merged, blanks dropped. [logp] is (frames x units) row-major. */
    fun greedy(logp: FloatArray, frames: Int, units: Int): IntArray {
        val out = ArrayList<Int>()
        var prev = BLANK
        for (t in 0 until frames) {
            var best = 0
            var bv = Float.NEGATIVE_INFINITY
            val o = t * units
            for (k in 0 until units) if (logp[o + k] > bv) { bv = logp[o + k]; best = k }
            if (best != prev && best != BLANK) out += best
            prev = best
        }
        return out.toIntArray()
    }

    /**
     * Prefix beam search. Units below [prune] (log-prob) in a frame are skipped, which keeps it to a handful per frame.
     * Returns up to [beam] hypotheses, best first.
     */
    fun prefixBeam(logp: FloatArray, frames: Int, units: Int, beam: Int = 16, prune: Float = -12f): List<Hyp> {
        var beams = HashMap<Prefix, DoubleArray>().apply { put(Prefix(IntArray(0)), doubleArrayOf(0.0, NEG)) }
        val cand = IntArray(units)
        for (t in 0 until frames) {
            val o = t * units
            var nc = 0
            for (k in 0 until units) if (logp[o + k] > prune) cand[nc++] = k
            val next = HashMap<Prefix, DoubleArray>(beams.size * 4)
            fun add(p: Prefix, pb: Double, pnb: Double) {
                val cur = next[p]
                if (cur == null) next[p] = doubleArrayOf(pb, pnb) else { cur[0] = lse(cur[0], pb); cur[1] = lse(cur[1], pnb) }
            }
            for ((prefix, v) in beams) {
                val (pb, pnb) = v[0] to v[1]
                val total = lse(pb, pnb)
                val last = if (prefix.ids.isEmpty()) -1 else prefix.ids.last()
                for (c in 0 until nc) {
                    val k = cand[c]
                    val p = logp[o + k].toDouble()
                    if (k == BLANK) { add(prefix, total + p, NEG); continue }
                    val ext = Prefix(prefix.ids + k)
                    if (k == last) {
                        add(ext, NEG, pb + p) // a repeat needs a blank in between
                        add(prefix, NEG, pnb + p) // or it continues the same unit
                    } else {
                        add(ext, NEG, total + p)
                    }
                }
            }
            beams = HashMap<Prefix, DoubleArray>().apply {
                next.entries.sortedByDescending { lse(it.value[0], it.value[1]) }.take(beam).forEach { put(it.key, it.value) }
            }
        }
        return beams.entries.map { Hyp(it.key.ids, lse(it.value[0], it.value[1])) }.sortedByDescending { it.logp }
    }

    /** Unit ids -> text: pieces joined, "▁" is a word start. Blank and sos/eos are skipped. */
    fun detokenize(ids: IntArray, tokens: List<String>): String =
        ids.filter { it > 0 && it < tokens.size - 1 }.joinToString("") { tokens[it] }.replace('▁', ' ').trim()

    private const val NEG = Double.NEGATIVE_INFINITY

    private fun lse(a: Double, b: Double): Double {
        val m = max(a, b)
        return if (m == NEG) NEG else m + ln(exp(a - m) + exp(b - m))
    }

    private class Prefix(val ids: IntArray) {
        private val h = ids.contentHashCode()
        override fun hashCode() = h
        override fun equals(other: Any?) = other is Prefix && other.ids.contentEquals(ids)
    }
}
