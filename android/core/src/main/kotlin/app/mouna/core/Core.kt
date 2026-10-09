package app.mouna.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Mouna's decision core: a line-for-line port of harness/mouna_harness/core.py (read its docstring for the why).
 * Pure Kotlin, no Android: the app feeds it encoder embeddings and renders the [Decision].
 * Parity with Python is pinned by harness/vectors/core.json (CoreParityTest). Change Python first, regenerate the
 * vectors (python -m mouna_harness vectors), then port.
 */
object CoreConstants {
    const val PRIOR_TAU = 0.14
    const val TAU_PSEUDO = 3
    const val Q_SET = 1.95
    val Q_SPEAK = mapOf(Tier.A to 1.7, Tier.B to 1.4)
    const val MARGIN = 1.3
    const val NEG_RATIO = 1.2
    const val CAREFUL = 0.8
    const val MAX_CHOICES = 4
    const val SAFE_MARGIN = 1.25
    const val RED_PAIR = 1.0
    const val YELLOW_PAIR = 1.8
    const val MAX_SHOTS = 5
    const val RED_SHOTS = 3
    const val CONTEXT_CAP = 0.15
}

/** A: low consequence (auto-speak), B: care request (stricter), C: action or external system (always confirmed). */
enum class Tier { A, B, C }

enum class Level { GREEN, YELLOW, RED }

data class Ranked(
    val intents: List<String>,      // best first
    val scores: List<Double>,       // distance in units of this person's spread; ~1 = a typical example
    val distances: List<Double>,    // raw cosine distance to each prototype
    val negative: Double = Double.POSITIVE_INFINITY, // score of the nearest "none of my phrases" example
)

data class PairScore(val a: String, val b: String, val gap: Double, val level: Level)

/** reason: first | check | missed | close_to:<other intent> */
data class TeachRequest(val intent: String, val reason: String)

enum class DecisionKind { SPEAK, CONFIRM, RESCUE, CHOOSE, ASK, NOT_TAUGHT }

/**
 * SPEAK: say options[0]. CONFIRM: one picture + yes/no. RESCUE: two pictures (look left/right or the switch).
 * CHOOSE: 3-4 pictures. ASK: yes/no tree, options as first guesses. NOT_TAUGHT: say so, offer options as "maybe".
 * maybeNone: one of the person's "none of these" examples is closer, so put "None of these" first.
 */
data class Decision(
    val kind: DecisionKind,
    val options: List<String> = emptyList(),
    val why: String = "",
    val maybeNone: Boolean = false,
)

private fun unit(x: FloatArray): DoubleArray {
    val v = DoubleArray(x.size) { x[it].toDouble() }
    return normalise(v)
}

private fun normalise(v: DoubleArray): DoubleArray {
    var s = 0.0
    for (a in v) s += a * a
    val n = sqrt(s) + 1e-12
    return DoubleArray(v.size) { v[it] / n }
}

private fun dot(a: DoubleArray, b: DoubleArray): Double {
    var s = 0.0
    for (i in a.indices) s += a[i] * b[i]
    return s
}

/** numpy.median: mean of the two middle values for an even count. */
internal fun median(xs: List<Double>): Double {
    val s = xs.sorted()
    val m = s.size / 2
    return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
}

/** Few-shot learner for one person; works for any fixed-size embedding. Not thread-safe: use it from one thread. */
class Learner(private val priorTau: Double = CoreConstants.PRIOR_TAU) {
    private val samples = LinkedHashMap<String, MutableList<DoubleArray>>()
    private val negatives = mutableListOf<DoubleArray>()
    private val checks = HashMap<String, MutableList<Boolean>>()

    val intents: List<String> get() = samples.keys.toList()
    fun count(intent: String): Int = samples[intent]?.size ?: 0
    val negativeCount: Int get() = negatives.size

    fun addSample(intent: String, x: FloatArray) {
        samples.getOrPut(intent) { mutableListOf() }.add(unit(x))
    }

    fun addNegative(x: FloatArray) {
        negatives.add(unit(x))
    }

    fun forget(intent: String) {
        samples.remove(intent)
        checks.remove(intent)
    }

    /** Recognise the example first (if the intent already has one), then learn it. Returns the check, or null. */
    fun teach(intent: String, x: FloatArray): Boolean? {
        var ok: Boolean? = null
        if (!samples[intent].isNullOrEmpty()) {
            val r = predict(x)
            val second = r.scores.getOrElse(1) { Double.POSITIVE_INFINITY }
            ok = r.intents[0] == intent && r.scores[0] <= CoreConstants.Q_SET && second >= CoreConstants.SAFE_MARGIN * r.scores[0]
            checks.getOrPut(intent) { mutableListOf() }.add(ok)
        }
        addSample(intent, x)
        return ok
    }

    /** This person's typical within-intent distance, shrunk towards the prior while there are few pairs. */
    fun tau(): Double {
        val d = mutableListOf<Double>()
        for (xs in samples.values) for (i in xs.indices) for (j in i + 1 until xs.size) d.add(1 - dot(xs[i], xs[j]))
        if (d.isEmpty()) return priorTau
        return (median(d) * d.size + priorTau * CoreConstants.TAU_PSEUDO) / (d.size + CoreConstants.TAU_PSEUDO)
    }

    private fun protos(): LinkedHashMap<String, Pair<DoubleArray, Int>> {
        val out = LinkedHashMap<String, Pair<DoubleArray, Int>>()
        for ((k, xs) in samples) {
            if (xs.isEmpty()) continue
            val m = DoubleArray(xs[0].size)
            for (x in xs) for (i in m.indices) m[i] += x[i]
            for (i in m.indices) m[i] /= xs.size
            out[k] = normalise(m) to xs.size
        }
        return out
    }

    private fun score(d: Double, n: Int, tau: Double) = d / (0.5 * tau * (1 + 1.0 / n))

    fun predict(x: FloatArray): Ranked {
        val v = unit(x)
        val tau = tau()
        val rows = protos().map { (k, p) -> Triple(k, 1 - dot(v, p.first), p.second) }
            .sortedBy { score(it.second, it.third, tau) }
        var neg = Double.POSITIVE_INFINITY
        for (u in negatives) neg = min(neg, 1 - dot(v, u))
        return Ranked(
            rows.map { it.first },
            rows.map { score(it.second, it.third, tau) },
            rows.map { it.second },
            if (neg.isFinite()) score(neg, 1, tau) else Double.POSITIVE_INFINITY,
        )
    }

    /** Every pair of taught intents, closest first. RED pairs look alike for this person. */
    fun separability(): List<PairScore> {
        val tau = tau()
        val p = protos()
        val keys = p.keys.sorted()
        val out = mutableListOf<PairScore>()
        for (i in keys.indices) for (j in i + 1 until keys.size) {
            val gap = (1 - dot(p.getValue(keys[i]).first, p.getValue(keys[j]).first)) / tau
            val level = if (gap < CoreConstants.RED_PAIR) Level.RED else if (gap < CoreConstants.YELLOW_PAIR) Level.YELLOW else Level.GREEN
            out.add(PairScore(keys[i], keys[j], gap, level))
        }
        return out.sortedBy { it.gap }
    }

    /** The one example Mouna needs next, or null when the pack is safe. minShots 2 = quick, 3 = thorough. */
    fun nextToTeach(intents: List<String> = this.intents, minShots: Int = 2, maxShots: Int = CoreConstants.MAX_SHOTS): TeachRequest? {
        val n = intents.associateWith { count(it) }
        intents.firstOrNull { n.getValue(it) == 0 }?.let { return TeachRequest(it, "first") }
        for (want in 2..minShots) intents.firstOrNull { n.getValue(it) < want }?.let { return TeachRequest(it, "check") }
        val missed = intents.filter { k -> checks[k]?.lastOrNull() == false && n.getValue(k) < maxShots }
        if (missed.isNotEmpty()) return TeachRequest(missed.minBy { n.getValue(it) }, "missed")
        for (p in separability()) {
            if (p.level != Level.RED) break
            if (p.a !in n || p.b !in n) continue
            val k = if (n.getValue(p.b) < n.getValue(p.a)) p.b else p.a
            if (n.getValue(k) < min(CoreConstants.RED_SHOTS, maxShots)) return TeachRequest(k, "close_to:${if (k == p.a) p.b else p.a}")
        }
        return null
    }
}

/**
 * One decision from a ranking. Speak needs a close best match (Q_SPEAK by tier), a clear runner-up gap (MARGIN) and
 * no closer "none of these" example (NEG_RATIO). Tier C is never spoken without a confirm. Everything else shows the
 * prediction set, which [prior] (context: recency, time of day) may reorder but never extend.
 */
fun decide(
    r: Ranked,
    tiers: Map<String, Tier> = emptyMap(),
    careful: Boolean = false,
    prior: Map<String, Double>? = null,
    qSet: Double = CoreConstants.Q_SET,
    qSpeak: Map<Tier, Double> = CoreConstants.Q_SPEAK,
): Decision {
    if (r.intents.isEmpty()) return Decision(DecisionKind.NOT_TAUGHT, why = "nothing taught")
    val best = r.scores[0]
    val second = r.scores.getOrElse(1) { Double.POSITIVE_INFINITY }
    val tier = tiers[r.intents[0]] ?: Tier.A
    val limit = (qSpeak[tier] ?: Double.NEGATIVE_INFINITY) * (if (careful) CoreConstants.CAREFUL else 1.0)
    if (best <= limit && second >= CoreConstants.MARGIN * best && r.negative >= CoreConstants.NEG_RATIO * best) {
        return Decision(DecisionKind.SPEAK, listOf(r.intents[0]), "clear")
    }
    var s = r.intents.zip(r.scores).filter { it.second <= qSet }.map { it.first }
    val maybeNone = r.negative < best
    if (s.isEmpty()) return Decision(DecisionKind.NOT_TAUGHT, r.intents.take(2), "not close to anything taught", maybeNone)
    if (prior != null && s.size > 1) {
        val score = r.intents.zip(r.scores).toMap()
        val mean = s.sumOf { prior[it] ?: 0.0 } / s.size
        val denom = if (mean == 0.0) 1.0 else mean
        s = s.sortedBy { k ->
            score.getValue(k) * (1 - CoreConstants.CONTEXT_CAP * max(-1.0, min(1.0, ((prior[k] ?: 0.0) - mean) / denom)))
        }
    }
    return when {
        s.size == 1 -> {
            val why = if (tier == Tier.C && s[0] == r.intents[0]) "action: always confirmed" else "fairly sure: confirm first"
            Decision(DecisionKind.CONFIRM, s, why, maybeNone)
        }
        s.size == 2 -> Decision(DecisionKind.RESCUE, s, "two look alike: pick one", maybeNone)
        s.size <= CoreConstants.MAX_CHOICES -> Decision(DecisionKind.CHOOSE, s, "a few are possible", maybeNone)
        else -> Decision(DecisionKind.ASK, s.take(CoreConstants.MAX_CHOICES), "many are possible: ask yes/no", maybeNone)
    }
}
