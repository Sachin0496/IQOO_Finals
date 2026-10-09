package app.mouna.core

/**
 * Text -> Auto-AVSR units for free talk's personal sentences: SentencePiece unigram segmentation (best total piece
 * score), port of `encoder/mouna_encoder/avsr.py` text_units; matches sentencepiece itself on 310 test sentences.
 * [pieces]: piece -> log-prob score (spm_pieces.tsv); [tokens]: the model's units (tokens.txt, 0 = blank).
 */
class Spm(private val pieces: Map<String, Float>, tokens: List<String>) {
    private val index = tokens.withIndex().associate { it.value to it.index }
    private val unk = index.getValue("<unk>")
    private val unkScore = (pieces.values.minOrNull() ?: 0f).toDouble() - 10.0
    private val maxLen = pieces.keys.maxOfOrNull { it.length } ?: 1

    fun encode(text: String): IntArray {
        val words = text.uppercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return IntArray(0)
        val s = words.joinToString("") { "▁$it" }
        val best = DoubleArray(s.length + 1) { Double.NEGATIVE_INFINITY }.also { it[0] = 0.0 }
        val from = IntArray(s.length + 1)
        val piece = arrayOfNulls<String>(s.length + 1)
        for (end in 1..s.length) {
            for (start in maxOf(0, end - maxLen) until end) {
                if (best[start] == Double.NEGATIVE_INFINITY) continue
                val p = s.substring(start, end)
                val sc = pieces[p]?.toDouble() ?: if (end - start == 1) unkScore else continue
                if (best[start] + sc > best[end]) {
                    best[end] = best[start] + sc
                    from[end] = start
                    piece[end] = if (pieces.containsKey(p)) p else null
                }
            }
        }
        val out = ArrayList<Int>()
        var end = s.length
        while (end > 0) {
            out += piece[end]?.let { index[it] } ?: unk
            end = from[end]
        }
        return out.reversed().toIntArray()
    }

    companion object {
        /** spm_pieces.tsv: one "piece<TAB>score" per line. */
        fun parse(lines: List<String>): Map<String, Float> =
            lines.mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1].toFloat() } }.toMap()
    }
}
