package app.mouna.app.engine

/**
 * What Mouna says on a phone call: an introduction, quick phrases for the common turns of a conversation, and the
 * helpers for numbers and typed words. Pure (no Android types) so it is unit-tested on the JVM.
 *
 * Hindi and Tamil are written neutrally (Hindi avoids gendered verbs) but have not been reviewed by a native speaker.
 */
data class QuickPhrase(
    val id: String,
    /** The pre-rendered pack phrase with the same meaning, if any: plays instantly and offline. */
    val packId: String?,
    private val text: Map<Lang, String>,
) {
    fun say(lang: Lang) = text[lang] ?: text.getValue(Lang.EN)
}

object CallPhrases {
    val quick = listOf(
        QuickPhrase("yes", "yes", mapOf(Lang.EN to "Yes", Lang.HI to "हाँ", Lang.TA to "ஆம்")),
        QuickPhrase("no", "no", mapOf(Lang.EN to "No", Lang.HI to "नहीं", Lang.TA to "இல்லை")),
        QuickPhrase("repeat", null, mapOf(Lang.EN to "Please repeat", Lang.HI to "कृपया दोबारा बोलिए", Lang.TA to "தயவுசெய்து மீண்டும் சொல்லுங்கள்")),
        QuickPhrase("wait", null, mapOf(Lang.EN to "Wait a moment", Lang.HI to "एक मिनट रुकिए", Lang.TA to "ஒரு நிமிடம் பொறுங்கள்")),
        QuickPhrase("slow", null, mapOf(Lang.EN to "Speak slowly please", Lang.HI to "कृपया धीरे बोलिए", Lang.TA to "தயவுசெய்து மெதுவாகப் பேசுங்கள்")),
        QuickPhrase("message", null, mapOf(Lang.EN to "I'll message you", Lang.HI to "बाद में मैसेज पर बात करते हैं", Lang.TA to "நான் உங்களுக்கு மெசேஜ் செய்கிறேன்")),
        QuickPhrase("thanks", "thank_you", mapOf(Lang.EN to "Thank you", Lang.HI to "धन्यवाद", Lang.TA to "நன்றி")),
        QuickPhrase("bye", null, mapOf(Lang.EN to "Bye", Lang.HI to "अलविदा", Lang.TA to "வருகிறேன்")),
    )

    /** The opening line: who is calling, why they are silent, and the one way to talk to them. */
    fun intro(name: String, lang: Lang): String {
        val n = name.trim()
        return when (lang) {
            Lang.EN ->
                (if (n.isEmpty()) "Hello, I'm speaking through the Mouna app." else "Hello, this is $n speaking through the Mouna app.") +
                    " I can't talk, but I can hear you. Please ask me yes or no questions."
            Lang.HI ->
                (if (n.isEmpty()) "नमस्ते, मौना ऐप के ज़रिए बात हो रही है।" else "नमस्ते, यह $n है, मौना ऐप के ज़रिए बात हो रही है।") +
                    " मुझसे बोला नहीं जाता, पर मुझे आपकी बात सुनाई देती है। कृपया मुझसे हाँ या नहीं वाले सवाल पूछिए।"
            Lang.TA ->
                (if (n.isEmpty()) "வணக்கம், மௌனா செயலி மூலம் பேசுகிறேன்." else "வணக்கம், நான் $n. மௌனா செயலி மூலம் பேசுகிறேன்.") +
                    " என்னால் பேச முடியாது, ஆனால் நீங்கள் சொல்வது எனக்குக் கேட்கும். தயவுசெய்து ஆம் அல்லது இல்லை என்று பதில் சொல்லக்கூடிய கேள்விகளைக் கேளுங்கள்."
        }
    }

    /** Everything worth fetching before it is needed, so the first tap in a call is as fast as the tenth. */
    fun warm(lang: Lang, name: String): List<String> =
        quick.filter { it.packId == null }.map { it.say(lang) } + intro(name, lang)

    /** Typed words in Devanagari or Tamil script are spoken in that language whatever the caregiver language is. */
    fun langOf(text: String, fallback: Lang): Lang = when {
        text.any { it in 'ऀ'..'ॿ' } -> Lang.HI
        text.any { it in '஀'..'௿' } -> Lang.TA
        else -> fallback
    }
}

/** Phone numbers as typed, picked from contacts or pasted. */
object Phones {
    /** Digits plus a leading "+", or null if it can't be a number. `*` and `#` are kept for service codes. */
    fun normalise(raw: String): String? {
        val s = raw.trim()
        val body = s.filter { it.isDigit() || it == '*' || it == '#' || it == '+' }
        val plus = body.startsWith("+")
        val rest = body.filter { it != '+' }
        if (rest.count { it.isDigit() } < 3) return null
        return (if (plus) "+" else "") + rest
    }

    /** Groups for reading aloud on screen: "+91 98765 43210". Display only; dial [normalise]. */
    fun pretty(n: String): String {
        val plus = n.startsWith("+")
        val d = n.removePrefix("+")
        if (d.any { !it.isDigit() }) return n
        return when {
            plus && d.length == 12 && d.startsWith("91") -> "+91 ${d.substring(2, 7)} ${d.substring(7)}"
            !plus && d.length == 10 -> "${d.substring(0, 5)} ${d.substring(5)}"
            else -> n
        }
    }

    fun clock(seconds: Int) = "%02d:%02d".format(seconds / 60, seconds % 60)
}
