package app.mouna.app.engine

import java.net.URLEncoder
import kotlin.math.max
import kotlin.math.min

/** Which phone action the person asked for. */
enum class PhoneVerb { CALL, MESSAGE }

/**
 * A person Mouna can reach: a phone number, a web-call room, or both. [starred] is the star on the phone's own contacts
 * list; it puts the person on the short list when no name was said.
 */
data class PhoneContact(
    val name: String,
    val number: String? = null,
    val room: String? = null,
    val aliases: List<String> = emptyList(),
    val starred: Boolean = false,
)

/** What the person asked for. name == null: "call" alone, so Mouna asks who. body == null for calls or a message with no text yet. */
data class PhoneCommand(val verb: PhoneVerb, val name: String?, val body: String?)

/** How a phone request is carried out. [PhoneRoute.EmergencyDial] only ever opens the dialer, never places a call. */
sealed interface PhoneRoute {
    /** A call in the web-call screen, for a contact with no phone number. */
    data class WebRoom(val room: String, val name: String) : PhoneRoute

    /** The phone's own phone app calls [number]. */
    data class Carrier(val number: String, val name: String) : PhoneRoute
    data class EmergencyDial(val number: String) : PhoneRoute
    data class WhatsApp(val url: String) : PhoneRoute
    data class Sms(val number: String, val text: String) : PhoneRoute

    /** A plain sentence for the caregiver saying why this person cannot be reached. */
    data class Unreachable(val why: String) : PhoneRoute
}

/**
 * Reads a phone request ("call Nakul", "nakul ko message karo") with fixed patterns, no ML. Text that is not clearly
 * about another person returns null, so ordinary talk never dials anyone.
 */
object PhoneParser {
    private val fillers = setOf(
        "please", "pls", "plz", "kindly", "can", "could", "would", "will", "you", "mouna", "hey", "hi", "hello", "ok", "okay",
    )
    private val trailing = setOf("now", "abhi", "jaldi", "please", "pls", "plz", "mouna", "today")

    /** Words that are never a person's name, so "message received" or "call me" are not commands. */
    private val notAName = setOf(
        "me", "you", "him", "her", "them", "us", "it", "back", "later", "again", "up", "out", "the", "a", "an", "to", "that",
        "is", "was", "are", "from", "received", "sent", "delivered", "read", "message", "msg", "text", "sms", "my", "your",
        "this", "there", "here", "and", "or", "not", "everyone", "everybody", "all", "someone", "anyone", "now", "abhi",
        "jaldi", "ko", "ki", "karo", "kar", "do", "bhejo", "hai", "mat", "nahi", "nahin", "na",
    )
    private val callWords = setOf("call", "phone", "dial", "ring")
    private val messageWords = setOf("message", "msg", "text", "sms")
    private val messageVerbs = messageWords + setOf("bolo", "batao")
    private val negations = setOf("mat", "nahi", "nahin")
    private val callTail = setOf(
        "karo", "kar", "do", "dijiye", "kardo", "dein", "lagao", "lagado", "lagaiye", "lagana", "karna", "karni", "hai",
        "chahiye", "please", "ji", "abhi", "now", "jaldi",
    )
    private val messageTail = setOf(
        "karo", "kar", "do", "dijiye", "kardo", "bhejo", "bhej", "bhejiye", "bhejna", "de", "dena", "dein", "karna", "karni",
        "karein", "lagao",
    )

    private val callRe = Regex("^(?:(?:make|start|place) (?:a |an )?)?(?:video |voice )?(?:call|phone|dial|ring)(?: (?:to|up))?(?: (.+))?$")
    private val callFirstRe = Regex("^(?:call|phone|dial|ring) (?:karo|kar do|kardo|kar dijiye|lagao|lagado|lagaiye)(?: (.+?))?(?: ko)?$")
    private val messageRe = Regex("^(?:send (?:a |an )?)?(?:message|msg|text|sms)(?: to)?(?: (.+))?$")
    private val tellSepRe = Regex("^tell (.+?) (?:to|that|ki)(?: (.+))?$")
    private val tellRe = Regex("^tell (.+)$")
    private val hinglishRe = Regex("^(.+?) ko (.+)$")

    /** The phone request in [text], or null when it is not one. */
    fun parse(text: String): PhoneCommand? {
        val s = dropFillers(clean(text))
        if (s.isEmpty()) return null
        return englishCall(s) ?: callFirst(s) ?: englishMessage(s) ?: tell(s) ?: hinglish(s)
    }

    /** Sign-language words for the two verbs (ISL), or null for any other sign. */
    fun fromSign(word: String): PhoneVerb? = when (word.trim().lowercase()) {
        "telephone", "cellphone" -> PhoneVerb.CALL
        "letter" -> PhoneVerb.MESSAGE
        else -> null
    }

    /**
     * Fixes a two-word name that [parse] split: "message nakul sharma hi" gives name "nakul" and body "sharma hi". When
     * the first two words match a contact at least as well as the first word alone, the second word joins the name.
     */
    fun resolve(cmd: PhoneCommand, contacts: List<PhoneContact>): PhoneCommand {
        val name = cmd.name ?: return cmd
        val body = cmd.body ?: return cmd
        if (cmd.verb != PhoneVerb.MESSAGE) return cmd
        val wider = "$name ${body.substringBefore(' ')}"
        val one = best(name, contacts)
        val two = best(wider, contacts)
        if (two < one || two < NameMatch.THRESHOLD) return cmd
        return PhoneCommand(cmd.verb, wider, body.substringAfter(' ', "").ifEmpty { null })
    }

    private fun best(spoken: String, contacts: List<PhoneContact>): Float =
        NameMatch.rank(spoken, contacts).firstOrNull()?.second ?: 0f

    private fun clean(text: String): String = text.lowercase().replace('’', '\'')
        .split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        .trimEnd('.', ',', '!', '?', ';', '…', ' ')

    private fun dropFillers(s: String): String = s.split(' ').dropWhile { it in fillers }.joinToString(" ")

    /** The name in a phrase, minus trailing fillers; null when it is empty or not a person. */
    private fun nameOf(raw: String): String? {
        val words = raw.split(' ').toMutableList()
        while (words.size > 1 && words.last() in trailing) words.removeAt(words.lastIndex)
        val name = words.joinToString(" ")
        return if (name.isEmpty() || words.first() in notAName) null else name
    }

    private fun callFrom(raw: String): PhoneCommand? =
        if (raw.isEmpty()) PhoneCommand(PhoneVerb.CALL, null, null)
        else nameOf(raw)?.let { PhoneCommand(PhoneVerb.CALL, it, null) }

    private fun englishCall(s: String): PhoneCommand? = callRe.matchEntire(s)?.let { callFrom(it.groupValues[1]) }

    private fun callFirst(s: String): PhoneCommand? = callFirstRe.matchEntire(s)?.let { callFrom(it.groupValues[1]) }

    private fun englishMessage(s: String): PhoneCommand? {
        val m = messageRe.matchEntire(s) ?: return null
        val raw = m.groupValues[1]
        return if (raw.isEmpty()) PhoneCommand(PhoneVerb.MESSAGE, null, null) else splitName(raw)
    }

    /** "nakul: come now" takes the name before the colon. Otherwise the first word is the name and the rest the body. */
    private fun splitName(raw: String): PhoneCommand? {
        if (':' in raw) {
            val name = nameOf(raw.substringBefore(':').trim()) ?: return null
            return PhoneCommand(PhoneVerb.MESSAGE, name, raw.substringAfter(':').trim().ifEmpty { null })
        }
        val name = nameOf(raw.substringBefore(' ')) ?: return null
        return PhoneCommand(PhoneVerb.MESSAGE, name, raw.substringAfter(' ', "").ifEmpty { null })
    }

    private fun tell(s: String): PhoneCommand? {
        val sep = tellSepRe.matchEntire(s)
        if (sep != null) return named(sep.groupValues[1], sep.groupValues[2])
        return tellRe.matchEntire(s)?.let { splitName(it.groupValues[1]) }
    }

    private fun named(raw: String, body: String): PhoneCommand? =
        nameOf(raw)?.let { PhoneCommand(PhoneVerb.MESSAGE, it, body.ifEmpty { null }) }

    /** Hinglish: "nakul ko call karo", "nakul ko bolo ki khana chahiye". */
    private fun hinglish(s: String): PhoneCommand? {
        val m = hinglishRe.matchEntire(s) ?: return null
        val raw = m.groupValues[1]
        val words = m.groupValues[2].split(' ')
        val verb = words.first()
        val tail = words.drop(1)
        return when (verb) {
            in callWords -> if (tail.all { it in callTail }) nameOf(raw)?.let { PhoneCommand(PhoneVerb.CALL, it, null) } else null
            in messageVerbs -> hinglishMessage(raw, verb, tail)
            else -> null
        }
    }

    private fun hinglishMessage(raw: String, verb: String, tail: List<String>): PhoneCommand? {
        if (tail.firstOrNull().orEmpty() in negations) return null
        val words = if (verb in messageWords) tail.dropWhile { it in messageTail } else tail
        val body = words.dropWhile { it == "ki" }.joinToString(" ").ifEmpty { null }
        return nameOf(raw)?.let { PhoneCommand(PhoneVerb.MESSAGE, it, body) }
    }
}

/**
 * Matches a spoken name to the saved contacts. Lip-reading and speech make errors, so spellings are folded (Indian
 * transliteration) and compared with Jaro-Winkler. Callers must surface the choices, never silently pick one.
 */
object NameMatch {
    /** Lowest score that still counts as a possible match. */
    const val THRESHOLD = 0.75f

    private const val PHONETIC = 0.9f
    private const val EPS = 1e-4f
    private val folds = listOf(
        "ph" to "f", "sh" to "s", "th" to "t", "dh" to "d", "bh" to "b", "kh" to "k",
        "aa" to "a", "ee" to "i", "oo" to "u", "w" to "v", "z" to "j",
    )

    /** Contacts that may be the spoken name, best first, score in 0..1; only scores >= [THRESHOLD]. Matches name and aliases. */
    fun rank(spoken: String, contacts: List<PhoneContact>): List<Pair<PhoneContact, Float>> {
        val s = key(spoken)
        if (s.isEmpty()) return emptyList()
        return contacts.map { it to score(s, it) }
            .filter { it.second >= THRESHOLD }
            .sortedByDescending { it.second }
    }

    /** True when the best match is clearly the one: score >= 0.92 and at least 0.08 ahead of the second. */
    fun sure(ranked: List<Pair<PhoneContact, Float>>): Boolean {
        val best = ranked.firstOrNull()?.second ?: return false
        val second = ranked.getOrNull(1)?.second ?: 0f
        return best >= 0.92f - EPS && best - second >= 0.08f - EPS
    }

    /** Similarity of two raw strings after the same folding as [rank], 0..1. Zero when either is empty after folding. */
    internal fun similarity(a: String, b: String): Float {
        val ka = key(a)
        val kb = key(b)
        if (ka.isEmpty() || kb.isEmpty()) return 0f
        return similar(ka, kb)
    }

    /** Lower case letters and single spaces, with the spelling folds and doubled letters collapsed. */
    private fun key(raw: String): String {
        val letters = raw.lowercase().map { if (it.isLetter()) it else ' ' }.joinToString("")
        val words = letters.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
        val folded = folds.fold(words) { s, (from, to) -> s.replace(from, to) }
        return folded.replace(Regex("([a-z])\\1+"), "$1")
    }

    /** Best similarity of the spoken key to the name, the aliases, and the first name. */
    private fun score(spoken: String, c: PhoneContact): Float {
        val candidates = listOf(c.name) + c.aliases + c.name.trim().substringBefore(' ')
        return candidates.map { key(it) }.filter { it.isNotEmpty() }
            .maxOfOrNull { similar(spoken, it) } ?: 0f
    }

    /** Same consonant skeleton ("nakul", "nakool" both NKL) counts as at least [PHONETIC]. */
    private fun similar(a: String, b: String): Float {
        val jw = jaroWinkler(a, b)
        val sa = skeleton(a)
        return if (sa.length >= 2 && sa == skeleton(b)) max(jw, PHONETIC) else jw
    }

    private fun skeleton(k: String): String = k.filter { it !in "aeiou " }

    private fun jaroWinkler(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val window = (max(a.length, b.length) / 2 - 1).coerceAtLeast(0)
        val aHit = BooleanArray(a.length)
        val bHit = BooleanArray(b.length)
        var common = 0
        for (i in a.indices) {
            for (j in max(0, i - window)..min(i + window, b.length - 1)) {
                if (!bHit[j] && a[i] == b[j]) {
                    aHit[i] = true
                    bHit[j] = true
                    common++
                    break
                }
            }
        }
        if (common == 0) return 0f
        var mismatched = 0
        var k = 0
        for (i in a.indices) {
            if (!aHit[i]) continue
            while (!bHit[k]) k++
            if (a[i] != b[k]) mismatched++
            k++
        }
        val m = common.toFloat()
        val jaro = (m / a.length + m / b.length + (m - mismatched / 2f) / m) / 3f
        if (jaro <= 0.7f) return jaro
        val prefix = a.zip(b).takeWhile { it.first == it.second }.take(4).size
        return jaro + prefix * 0.1f * (1 - jaro)
    }
}

/** The saved people: phone favourites and web-call favourites, joined by name. */
object PhoneBook {
    /**
     * Phone favourites (name to number) and web favourites (name to room) merged by name, case-insensitive; order kept,
     * phone first. A duplicate name keeps its first entry. Numbers go through [Phones.normalise]; unusable ones are dropped.
     * Then the phone's own [device] contacts: a new name is added; a name already here only fills in a missing number.
     */
    fun merge(
        phone: List<Pair<String, String>>,
        web: List<Pair<String, String>>,
        device: List<PhoneContact> = emptyList(),
    ): List<PhoneContact> {
        val byName = LinkedHashMap<String, PhoneContact>()
        for ((name, number) in phone) {
            val n = name.trim()
            if (n.isEmpty() || n.lowercase() in byName) continue
            byName[n.lowercase()] = PhoneContact(n, Phones.normalise(number))
        }
        for ((name, room) in web) {
            val n = name.trim()
            val r = room.trim()
            if (n.isEmpty() || r.isEmpty()) continue
            val existing = byName[n.lowercase()]
            byName[n.lowercase()] = existing?.copy(room = existing.room ?: r) ?: PhoneContact(n, null, r)
        }
        for (d in device) addDevice(byName, d)
        return byName.values.toList()
    }

    /** A device contact whose name is new is added; one whose name is already saved only fills in a missing number. */
    private fun addDevice(byName: MutableMap<String, PhoneContact>, d: PhoneContact) {
        val n = d.name.trim()
        if (n.isEmpty()) return
        val number = d.number?.let { Phones.normalise(it) }
        val existing = byName[n.lowercase()]
        when {
            existing == null -> byName[n.lowercase()] = d.copy(name = n, number = number)
            existing.number == null && number != null -> byName[n.lowercase()] = existing.copy(number = number)
        }
    }
}

/** How many people the picker shows when there are no favourites or starred contacts. */
private const val PICKER_SIZE = 8

/**
 * The people for the gaze "Who?" picker when no name was said: the favourites ([favouriteNames], matched
 * case-insensitively) and then the starred contacts that are not favourites, each in [all] order. When there are
 * none, the first [PICKER_SIZE] contacts.
 */
fun PhoneBook.shortlist(all: List<PhoneContact>, favouriteNames: Set<String>): List<PhoneContact> {
    val favs = favouriteNames.map { it.trim().lowercase() }.toSet()
    val favourites = all.filter { it.name.trim().lowercase() in favs }
    val starred = all.filter { it.starred && it.name.trim().lowercase() !in favs }
    return (favourites + starred).ifEmpty { all.take(PICKER_SIZE) }
}

/**
 * Chooses how a phone request is carried out, from what the contact has and what this phone can do. A call goes to the
 * phone's own phone app when the contact has a number; a web-call room is only the fallback for a contact without one.
 */
object PhoneRouter {
    private val emergency = setOf("112", "100", "101", "102", "108", "911", "999")

    fun route(verb: PhoneVerb, c: PhoneContact, body: String?, hasWhatsApp: Boolean): PhoneRoute =
        when (verb) {
            PhoneVerb.CALL -> call(c)
            PhoneVerb.MESSAGE -> message(c, body, hasWhatsApp)
        }

    /** True for the emergency numbers, after stripping spaces and "+". */
    fun isEmergency(number: String): Boolean = number.filter { it != ' ' && it != '+' } in emergency

    /**
     * Digits with the country code and no "+", for wa.me links. A 10-digit Indian mobile gets 91, a leading 0 trunk
     * prefix is dropped, and "00" is an international prefix. Null when the number is not usable.
     */
    fun waDigits(number: String): String? {
        val plus = number.trim().startsWith("+")
        val digits = number.filter { it.isDigit() }
        if (plus) return digits.takeIf { it.length in 8..15 }
        if (digits.startsWith("00")) return digits.drop(2).takeIf { it.length in 8..15 }
        val local = digits.removePrefix("0")
        return when {
            local.length == 10 && local[0] in '6'..'9' -> "91$local"
            local.length == 12 && local.startsWith("91") -> local
            else -> null
        }
    }

    private fun call(c: PhoneContact): PhoneRoute {
        val n = c.number
        return when {
            n != null && isEmergency(n) -> PhoneRoute.EmergencyDial(n)
            n != null -> PhoneRoute.Carrier(n, c.name)
            c.room != null -> PhoneRoute.WebRoom(c.room, c.name)
            else -> PhoneRoute.Unreachable("${c.name} has no phone number saved")
        }
    }

    private fun message(c: PhoneContact, body: String?, hasWhatsApp: Boolean): PhoneRoute {
        val n = c.number ?: return PhoneRoute.Unreachable("${c.name} has no phone number saved")
        val digits = waDigits(n)
        return if (hasWhatsApp && digits != null) PhoneRoute.WhatsApp(waUrl(digits, body))
        else PhoneRoute.Sms(n, body ?: "")
    }

    private fun waUrl(digits: String, body: String?): String {
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) return "https://wa.me/$digits"
        return "https://wa.me/$digits?text=" + URLEncoder.encode(text, "UTF-8").replace("+", "%20")
    }
}

/** What Mouna shows next for a phone request. */
sealed interface PhoneStep {
    /** Pick a person from [people], best match first. [body] is the text if the person already gave one. */
    data class Who(val verb: PhoneVerb, val body: String?, val people: List<PhoneContact>) : PhoneStep

    /** One person is clearly meant: confirm before anything happens. */
    data class Confirm(val verb: PhoneVerb, val c: PhoneContact, val body: String?) : PhoneStep

    /** Nobody saved by that name. */
    data class Missing(val name: String) : PhoneStep
}

/** The steps a phone request goes through: who, then what to say, then one confirm. Pure, so it is tested without a phone. */
object PhoneFlow {
    /** Message texts offered by gaze when the person gave none of their own. */
    val QUICK = listOf("Please come here", "I need help", "I'm okay", "Please call me")

    /**
     * No name: everyone saved, to pick from. A name that clearly matches one person: confirm them. Several that could
     * be it: pick between them, best first. Nothing close, or no contacts at all: say who is missing.
     */
    fun start(cmd: PhoneCommand, contacts: List<PhoneContact>): PhoneStep {
        val resolved = PhoneParser.resolve(cmd, contacts)
        val name = resolved.name ?: return PhoneStep.Who(resolved.verb, resolved.body, contacts)
        if (contacts.isEmpty()) return PhoneStep.Missing(name)
        val ranked = NameMatch.rank(name, contacts)
        return when {
            ranked.isEmpty() -> PhoneStep.Missing(name)
            NameMatch.sure(ranked) -> PhoneStep.Confirm(resolved.verb, ranked.first().first, resolved.body)
            else -> PhoneStep.Who(resolved.verb, resolved.body, ranked.map { it.first })
        }
    }

    /**
     * The two items on screen for [page], left then right. Each page moves on two items and wraps round, so an odd
     * count still reaches every item. One item shows alone.
     */
    fun <T> pair(items: List<T>, page: Int): List<T> {
        if (items.isEmpty()) return emptyList()
        if (items.size == 1) return listOf(items[0])
        val start = Math.floorMod(page * 2, items.size)
        return listOf(items[start], items[(start + 1) % items.size])
    }
}

/** An app the person can open: its label as the launcher shows it, and its package. */
data class AppInfo(val label: String, val pkg: String)

/**
 * Reads an open-an-app request ("open youtube", "youtube kholo") with fixed patterns, no ML. It does not decide whether
 * the name is a real app: [AppMatch] does that against the installed apps, so "open the door" just matches nothing.
 */
object AppParser {
    private val leading = Regex("^(?:(?:can you|could you|would you|will you|please|pls|plz|kindly|mouna|hey|hi|hello|ok|okay|just) )+")
    private val trailing = Regex("(?: (?:now|abhi|jaldi|please|pls|plz|mouna|today))+$")
    private val hinglishVerb = Regex("(?:^| )(?:kholo|khol do|open karo|open kar do|chalao|chala do|start karo)$")
    private val englishVerb = Regex("^(?:open|launch|start|run|show me) (.+)$")

    /** Words that are never an app name on their own, so "open the app" or "start karo" are not requests. */
    private val notAnApp = setOf("", "app", "the", "a", "an", "it", "this", "that", "karo", "kar", "do", "kholo", "khol", "chalao", "chala")

    /** The app name in [text], lower case as said; null when the text is not an open request. */
    fun parse(text: String): String? {
        val s = clean(text).replace(leading, "").replace(trailing, "")
        val core = s.replace(hinglishVerb, "")
        val named = englishVerb.matchEntire(core)?.groupValues?.get(1) ?: if (core != s) core else null
        return named?.let { nameOf(it) }
    }

    private fun clean(text: String): String =
        text.lowercase().map { if (it.isLetterOrDigit() || it == ' ') it else ' ' }
            .joinToString("").split(' ').filter { it.isNotEmpty() }.joinToString(" ")

    /** The name without a leading "the" or a trailing "app"; null when nothing is left. */
    private fun nameOf(raw: String): String? {
        val name = raw.removePrefix("the ").removeSuffix(" app").trim()
        return name.takeIf { it !in notAnApp }
    }
}

/**
 * Matches a spoken app name to the installed apps, with the same folding as [NameMatch]. Spaces are ignored, so
 * "you tube" and "YouTube" are the same app.
 */
object AppMatch {
    /** Lowest score that still counts as a possible app. */
    const val THRESHOLD = 0.8f

    private const val EPS = 1e-4f

    /**
     * Installed apps that may be [spoken], best first, score in 0..1; only scores >= [THRESHOLD]. An exact (normalised)
     * label match is 1.0 and always first.
     */
    fun rank(spoken: String, apps: List<AppInfo>): List<Pair<AppInfo, Float>> {
        val s = squash(spoken)
        if (s.isEmpty()) return emptyList()
        return apps.map { it to NameMatch.similarity(s, squash(it.label)) }
            .filter { it.second >= THRESHOLD }
            .sortedByDescending { it.second }
    }

    /** True when the best app is clearly the one: score >= 0.92 and at least 0.08 ahead of the second. */
    fun sure(ranked: List<Pair<AppInfo, Float>>): Boolean {
        val best = ranked.firstOrNull()?.second ?: return false
        val second = ranked.getOrNull(1)?.second ?: 0f
        return best >= 0.92f - EPS && best - second >= 0.08f - EPS
    }

    private fun squash(s: String): String = s.replace(" ", "")
}
