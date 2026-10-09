package app.mouna.core

import org.json.JSONArray
import org.json.JSONObject

/*
 * Ask mode and Build a sentence: ports of lab/src/core/ask.ts and blocks.ts. The data is the Lab's own JSON
 * (ask-tree.json, blocks.json, phrase-pack.json), packed into this module's resources under /mouna/ by Gradle, so
 * there is one source of truth. Texts are keyed by language: en, ta, kn, hi.
 */

typealias Texts = Map<String, String>

private fun texts(o: JSONObject?): Texts = o?.keys()?.asSequence()?.associateWith { o.getString(it) } ?: emptyMap()

private fun resource(name: String): String =
    Learner::class.java.getResourceAsStream("/mouna/$name")?.readBytes()?.toString(Charsets.UTF_8) ?: error("missing resource /mouna/$name")

// ---------------- Ask mode ----------------

data class AskNode(
    val id: String,
    val ask: Texts,
    val urgent: Boolean,
    val phrase: String? = null, // a built-in phrase id (its natural voice), or…
    val say: Texts? = null, // …the words to say
    val children: List<AskNode> = emptyList(),
)

data class AskTree(val tree: List<AskNode>, val start: Texts, val none: Texts) {
    companion object {
        fun parse(json: String): AskTree {
            val o = JSONObject(json)
            fun node(n: JSONObject): AskNode = AskNode(
                id = n.getString("id"),
                ask = texts(n.optJSONObject("ask")),
                urgent = n.optBoolean("urgent", false),
                phrase = if (n.has("phrase")) n.getString("phrase") else null,
                say = n.optJSONObject("say")?.let { texts(it) },
                children = n.optJSONArray("children")?.let { a -> List(a.length()) { node(a.getJSONObject(it)) } } ?: emptyList(),
            )
            val t = o.getJSONArray("tree")
            return AskTree(List(t.length()) { node(t.getJSONObject(it)) }, texts(o.getJSONObject("start")), texts(o.getJSONObject("none")))
        }

        /** The 30-answer ward tree from the Lab (ICU communication studies; native review pending). */
        fun bundled(): AskTree = parse(resource("ask-tree.json"))
    }
}

sealed interface AskResult {
    data class Answer(val node: AskNode) : AskResult
    data object None : AskResult
}

/** Partner-assisted scanning by the device: yes goes into a group or picks an answer, no moves to the next. */
class AskSession(tree: List<AskNode>) {
    private val stack = ArrayDeque<Pair<List<AskNode>, Int>>()
    private var list = tree
    var position = 0
        private set
    var result: AskResult? = null
        private set
    /** questions asked so far: the cost of reaching the answer */
    var asked = 1
        private set

    val current: AskNode? get() = if (result != null) null else list[position]
    val options: List<AskNode> get() = list
    val path: List<AskNode> get() = stack.map { (l, i) -> l[i] }

    fun yes() {
        val n = current ?: return
        if (n.children.isNotEmpty()) {
            stack.addLast(list to position)
            list = n.children
            position = 0
            asked++
        } else {
            result = AskResult.Answer(n)
        }
    }

    /** Next question; past the end of a group, back to the next group up; past the end of everything, none. */
    fun no() {
        if (result != null) return
        position++
        while (position >= list.size) {
            val up = stack.removeLastOrNull()
            if (up == null) {
                result = AskResult.None
                return
            }
            list = up.first
            position = up.second + 1
        }
        asked++
    }

    /** Undo the last yes into a group: back to that group's question. */
    fun back() {
        val up = stack.removeLastOrNull() ?: return
        list = up.first
        position = up.second
        result = null
        asked++
    }
}

// ---------------- Build a sentence ----------------

fun blockId(b: String) = "b_$b"

data class Sentence(val id: String, val blocks: List<String>, val text: Texts)

data class BlockPack(val blocks: Map<String, Texts>, val sentences: List<Sentence>) {
    companion object {
        fun parse(json: String): BlockPack {
            val o = JSONObject(json)
            val b = o.getJSONArray("blocks")
            val blocks = LinkedHashMap<String, Texts>()
            for (i in 0 until b.length()) b.getJSONObject(i).let { blocks[blockId(it.getString("id"))] = texts(it.getJSONObject("word")) }
            val s = o.getJSONArray("sentences")
            val sentences = List(s.length()) { i ->
                val x = s.getJSONObject(i)
                val ids = x.getJSONArray("blocks").let { a: JSONArray -> List(a.length()) { a.getString(it) } }
                Sentence("s_${ids.joinToString("_")}", ids.map(::blockId), texts(x.getJSONObject("text")))
            }
            return BlockPack(blocks, sentences)
        }

        /** The Lab's 16 blocks and 23 human-written sentences (native review pending). */
        fun bundled(): BlockPack = parse(resource("blocks.json"))
    }
}

data class Reading(
    val ranked: List<Pair<Sentence, Double>>, // listed sentences of this length, best first, summed distance
    val sure: Boolean, // every block of the best sentence is within the speak limit on its own
    val words: List<String>, // each block's own best guess, for the plain-words fallback
)

/**
 * Reads a row of recognised blocks (one [Ranked] per block, from Learner.predict) against the sentence list by the
 * smallest summed raw distance, as measured in harness/mouna_harness/openset.py. Always show and confirm the result.
 */
fun readBlocks(row: List<Ranked>, pack: BlockPack): Reading {
    val dist = row.map { r -> r.intents.zip(r.distances).toMap() }
    val ranked = pack.sentences.filter { it.blocks.size == row.size }
        .map { s -> s to s.blocks.withIndex().sumOf { (i, b) -> dist[i][b] ?: Double.POSITIVE_INFINITY } }
        .filter { it.second.isFinite() }
        .sortedBy { it.second }
    val best = ranked.firstOrNull()?.first
    val score = row.map { r -> r.intents.zip(r.scores).toMap() }
    val limit = CoreConstants.Q_SPEAK.getValue(Tier.A)
    val sure = best != null && best.blocks.withIndex().all { (i, b) -> (score[i][b] ?: Double.POSITIVE_INFINITY) <= limit }
    val words = row.map { r -> r.intents.firstOrNull { it in pack.blocks } ?: r.intents.firstOrNull() ?: "" }
    return Reading(ranked, sure, words)
}

/** The plain-words fallback, "fan, off", in every language: never an invented sentence. */
fun plainWords(words: List<String>, pack: BlockPack): Texts =
    listOf("en", "ta", "kn", "hi").associateWith { l -> words.joinToString(", ") { w -> pack.blocks[w]?.get(l) ?: w } }
