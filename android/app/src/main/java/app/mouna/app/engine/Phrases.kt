package app.mouna.app.engine

import app.mouna.core.Learner
import app.mouna.core.Tier
import org.json.JSONObject

/**
 * The caregiver's language for Mouna's voice. English first; Hindi and Tamil as options. The person's own mouthing
 * can be in any language: Mouna matches their movements to their own examples, it never transcribes.
 */
enum class Lang(val tag: String, val code: String, val label: String) {
    EN("en", "en-IN", "English"),
    HI("hi", "hi-IN", "हिन्दी"),
    TA("ta", "ta-IN", "தமிழ்"),
}

data class Phrase(val id: String, val urgent: Boolean, val text: Map<String, String>) {
    fun say(lang: Lang) = text[lang.tag] ?: text["en"] ?: id
}

/** lab/src/core/phrase-pack.json, packed into the core module's resources (one source for Lab, app and voices). */
data class PhrasePack(val phrases: List<Phrase>, val demo: List<String>, val protocol: List<String>) {
    private val byId = phrases.associateBy { it.id }
    operator fun get(id: String) = byId[id]

    /**
     * Risk tiers (PLAN.md §3): A speaks on a clear match; B (urgent care requests) needs a closer match; C (actions on
     * the room or outside systems) is always confirmed. No action phrases ship yet, so C is unused until IR lands.
     */
    fun tiers(): Map<String, Tier> = phrases.associate { it.id to if (it.urgent) Tier.B else Tier.A } + ACTIONS.associateWith { Tier.C }

    /** The same pack with the family's own words and Mouna's warm defaults added (English, spoken by the phone). */
    fun withExtras(custom: List<Pair<String, String>>): PhrasePack =
        copy(phrases = phrases + (WARM + custom).map { (id, t) -> Phrase(id, false, mapOf("en" to t)) }.filter { p -> phrases.none { it.id == p.id } })

    companion object {
        val ACTIONS = setOf("fan_off", "fan_on", "tv_off")

        /** Not needs but feelings: what people most want to say when they get their voice back. */
        val WARM = listOf(
            "love" to "I love you",
            "okay" to "I'm okay. Don't worry.",
            "hold_hand" to "Please hold my hand",
            "scared" to "I'm scared",
            "stay" to "Please stay with me",
        )

        fun isCustom(id: String) = id.startsWith("my_")

        fun bundled(): PhrasePack {
            val json = Learner::class.java.getResourceAsStream("/mouna/phrase-pack.json")!!.readBytes().toString(Charsets.UTF_8)
            val o = JSONObject(json)
            val arr = o.getJSONArray("phrases")
            val phrases = List(arr.length()) { i ->
                val p = arr.getJSONObject(i)
                val t = p.getJSONObject("text")
                Phrase(p.getString("id"), p.optBoolean("urgent", false), t.keys().asSequence().associateWith { t.getString(it) })
            }
            fun ids(k: String) = o.optJSONArray(k)?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            return PhrasePack(phrases, ids("demo"), ids("protocol"))
        }
    }
}
