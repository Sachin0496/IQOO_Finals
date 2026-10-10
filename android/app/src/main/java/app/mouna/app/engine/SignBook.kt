package app.mouna.app.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

/**
 * The person's own signs. The INCLUDE model knows its training signers, not ours (0 / 13 on new signers), so the
 * person teaches each sign a few times; a new sign is matched to the nearest taught example by the cosine of the
 * model's 256-d features (models/isl/export_isl.py "features").
 *
 * Measured on INCLUDE's held-out clips, different recordings per word (models/isl/eval/teach.json), 3 examples per
 * word: portrait (the phone's framing) top-1 76.8%, top-3 92.7%; landscape 91.8% / 97.7%. One person matching their
 * own signs is the easier case.
 *
 * Plain Kotlin, no Android: unit-tested on the JVM. Examples stay on the phone (the app's own files), never uploaded.
 */
class SignBook(private val file: File?) {
    data class Sign(val id: String, val text: String, val examples: List<FloatArray>)
    data class Match(val id: String, val text: String, val cos: Float)

    /** Speak a match on its own, or offer the likeliest taught signs to tap. */
    sealed interface Decision {
        data class Speak(val match: Match) : Decision
        data class Offer(val matches: List<Match>) : Decision
    }

    var signs: List<Sign> = load()
        private set

    fun add(text: String): String {
        val base = "s_" + System.currentTimeMillis().toString(36)
        val id = generateSequence(0) { it + 1 }.map { if (it == 0) base else "${base}_$it" }.first { c -> signs.none { it.id == c } }
        signs = signs + Sign(id, text.trim(), emptyList())
        save()
        return id
    }

    fun remove(id: String) {
        signs = signs.filter { it.id != id }
        save()
    }

    /** One more example of [id] (L2-normalised features); the oldest goes once there are [MAX_SHOTS]. Returns the count. */
    fun teach(id: String, features: FloatArray): Int {
        var n = 0
        signs = signs.map { s ->
            if (s.id != id) s else s.copy(examples = (s.examples + unit(features)).takeLast(MAX_SHOTS)).also { n = it.examples.size }
        }
        save()
        return n
    }

    /** Start over: every sign and example gone. */
    fun clear() {
        signs = emptyList()
        save()
    }

    fun count(id: String) = signs.firstOrNull { it.id == id }?.examples?.size ?: 0

    /** Taught signs (with at least one example) by their best cosine to [features], best first. */
    fun match(features: FloatArray): List<Match> {
        val f = unit(features)
        return signs.filter { it.examples.isNotEmpty() }
            .map { s -> Match(s.id, s.text, s.examples.maxOf { dot(f, it) }) }
            .sortedByDescending { it.cos }
    }

    fun decide(features: FloatArray): Decision? {
        val m = match(features)
        if (m.isEmpty()) return null
        val margin = m[0].cos - (m.getOrNull(1)?.cos ?: -1f)
        return if (m[0].cos >= SPEAK_COS && margin >= SPEAK_MARGIN) Decision.Speak(m[0]) else Decision.Offer(m.take(OFFER))
    }

    private fun load(): List<Sign> = runCatching {
        val a = JSONArray(file?.takeIf { it.exists() }?.readText() ?: return emptyList())
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val ex = o.getJSONArray("examples")
            Sign(o.getString("id"), o.getString("text"), List(ex.length()) { j ->
                ex.getJSONArray(j).let { v -> FloatArray(v.length()) { v.getDouble(it).toFloat() } }
            })
        }
    }.getOrDefault(emptyList())

    private fun save() {
        val f = file ?: return
        val a = JSONArray()
        for (s in signs) a.put(JSONObject().put("id", s.id).put("text", s.text).put("examples", JSONArray().apply {
            s.examples.forEach { e -> put(JSONArray().apply { e.forEach { put(it.toDouble()) } }) }
        }))
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(f)
    }

    companion object {
        /** Examples a new sign asks for: 3 measured best of 1-3 (teach.json). */
        const val SHOTS = 3
        const val MAX_SHOTS = 5
        /**
         * Speak alone when the best taught sign has cosine >= this and leads the next by [SPEAK_MARGIN]; otherwise offer.
         * teach.json, 3 examples: portrait 17.5% of signs spoken (0 wrong), untaught signs spoken 4.3%; the rest offer the
         * top 3, the right one inside 75%. Landscape 34% spoken, 0 wrong, untaught 3.9%.
         */
        const val SPEAK_COS = 0.9f
        const val SPEAK_MARGIN = 0.1f
        const val OFFER = 3

        fun unit(v: FloatArray): FloatArray {
            var n = 0f
            for (x in v) n += x * x
            n = sqrt(n)
            return if (n > 0f) FloatArray(v.size) { v[it] / n } else v.copyOf()
        }

        private fun dot(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }
    }
}
