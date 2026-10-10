package app.mouna.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln1p
import kotlin.math.max

/**
 * Free talk, part A of issue #5: the person's own sentences, scored by the model itself next to the open reading.
 * Port of `encoder/mouna_encoder/avsr.py` (ctc_sequence_logp, score_sentences); pinned by harness/vectors/freetalk.json.
 *
 * Measured on GRID (deck/data/freetalk-personal-grid.json): with the true sentence in a list of 20, the model's score
 * puts it first 29/30 times, where the open reading got 0/30 sentences exactly right.
 */
object Personal {
    private const val NEG = -1e30

    /**
     * A listed sentence goes before the open reading when its score is within this many nats of the open best
     * (GRID: 23/30 in-list sentences, 0/30 false when the truth was not in the list). Below it, it is still offered.
     */
    const val FIRST_WITHIN = -20.0
    /** Further than this behind the open reading, a listed sentence is not offered at all. */
    const val OFFER_WITHIN = -35.0
    /**
     * Listed sentences rank by score minus this share of their no-video prior, so a short common sentence ("Thank you
     * very much") can't win every clip. GRID + the care phrases: without it a care phrase came first 24/30 and the true
     * sentence 6/30; with 0.7-1.0 never and 30/30 (deck/data/freetalk-personal-grid.json).
     */
    const val PRIOR_WEIGHT = 0.8

    /** Rank of a listed sentence among the others: its score minus part of what the decoder expects with no video. */
    fun rank(score: Double, prior: Double, ctcWeight: Double = JointBeam.CTC_WEIGHT): Double =
        score - PRIOR_WEIGHT * (1 - ctcWeight) * prior

    /** log P(ids | frames) under CTC: the forward algorithm over the blank-extended labels. */
    fun ctcSequence(ctc: FloatArray, frames: Int, units: Int, ids: IntArray): Double {
        val ext = IntArray(ids.size * 2 + 1) { if (it % 2 == 0) Ctc.BLANK else ids[it / 2] }
        val s = ext.size
        var alpha = DoubleArray(s) { NEG }
        alpha[0] = ctc[Ctc.BLANK].toDouble()
        if (s > 1) alpha[1] = ctc[ext[1]].toDouble()
        for (t in 1 until frames) {
            val next = DoubleArray(s)
            for (j in 0 until s) {
                var a = alpha[j]
                if (j >= 1) a = lae(a, alpha[j - 1])
                if (j >= 2 && ext[j] != Ctc.BLANK && ext[j] != ext[j - 2]) a = lae(a, alpha[j - 2])
                next[j] = a + ctc[t * units + ext[j]]
            }
            alpha = next
        }
        return if (s > 1) lae(alpha[s - 1], alpha[s - 2]) else alpha[s - 1]
    }

    /**
     * Whole-sentence joint score, the search's objective: (1 - w) * sum of log p_att over the tokens and eos + w * CTC.
     * [att] gives, for a sentence, log p_att of each target (its tokens, then eos) given the prefix before it.
     */
    fun score(ctc: FloatArray, frames: Int, units: Int, ids: IntArray, att: DoubleArray, ctcWeight: Double = JointBeam.CTC_WEIGHT): Double =
        (1 - ctcWeight) * att.sum() + ctcWeight * ctcSequence(ctc, frames, units, ids)

    /** [score]: the joint score (comparable with the open reading); [rank]: order among listed sentences ([Personal.rank]). */
    data class Option(val text: String, val score: Double, val personal: Boolean, val rank: Double = score)

    /**
     * What "Did you mean…?" shows, best first: listed sentences in [Option.rank] order; the best leads when its score is
     * within [FIRST_WITHIN] of the open reading, otherwise the open reading leads; then they alternate. Listed sentences further than
     * [OFFER_WITHIN] behind are dropped. Duplicates (same text) keep their first place.
     */
    fun merge(open: List<Option>, listed: List<Option>, max: Int = 4): List<Option> {
        val openBest = open.firstOrNull()?.score ?: Double.NEGATIVE_INFINITY
        val mine = listed.sortedByDescending { it.rank }.filter { open.isEmpty() || it.score - openBest > OFFER_WITHIN }
        val mineFirst = mine.isNotEmpty() && (open.isEmpty() || mine[0].score - openBest > FIRST_WITHIN)
        val a = if (mineFirst) mine else open
        val b = if (mineFirst) open else mine
        val out = LinkedHashMap<String, Option>()
        for (i in 0 until maxOf(a.size, b.size)) {
            a.getOrNull(i)?.let { out.putIfAbsent(it.text.lowercase(), it) }
            b.getOrNull(i)?.let { out.putIfAbsent(it.text.lowercase(), it) }
        }
        return out.values.take(max)
    }

    private fun lae(a: Double, b: Double): Double = max(a, b) + ln1p(exp(-abs(a - b)))
}
