package app.mouna.app.engine

import app.mouna.core.CoreConstants
import app.mouna.core.Decision
import app.mouna.core.DecisionKind
import app.mouna.core.Ranked

/**
 * Which spoken mouthings may be kept as extra examples without the person's say-so. The idea is LipLearner's online
 * incremental learning (Su, Fang & Rekimoto, CHI 2023, arXiv:2302.05907): a confident, unambiguous prediction that the person
 * does not correct is treated as a new example of that phrase, so the personal model grows with use.
 *
 * Off by default (Store.selfTrain). The core decision is untouched; this only chooses which clips the app feeds back to
 * the Learner. A correction (picked or "none of these") always discards the clip, so a wrong match is never learned.
 *
 * The constants are targets, not yet measured on our data: Q is stricter than the speak limit (1.4 to 1.7) on purpose,
 * MARGIN and NEG are stricter than the core's MARGIN (1.3) and NEG_RATIO (1.2). Tune them against recorded sessions.
 */
object SelfTrain {
    /** Best score at most this: a typical taught example scores about 1.0, so only very close matches qualify. */
    const val Q = 0.6
    /** The runner-up must be at least this many times further than the best. */
    const val MARGIN = 2.0
    /** The nearest "none of these" example must be at least this many times further than the best. */
    const val NEG = 2.0

    /**
     * True only when Mouna spoke its top phrase ([DecisionKind.SPEAK] for [r].intents[0]) on a very clear match, and the
     * phrase has fewer than [maxShots] examples. A single taught phrase has no runner-up, so the margin is treated as open.
     */
    fun keep(r: Ranked, d: Decision, count: Int, maxShots: Int = CoreConstants.MAX_SHOTS): Boolean {
        if (d.kind != DecisionKind.SPEAK) return false
        if (r.intents.isEmpty() || d.options.firstOrNull() != r.intents[0]) return false
        val best = r.scores[0]
        if (best > Q) return false
        val second = r.scores.getOrElse(1) { Double.POSITIVE_INFINITY }
        if (second < MARGIN * best) return false
        if (r.negative < NEG * best) return false
        return count < maxShots
    }
}
