package app.mouna.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln1p
import kotlin.math.max

/**
 * Free talk: joint CTC / attention beam search, as Auto-AVSR decodes (0.9 attention + 0.1 CTC prefix score).
 * Port of `encoder/mouna_encoder/avsr.py` joint_search; pinned by harness/vectors/freetalk.json.
 *
 * The CTC head alone reads silent mouthing as one word ("HELLO"); the attention decoder reads the sentence
 * ("HELLO HOW ARE YOU"), so the phone needs both.
 */
object JointBeam {
    const val CTC_WEIGHT = 0.1
    private const val NEG = -1e30

    data class Hyp(val ids: IntArray, val score: Double)

    private class Run(val toks: IntArray, val score: Double, val psi: Double, val rn: DoubleArray, val rb: DoubleArray)

    /**
     * [ctc]: CTC log-probs of the real frames (frames x units, row-major). [decode]: next-token attention log-probs for
     * each prefix (each starts with [sos]). Returns ended hypotheses (ids without sos/eos) best first.
     */
    fun search(
        ctc: FloatArray,
        frames: Int,
        units: Int,
        sos: Int,
        decode: (List<IntArray>) -> List<FloatArray>,
        beam: Int = 8,
        preBeam: Int = 12,
        maxLen: Int = 47,
        ctcWeight: Double = CTC_WEIGHT,
    ): List<Hyp> {
        val eos = sos
        val rb0 = DoubleArray(frames)
        var acc = 0.0
        for (t in 0 until frames) { acc += ctc[t * units + Ctc.BLANK].toDouble(); rb0[t] = acc }
        var running = listOf(Run(intArrayOf(sos), 0.0, 0.0, DoubleArray(frames) { NEG }, rb0))
        val ended = ArrayList<Hyp>()
        val order = IntArray(preBeam)
        repeat(maxLen) {
            val att = decode(running.map { it.toks })
            data class Cand(val s: Double, val hi: Int, val c: Int, val psi: Double, val rn: DoubleArray?, val rb: DoubleArray?)
            val cands = ArrayList<Cand>()
            for ((hi, h) in running.withIndex()) {
                val row = att[hi]
                val empty = h.toks.size == 1
                // the preBeam best tokens, best first, ties to the lower id
                var no = 0
                for (k in 0 until units) {
                    val p = row[k]
                    if (no == preBeam && p <= row[order[no - 1]]) continue
                    var i = if (no < preBeam) no++ else no - 1
                    while (i > 0 && row[order[i - 1]] < p) { order[i] = order[i - 1]; i-- }
                    order[i] = k
                }
                for (j in 0 until no) {
                    val c = order[j]
                    if (c == Ctc.BLANK) continue
                    if (c == eos) {
                        val psi = lae(h.rn[frames - 1], h.rb[frames - 1])
                        cands += Cand(h.score + (1 - ctcWeight) * row[c] + ctcWeight * (psi - h.psi), hi, c, psi, null, null)
                    } else {
                        val rn = DoubleArray(frames) { NEG }
                        val rb = DoubleArray(frames) { NEG }
                        if (empty) rn[0] = ctc[c].toDouble()
                        var psi = rn[0]
                        val last = h.toks.last()
                        for (t in 1 until frames) {
                            val phi = if (c == last && !empty) h.rb[t - 1] else lae(h.rn[t - 1], h.rb[t - 1])
                            val xc = ctc[t * units + c].toDouble()
                            rn[t] = lae(rn[t - 1], phi) + xc
                            rb[t] = lae(rn[t - 1], rb[t - 1]) + ctc[t * units + Ctc.BLANK]
                            psi = lae(psi, phi + xc)
                        }
                        cands += Cand(h.score + (1 - ctcWeight) * row[c] + ctcWeight * (psi - h.psi), hi, c, psi, rn, rb)
                    }
                }
            }
            cands.sortWith(compareBy<Cand>({ -it.s }, { it.hi }, { it.c }))
            val next = ArrayList<Run>()
            for (cd in cands.take(beam)) {
                val toks = running[cd.hi].toks
                if (cd.c == eos) ended += Hyp(toks.copyOfRange(1, toks.size), cd.s)
                else next += Run(toks + cd.c, cd.s, cd.psi, cd.rn!!, cd.rb!!)
            }
            running = next
            val bestEnd = ended.maxOfOrNull { it.score } ?: Double.NEGATIVE_INFINITY
            if (running.isEmpty() || running.maxOf { it.score } < bestEnd) return ended.sortedByDescending { it.score }
        }
        if (ended.isEmpty()) running.forEach { ended += Hyp(it.toks.copyOfRange(1, it.toks.size), it.score) }
        return ended.sortedByDescending { it.score }
    }

    /** log(exp(a) + exp(b)), as numpy.logaddexp. */
    private fun lae(a: Double, b: Double): Double = max(a, b) + ln1p(exp(-abs(a - b)))
}
