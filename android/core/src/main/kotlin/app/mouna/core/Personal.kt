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
     * A listed sentence goes before the open reading when its score is within this many nats of the open best; below
     * it, it is still offered. Was -20 (GRID). Laptop replay of Nakul's 21 held-out silent clips (adapt.py seed 0,
     * 10 Oct; not yet saved in deck/data): -20 put a wrong listed sentence first 19/21 when the truth was not listed
     * ("I am not hungry" -> "I am hungry"); -8 on the original model and -6 on a model tuned to the person
     * ([FIRST_WITHIN_TUNED]) put it first 0/21 and kept listed truths first or second.
     */
    const val FIRST_WITHIN = -8.0
    /** [FIRST_WITHIN] for a model tuned to the person: it already reads their sentences well, so they need less help. */
    const val FIRST_WITHIN_TUNED = -6.0
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
     * within [firstWithin] of the open reading, otherwise the open reading leads; then they alternate. Listed sentences further than
     * [OFFER_WITHIN] behind are dropped. Duplicates (same text) keep their first place.
     */
    fun merge(open: List<Option>, listed: List<Option>, max: Int = 4, firstWithin: Double = FIRST_WITHIN): List<Option> {
        val openBest = open.firstOrNull()?.score ?: Double.NEGATIVE_INFINITY
        val mine = listed.sortedByDescending { it.rank }.filter { open.isEmpty() || it.score - openBest > OFFER_WITHIN }
        val mineFirst = mine.isNotEmpty() && (open.isEmpty() || mine[0].score - openBest > firstWithin)
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
