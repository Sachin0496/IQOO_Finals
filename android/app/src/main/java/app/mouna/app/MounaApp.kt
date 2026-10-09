package app.mouna.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.mouna.app.engine.CallLink
import app.mouna.app.engine.CallPhrases
import app.mouna.app.engine.CallState
import app.mouna.app.engine.Engine
import app.mouna.app.engine.Phones
import android.os.SystemClock
import app.mouna.app.engine.Event
import app.mouna.app.engine.Lang
import app.mouna.app.engine.Listen
import app.mouna.app.engine.Phrase
import app.mouna.app.engine.Hearing
import app.mouna.app.engine.Isl
import app.mouna.app.engine.Listener
import app.mouna.app.engine.Voice
import app.mouna.app.engine.VoiceMatcher
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import app.mouna.core.AskNode
import app.mouna.core.Decision
import app.mouna.core.DecisionKind
import app.mouna.core.Zone

enum class Screen { SPEAK, TEACH, ASK, CALL, SETTINGS, EYES, SWITCH }

/** On a call, the lower half of the Call screen: tap-to-speak phrases, or the live Speak screen (lips, sign). */
enum class CallTab { PHRASES, MOUTH }

/** What Mouna shows when it is not sure enough to speak (core.md: "What the app shows for each decision"). */
sealed interface Prompt {
    /** From the core: CONFIRM, RESCUE, CHOOSE, ASK or NOT_TAUGHT. */
    data class FromCore(val d: Decision) : Prompt
    /** "Wrong?" after speaking, or "No" to a confirm: every phrase, plus "None of these". */
    data object PickAny : Prompt
    /** Voice mode heard words that match none of the person's phrases: offer to say them clearly. */
    data class Heard(val text: String) : Prompt
    /** Sign mode wasn't sure: the likeliest ISL words to pick from. */
    data class Signed(val words: List<String>) : Prompt
}

enum class Channel { LIPS, VOICE, SIGN }

data class Said(val phrase: Phrase?, val text: String, val via: String)

/**
 * The app's state and the rules that connect the engine's events to screens. Lives as long as the activity.
 * Everything here runs on the main thread; the engine hops to its own threads.
 */
class MounaApp(
    val engine: Engine,
    val voice: Voice,
    val hearing: Hearing,
    /** Asks for the microphone if needed; calls back with the answer. */
    private val askMic: ((Boolean) -> Unit) -> Unit,
    /** The phone call transport (the carrier today); Voice hands it audio if it asks for it. */
    private val link: CallLink,
    /** Asks for the permission to call; calls back with whether calling is allowed. */
    private val askCall: ((Boolean) -> Unit) -> Unit,
    /** Opens the contact picker; calls back with the name and number picked, or null. */
    private val pickContact: ((Pair<String, String>?) -> Unit) -> Unit,
) {
    val store = engine.store
    var phrases by mutableStateOf(engine.phrases)
        private set

    var channel by mutableStateOf(runCatching { Channel.valueOf(store.listenWith.uppercase()) }.getOrDefault(Channel.LIPS))
        private set
    /** Microphone level 0..1, for the voice ring. */
    var micLevel by mutableStateOf(0f)
        private set
    var heard by mutableStateOf<String?>(null)
        private set
    var hearingBusy by mutableStateOf(false)
        private set
    var voiceStatus by mutableStateOf(hearing.status)
        private set
    /** Voice teaching: the phrase whose next utterance becomes a template. */
    var voiceTeaching by mutableStateOf<String?>(null)
        private set
    var voiceTemplates by mutableStateOf(store.voiceTemplates)
        private set
    private var lastHeard: String? = null
    private val main = Handler(Looper.getMainLooper())
    private val asr = Executors.newSingleThreadExecutor()
    private val listener = Listener(
        onLevel = { l -> main.post { micLevel = l } },
        onUtterance = { samples ->
            main.post { hearingBusy = true }
            asr.execute {
                val text = runCatching { hearing.transcribe(samples) }.getOrDefault("")
                main.post {
                    hearingBusy = false
                    onHeard(text)
                }
            }
        },
    )

    // ---------------- calls ----------------

    var callState by mutableStateOf(CallState.IDLE)
        private set
    /** The number being typed on the dial pad, and the name it was picked with (if any). */
    var dialNumber by mutableStateOf("")
        private set
    var dialName by mutableStateOf<String?>(null)
        private set
    /** Who the current call is with, and when it connected (elapsedRealtime), for the clock. */
    var callWith by mutableStateOf("")
        private set
    var callSince by mutableStateOf(0L)
        private set
    var callNote by mutableStateOf<String?>(null)
        private set
    var callTab by mutableStateOf(CallTab.PHRASES)
        private set
    var favourites by mutableStateOf(store.favourites)
        private set
    var callerName by mutableStateOf(store.callerName)
        private set
    val onCall get() = callState != CallState.IDLE

    init {
        voice.link = link
        link.onState = { st ->
            callState = st
            voice.callMode = st != CallState.IDLE
            if (st == CallState.ACTIVE) callSince = SystemClock.elapsedRealtime()
            if (st == CallState.IDLE) callSince = 0L
            applyChannel() // the microphone stays off on a call: it would hear the other person
        }
        asr.execute {
            hearing.load()
            main.post { voiceStatus = hearing.status }
        }
    }

    var screen by mutableStateOf(Screen.SPEAK)
        private set
    var prompt by mutableStateOf<Prompt?>(null)
        private set
    var said by mutableStateOf<Said?>(null)
        private set
    var lang by mutableStateOf(store.lang)
        private set
    var voiceId by mutableStateOf(store.voice)
        private set
    var careful by mutableStateOf(store.careful)
        private set
    /** Bumped on each "yes" from the body (switch, nod, double blink), for screens that react to it (Ask, scanning). */
    var switchPresses by mutableStateOf(0)
        private set
    /** Bumped on each "no" (head shake), for Ask. */
    var noSignals by mutableStateOf(0)
        private set
    var lastAnswerVia by mutableStateOf<String?>(null)
        private set
    var lastTeach by mutableStateOf<Event.Taught?>(null)
        private set
    /** Counts taught examples, so "keep going" fires even when two in a row look the same. */
    var teachSeq by mutableStateOf(0)
        private set

    /** Where lips, voice and sign listen: Speak, or the Mouth/Sign half of the Call screen. */
    private fun onSpeakSurface() = screen == Screen.SPEAK || (screen == Screen.CALL && callTab == CallTab.MOUTH)

    fun go(s: Screen) {
        screen = s
        prompt = null
        voiceTeaching = null
        engine.gazeOn(false)
        applyChannel()
    }

    /** Lips listen through the camera; Voice through the microphone. Only on Speak (Teach arms them itself). */
    private fun applyChannel() {
        val speak = onSpeakSurface() && prompt == null
        engine.listen(if (speak && channel == Channel.LIPS) Listen.SPEAK else Listen.PAUSED)
        engine.gesturesOn(prompt != null || screen == Screen.ASK)
        engine.signing(onSpeakSurface() && channel == Channel.SIGN)
        val mic = ((speak && channel == Channel.VOICE) || voiceTeaching != null) && !onCall
        if (mic) listener.start() else listener.stop()
        if (!mic) micLevel = 0f
    }

    fun chooseChannel(c: Channel) {
        if (c == Channel.VOICE) {
            askMic { granted ->
                if (!granted) return@askMic
                channel = c
                store.listenWith = "voice"
                applyChannel()
            }
        } else {
            channel = c
            store.listenWith = c.name.lowercase()
            applyChannel()
        }
    }

    // ---------------- voice ----------------

    private fun onHeard(text: String) {
        heard = text
        val teach = voiceTeaching
        if (teach != null) {
            if (text.isNotBlank()) addTemplate(teach, text)
            voiceTeaching = null
            applyChannel()
            return
        }
        if (!onSpeakSurface() || prompt != null || text.isBlank()) return
        val ranked = VoiceMatcher.rank(text, voiceCandidates())
        android.util.Log.i("Mouna", "heard \"$text\" -> " + ranked.take(3).joinToString { "${it.id} %.2f".format(it.score) })
        val best = ranked.firstOrNull()
        val second = ranked.getOrNull(1)?.score ?: 0.0
        lastHeard = text
        if (best != null && best.score >= VoiceMatcher.SPEAK && best.score - second >= VoiceMatcher.MARGIN) {
            addTemplate(best.id, text) // a clear hit sharpens the template too
            lastHeard = null
            speak(best.id, "voice")
            return
        }
        val options = ranked.filter { it.score >= VoiceMatcher.SHOW }.take(4).map { it.id }
        prompt = when (options.size) {
            0 -> Prompt.Heard(text)
            1 -> Prompt.FromCore(Decision(DecisionKind.CONFIRM, options, "fairly sure: confirm first"))
            2 -> Prompt.FromCore(Decision(DecisionKind.RESCUE, options, "two sound alike"))
            else -> Prompt.FromCore(Decision(DecisionKind.CHOOSE, options, "a few are possible"))
        }
        applyChannel()
    }

    /** QA: run a recording through exactly the live path (transcribe, match, speak). Debug builds only. */
    fun hearRecording(samples: FloatArray) {
        hearingBusy = true
        asr.execute {
            val text = runCatching { hearing.transcribe(samples) }.getOrDefault("")
            main.post {
                hearingBusy = false
                onHeard(text)
            }
        }
    }

    private fun voiceCandidates(): Map<String, List<String>> =
        engine.knowledge.value.pack.mapNotNull { id -> phrases[id]?.let { p -> id to (listOf(p.say(Lang.EN)) + voiceTemplates[id].orEmpty()) } }.toMap()

    private fun addTemplate(id: String, text: String) {
        val list = (voiceTemplates[id].orEmpty() + text).takeLast(6)
        voiceTemplates = voiceTemplates + (id to list)
        store.voiceTemplates = voiceTemplates
    }

    /** Teach: the next thing the person says becomes a template for [id]. */
    fun teachVoice(id: String) {
        askMic { granted ->
            if (!granted) return@askMic
            heard = null
            voiceTeaching = id
            applyChannel()
        }
    }

    fun stopVoiceTeaching() {
        voiceTeaching = null
        applyChannel()
    }

    /** A sign: speak a clear winner, otherwise offer the likeliest words. Words are spoken by the phone's voice. */
    private fun signed(g: List<Isl.Guess>) {
        if (!onSpeakSurface() || prompt != null || channel != Channel.SIGN || g.isEmpty()) return
        android.util.Log.i("Mouna", "signed -> " + g.take(3).joinToString { "${it.word} %.2f".format(it.p) })
        val best = g[0]
        val second = g.getOrNull(1)?.p ?: 0f
        if (best.p >= SIGN_SPEAK && best.p - second >= SIGN_MARGIN) {
            sayWord(best.word)
            return
        }
        prompt = Prompt.Signed(g.take(3).map { it.word })
        applyChannel()
    }

    fun sayWord(word: String) {
        prompt = null
        voice.say(null, word, Lang.EN, Voice.DEVICE)
        said = Said(null, word.replaceFirstChar { it.uppercase() }, "sign")
        applyChannel()
    }

    /** Words Mouna heard but that are not a phrase: say them clearly, in the phone's voice. */
    fun sayHeard(text: String) {
        prompt = null
        voice.say(null, text, Lang.EN, Voice.DEVICE)
        said = Said(null, text, "voice")
        applyChannel()
    }

    // ---------------- the family's own words ----------------

    fun addCustom(text: String) {
        if (text.isBlank()) return
        engine.addCustom(text)
        phrases = engine.phrases
    }

    fun removeCustom(id: String) {
        engine.removeCustom(id)
        phrases = engine.phrases
    }



    fun onEvent(e: Event) {
        when (e) {
            is Event.Decided -> if (onSpeakSurface() && prompt == null) decided(e.decision)
            is Event.Taught -> {
                lastTeach = e
                teachSeq++
            }
            is Event.NegativeAdded -> Unit
            is Event.SwitchPressed -> switched("switch")
            is Event.Signed -> signed(e.guesses)
            is Event.Answer -> if (e.yes) switched(e.via) else shook()
            is Event.Looked -> looked(e.zone)
        }
    }

    private fun decided(d: Decision) {
        // Nothing taught yet: the Speak screen already says "teach first"; don't answer every mouthing with a prompt.
        if (d.kind == DecisionKind.NOT_TAUGHT && d.options.isEmpty() && engine.knowledge.value.counts.isEmpty()) return
        if (d.kind == DecisionKind.SPEAK) {
            speak(d.options[0], "lips")
            return
        }
        if (d.kind == DecisionKind.ASK && d.options.isEmpty()) {
            go(Screen.ASK)
            return
        }
        lastHeard = null
        prompt = Prompt.FromCore(d)
        applyChannel() // lips (and voice) wait while the person answers with eyes, switch or touch
        engine.gazeOn(d.kind == DecisionKind.RESCUE)
    }

    /** The person answered a prompt with [id]: say it, and learn from the mouthing (or words) that led here. */
    fun choose(id: String, via: String) {
        val heardText = lastHeard
        if (heardText != null) addTemplate(id, heardText) else if (prompt != null) engine.picked(id)
        lastHeard = null
        close()
        speak(id, via)
    }

    fun noneOfThese() {
        if (lastHeard == null) engine.noneOfThese()
        lastHeard = null
        close()
        said = Said(null, "Not one of my phrases", "none")
    }

    /** "No" to a confirm, or "Wrong?" after speaking: show every phrase. */
    fun wrong() {
        prompt = Prompt.PickAny
        engine.gazeOn(false)
        applyChannel()
    }

    fun close() {
        prompt = null
        engine.gazeOn(false)
        applyChannel()
    }

    fun speak(id: String, via: String) {
        val p = phrases[id]
        val text = p?.say(lang) ?: id
        voice.say(id, text, lang, voiceId)
        said = Said(p, text, via)
    }

    fun speakAsk(node: AskNode) {
        val p = node.phrase?.let { phrases[it] }
        val text = p?.say(lang) ?: node.say?.get(lang.tag) ?: node.say?.get("en") ?: node.ask[lang.tag] ?: node.id
        voice.say(node.phrase, text, lang, voiceId)
        said = Said(p, text, "ask")
    }

    /** A "yes" from the body: the personal switch, a nod or a double blink ([via]). */
    private fun switched(via: String) {
        switchPresses++
        lastAnswerVia = via
        val pr = prompt
        if (!onSpeakSurface()) return
        when {
            // Idle: the person's own movement means "I need something" -> the yes/no questions.
            pr == null -> if (via == "switch") go(Screen.ASK)
            pr is Prompt.Heard -> sayHeard(pr.text)
            pr is Prompt.FromCore && pr.d.kind == DecisionKind.CONFIRM -> choose(pr.d.options[0], via)
            // RESCUE: the switch confirms the side the eyes are on (the demo's "look left, raise an eyebrow").
            pr is Prompt.FromCore && pr.d.kind == DecisionKind.RESCUE -> when (engine.live.value.gazeZone) {
                Zone.LEFT -> choose(pr.d.options[0], "eyes + $via")
                Zone.RIGHT -> choose(pr.d.options.getOrElse(1) { pr.d.options[0] }, "eyes + $via")
                Zone.CENTER -> Unit
            }
            // CHOOSE / NOT_TAUGHT / PickAny: scanning; the prompt UI picks the highlighted picture.
            else -> Unit
        }
    }

    /** A head shake: "no" to a confirm or a "Did you say", "no" in Ask. */
    private fun shook() {
        noSignals++
        lastAnswerVia = "shake"
        when (val pr = prompt) {
            is Prompt.Heard -> close()
            is Prompt.FromCore -> if (pr.d.kind == DecisionKind.CONFIRM || pr.d.kind == DecisionKind.RESCUE) wrong()
            else -> Unit
        }
    }

    private fun looked(zone: Zone) {
        val pr = prompt as? Prompt.FromCore ?: return
        if (pr.d.kind != DecisionKind.RESCUE) return
        // Without a personal switch, a long look is the answer; with one, the look only highlights.
        if (engine.knowledge.value.switchReady) return
        when (zone) {
            Zone.LEFT -> choose(pr.d.options[0], "eyes")
            Zone.RIGHT -> choose(pr.d.options.getOrElse(1) { pr.d.options[0] }, "eyes")
            Zone.CENTER -> Unit
        }
    }

    fun chooseLang(l: Lang) {
        lang = l
        store.lang = l
    }

    fun chooseVoice(id: String) {
        voiceId = id
        store.voice = id
    }

    fun chooseCareful(on: Boolean) {
        careful = on
        store.careful = on
    }

    /** QA and rehearsal: show what the person would see for a decision of [kind], with phrases from their pack. */
    fun preview(kind: DecisionKind) {
        val pack = engine.knowledge.value.pack
        val n = when (kind) {
            DecisionKind.CONFIRM -> 1
            DecisionKind.RESCUE, DecisionKind.NOT_TAUGHT -> 2
            DecisionKind.CHOOSE -> 3
            else -> 4
        }
        go(Screen.SPEAK)
        decided(Decision(kind, pack.take(n), if (kind == DecisionKind.CONFIRM) "fairly sure: confirm first" else "preview", maybeNone = kind == DecisionKind.NOT_TAUGHT))
    }

    // ---------------- calls ----------------

    fun pressDigit(c: Char) {
        if (dialNumber.length < 20) dialNumber += c
        dialName = null // typing changes whose number this is
    }

    fun backspace() {
        dialNumber = dialNumber.dropLast(1)
        if (dialNumber.isEmpty()) dialName = null
    }

    fun setDial(number: String, name: String?) {
        dialNumber = number
        dialName = name
    }

    fun chooseContact() = pickContact { c -> if (c != null) setDial(Phones.normalise(c.second) ?: c.second, c.first) }

    fun saveFavourite() {
        val name = dialName ?: return
        val number = Phones.normalise(dialNumber) ?: return
        favourites = favourites.filter { it.second != number } + (name to number)
        store.favourites = favourites
    }

    fun removeFavourite(number: String) {
        favourites = favourites.filter { it.second != number }
        store.favourites = favourites
    }

    fun dial() {
        val number = Phones.normalise(dialNumber) ?: return
        callNote = null
        askCall { allowed ->
            if (!allowed) {
                callNote = "Allow Mouna to make phone calls to call from here."
                return@askCall
            }
            callWith = dialName ?: Phones.pretty(number)
            if (!link.dial(number)) {
                callNote = "The call could not be started."
                return@askCall
            }
            callTab = CallTab.PHRASES
            applyChannel()
            // Words worth having ready, so the first tap is as fast as the tenth.
            voice.prefetch(CallPhrases.warm(lang, callerName), lang, voiceId)
        }
    }

    fun hangUp() {
        if (!link.hangUp()) callNote = "Mouna can't end the call itself on this phone. End it on the phone's call screen."
    }

    fun chooseCallTab(t: CallTab) {
        callTab = t
        prompt = null
        applyChannel()
    }

    fun chooseCallerName(name: String) {
        callerName = name
        store.callerName = name
    }

    fun intro() = sayCall(null, CallPhrases.intro(callerName, lang), lang, "intro")

    fun quick(q: app.mouna.app.engine.QuickPhrase) = sayCall(q.packId, q.say(lang), lang, "call")

    /** Anything typed on the call screen, in whichever script it is written. */
    fun sayTyped(text: String) {
        val t = text.trim()
        if (t.isNotEmpty()) sayCall(null, t, CallPhrases.langOf(t, lang), "typed")
    }

    /** QA: speak [text] as if on a call or not, through the same Voice path (adb: app.mouna.SAY). */
    fun debugSay(text: String, l: Lang, voiceId: String) = sayCall(null, text, l, "debug", voiceId)

    private fun sayCall(packId: String?, text: String, l: Lang, via: String, voiceId: String = this.voiceId) {
        voice.say(packId, text, l, voiceId)
        said = Said(null, text, via)
    }

    fun clearSaid() {
        said = null
    }

    fun shutdown() {
        listener.stop()
        asr.execute { hearing.close() }
        asr.shutdown()
    }

    companion object {
        /** ISL: speak only a clear winner (model probability, not a measured accuracy). */
        const val SIGN_SPEAK = 0.6f
        const val SIGN_MARGIN = 0.25f
    }
}
