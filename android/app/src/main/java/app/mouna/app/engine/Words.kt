package app.mouna.app.engine

import app.mouna.core.Decision
import app.mouna.core.DecisionKind
import app.mouna.core.Personal

/** One line of "Did you mean…?": an open reading, one of the person's sentences, or a word they taught ([word] = its id). */
data class Suggestion(val text: String, val personal: Boolean = false, val word: String? = null)

/**
 * Words the person taught Lips (any language, any name): the legacy phrase learner (lip encoder + decision core) over
 * the same clip Lips reads, trimmed to the moving part. Lips reads English sentences; taught words cover what it can't
 * spell or read: names, Kannada or Tamil words, a private signal. Every suggestion is still confirmed before it is spoken.
 */
object Words {
    /** A word fits the lip encoder's window: a clip whose moving part is longer than this (~3.2 s) is a sentence. */
    const val MAX_FRAMES = 96
    const val MIN_FRAMES = 10
    /** Examples a new word asks for (E15: 3 examples per phrase beat 2 on top-1; more come from confirmations). */
    const val SHOTS = 3

    /**
     * What "Did you mean…?" shows, best first. A clear taught word (SPEAK, or CONFIRM with no closer "none of these"
     * example) leads; otherwise taught words that are possible (CONFIRM, RESCUE, CHOOSE) come right after the first
     * reading. ASK and NOT_TAUGHT add nothing: the clip is not one of the person's words. Same text keeps its first place.
     */
    fun merge(open: List<Personal.Option>, words: Decision?, max: Int = 4, text: (String) -> String?): List<Suggestion> {
        val reading = open.map { Suggestion(it.text, it.personal) }
        val possible = words?.takeIf { offers(it) }?.options.orEmpty()
            .mapNotNull { id -> text(id)?.let { Suggestion(it, personal = true, word = id) } }
        val lead = words != null && possible.isNotEmpty() &&
            (words.kind == DecisionKind.SPEAK || (words.kind == DecisionKind.CONFIRM && !words.maybeNone))
        val ordered = when {
            lead -> possible + reading
            reading.isEmpty() -> possible
            else -> reading.take(1) + possible + reading.drop(1)
        }
        return ordered.distinctBy { it.text.lowercase() }.take(max)
    }

    /**
     * The part of a Lips clip the lip encoder sees: the moving span (FreeTalk.activeSpan, the cut Lips reads), or null
     * when it is too short, too long for a word, or some frame has no lip-encoder crop (the crop was just switched on).
     */
    fun clip(c: Clip): Clip? {
        val span = app.mouna.core.FreeTalk.activeSpan(c.avsrCrops().first)
        if (span.isEmpty()) return null
        val frames = c.frames.subList(span.first, span.last + 1)
        if (frames.size < MIN_FRAMES || frames.size > MAX_FRAMES || frames.any { it.crop == null }) return null
        return Clip(frames)
    }

    /** Whether a words decision puts any word on screen (ASK and NOT_TAUGHT do not). */
    fun offers(d: Decision) = d.kind in OFFERED

    private val OFFERED = setOf(DecisionKind.SPEAK, DecisionKind.CONFIRM, DecisionKind.RESCUE, DecisionKind.CHOOSE)
}
