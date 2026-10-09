package app.mouna.app.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.mouna.app.sense.DoubleBlink
import app.mouna.app.sense.Frame
import app.mouna.app.sense.Gesture
import app.mouna.app.sense.HeadGesture
import app.mouna.app.sense.Sensor
import app.mouna.core.Decision
import app.mouna.core.GazeModel
import app.mouna.core.GazeSelector
import app.mouna.core.Learner
import app.mouna.core.PairScore
import app.mouna.core.PersonalSwitch
import app.mouna.core.SwitchModel
import app.mouna.core.SwitchTeachResult
import app.mouna.core.TeachRequest
import app.mouna.core.Zone
import app.mouna.core.decide
import app.mouna.core.teachSwitch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** What the lips are used for right now. */
enum class Listen { SPEAK, TEACH, NEGATIVE, PAUSED }

/** Per-frame state for the camera card. Updated ~30 times a second. */
data class Live(
    val face: Boolean = false,
    val hearing: Boolean = false,
    val outer: List<Pair<Float, Float>> = emptyList(),
    val imageW: Int = 0,
    val imageH: Int = 0,
    val fps: Float = 0f,
    val switchLevel: Double = 0.0,
    val gazeZone: Zone = Zone.CENTER,
    /** Sign mode: a body is in view / hands are up / a sign is being recorded. */
    val body: Boolean = false,
    val hands: Int = 0,
    val signing: Boolean = false,
    val yawDeg: Float = 0f,
)

/** The person's pack and how well Mouna knows it. */
data class Knowledge(
    val encoder: EncoderReport = EncoderReport("Starting", "Loading the lip encoder…"),
    val ready: Boolean = false,
    val pack: List<String> = emptyList(),
    val counts: Map<String, Int> = emptyMap(),
    val negatives: Int = 0,
    val pairs: List<PairScore> = emptyList(),
    val next: TeachRequest? = null,
    val listen: Listen = Listen.PAUSED,
    val teaching: String? = null,
    val switchReady: Boolean = false,
    val gazeReady: Boolean = false,
    val lastMs: Double? = null,
    val islReady: Boolean = false,
    /** The ISL model has been looked for (found or not): until then the screen says "Loading…", not "No ISL model". */
    val islKnown: Boolean = false,
)

sealed interface Event {
    /** A mouthing in Speak mode. [ms]: from the end of the utterance to the decision (E8 budget: 700 ms to voice). */
    data class Decided(val decision: Decision, val ms: Double) : Event
    /** check: null for a first example, else whether Mouna already recognised it before learning it. */
    data class Taught(val intent: String, val check: Boolean?) : Event
    data class NegativeAdded(val count: Int) : Event
    data object SwitchPressed : Event
    /** A sign was seen; the most likely words from the ISL model, best first. */
    data class Signed(val guesses: List<Isl.Guess>, val ms: Double) : Event
    /** Nod or double blink = yes, shake = no. Only while Mouna is asking (see [Engine.gesturesOn]). */
    data class Answer(val yes: Boolean, val via: String) : Event
    data class Looked(val zone: Zone) : Event
}

/**
 * The app's engine. Threads: the camera analysis thread runs the face model, the gate, the switch and the gaze
 * selector; one worker thread owns the encoder and the Learner (which is not thread-safe). The UI only reads flows
 * and calls the methods below, which hop to the right thread.
 */
class Engine(private val context: Context, private val bundled: PhrasePack, val store: Store) {
    /** Bundled phrases + Mouna's warm defaults + the family's own words. */
    @Volatile var phrases: PhrasePack = bundled.withExtras(store.custom)
        private set
    @Volatile var tiers = phrases.tiers()
        private set

    val analysis = Executors.newSingleThreadExecutor()
    private val worker = Executors.newSingleThreadExecutor()

    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live.asStateFlow()
    private val _knowledge = MutableStateFlow(Knowledge())
    val knowledge: StateFlow<Knowledge> = _knowledge.asStateFlow()
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 32)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    @Volatile var sensor: Sensor? = null
        private set
    private val segmenter = Segmenter()
    private val taps = CopyOnWriteArrayList<(Frame) -> Unit>()
    private var lastFrameMs = 0L

    // worker thread only
    private var encoder: LipEncoder = ShapeEncoder()
    private var learner = Learner()
    private var examples = Store.Examples(LinkedHashMap(), mutableListOf())
    private var lastEmbedding: FloatArray? = null
    private var lastMs: Double? = null
    private var isl: Isl? = null
    @Volatile private var islKnown = false
    @Volatile private var signMode = false
    // analysis thread: the sign being recorded
    private var signFrames: MutableList<FloatArray>? = null
    private var handsUp = 0
    private var handsDown = 0

    @Volatile private var listen = Listen.PAUSED
    @Volatile private var teaching: String? = null
    @Volatile private var ready = false
    @Volatile private var personalSwitch: PersonalSwitch? = store.switchModel?.let { PersonalSwitch(it) }
    @Volatile private var gazeModel: GazeModel? = store.gaze
    @Volatile private var gaze: GazeSelector? = null
    @Volatile private var gestures = false
    private var head = HeadGesture()
    private var blink = DoubleBlink()

    val pack: List<String> get() = _knowledge.value.pack
    private val defaultPack get() = phrases.demo + "love"

    /** Add the family's own words as a phrase (spoken by the phone's voice) and put it in the pack. */
    fun addCustom(text: String): String {
        val id = "my_" + System.currentTimeMillis().toString(36)
        store.custom = store.custom + (id to text.trim())
        phrases = bundled.withExtras(store.custom)
        tiers = phrases.tiers()
        setPack(pack + id)
        return id
    }

    fun removeCustom(id: String) {
        store.custom = store.custom.filter { it.first != id }
        setPack(pack - id)
        phrases = bundled.withExtras(store.custom)
        tiers = phrases.tiers()
    }

    fun start() {
        analysis.execute { sensor = runCatching { Sensor(context, ::onFrame) }.onFailure { Log.e(TAG, "sensor", it) }.getOrNull() }
        worker.execute {
            isl = runCatching { Isl.open(context) }.getOrNull()
            publish() // ISL is known now; the encoder can take minutes on its first NPU compile
            islKnown = true
            publish()
            val (ort, report) = OrtEncoder.open(context) { Log.w(TAG, it) }
            encoder = ort ?: ShapeEncoder()
            examples = store.load(encoder.id)
            learner = Learner()
            for ((k, xs) in examples.samples) for (x in xs) learner.addSample(k, x)
            for (x in examples.negatives) learner.addNegative(x)
            ready = true
            publish(report)
        }
    }

    // ---------------- analysis thread ----------------

    private fun onFrame(f: Frame) {
        val dt = if (lastFrameMs > 0) f.tMs - lastFrameMs else 0
        lastFrameMs = f.tMs
        if (signMode) {
            onSignFrame(f)
            val prev = _live.value
            _live.value = prev.copy(
                face = false, hearing = false, outer = emptyList(), imageW = f.imageW, imageH = f.imageH,
                fps = if (dt > 0) prev.fps + 0.1f * (1000f / dt - prev.fps) else prev.fps,
                body = f.sign != null, hands = f.hands, signing = signFrames != null,
            )
            return
        }
        val sw = personalSwitch
        if (sw != null && f.blend != null && sw.push(f.blend, f.tMs)) _events.tryEmit(Event.SwitchPressed)
        val g = gaze
        if (g != null && !f.iris.isNaN()) g.push(f.iris, f.tMs)?.let { _events.tryEmit(Event.Looked(it)) }
        for (t in taps) t(f)
        if (gestures && f.face) {
            when (head.push(f.yawDeg, f.pitch, f.tMs)) {
                Gesture.NOD -> _events.tryEmit(Event.Answer(true, "nod"))
                Gesture.SHAKE -> _events.tryEmit(Event.Answer(false, "shake"))
                null -> Unit
            }
            if (blink.push(f.eyeOpen, f.tMs)) _events.tryEmit(Event.Answer(true, "double blink"))
        }

        val mode = listen
        var clip: Clip? = null
        if (ready && mode != Listen.PAUSED) clip = segmenter.push(f) else segmenter.reset()
        if (clip != null) {
            val intent = teaching
            worker.execute { handle(clip, mode, intent) }
        }
        val prev = _live.value
        _live.value = Live(
            face = f.face,
            hearing = segmenter.recording,
            outer = f.outer,
            imageW = f.imageW,
            imageH = f.imageH,
            fps = if (dt > 0) prev.fps + 0.1f * (1000f / dt - prev.fps) else prev.fps,
            switchLevel = sw?.level ?: 0.0,
            gazeZone = g?.zone ?: Zone.CENTER,
            yawDeg = f.yawDeg,
        )
    }

    /** A sign = hands up, signing, hands down (or out of view). Recorded only while a body is in view. */
    private fun onSignFrame(f: Frame) {
        val kp = f.sign
        val rec = signFrames
        if (rec == null) {
            handsUp = if (kp != null && f.hands > 0) handsUp + 1 else 0
            if (handsUp >= 3 && kp != null) {
                signFrames = mutableListOf(kp)
                handsDown = 0
            }
            return
        }
        if (kp != null) rec.add(kp)
        handsDown = if (kp == null || f.hands == 0) handsDown + 1 else 0
        if (handsDown >= 10 || rec.size >= 150) {
            signFrames = null
            handsUp = 0
            val frames = rec.dropLast(minOf(handsDown, rec.size))
            if (frames.size >= 12) {
                val endMs = f.tMs
                worker.execute {
                    val model = isl ?: return@execute
                    val g = runCatching { model.classify(frames) }.onFailure { Log.e(TAG, "isl", it) }.getOrNull() ?: return@execute
                    _events.tryEmit(Event.Signed(g, (SystemClock.uptimeMillis() - endMs).toDouble()))
                }
            }
        }
    }

    /** QA: classify recorded keypoints (frames of 27 x 2) and log the top words. */
    fun classifySign(frames: List<FloatArray>) = worker.execute {
        val g = isl?.classify(frames)
        Log.i(TAG, "isl check -> " + (g?.joinToString { "${it.word} %.4f".format(it.p) } ?: "no model"))
    }

    /** Sign mode: the camera runs body + hands for ISL instead of the face. */
    fun signing(on: Boolean) {
        if (on == signMode) return
        signMode = on
        sensor?.signing = on
        analysis.execute { signFrames = null; handsUp = 0 }
    }

    // ---------------- worker thread ----------------

    private fun handle(clip: Clip, mode: Listen, intent: String?) {
        val emb = runCatching { encoder.embed(clip) }.getOrElse {
            Log.e(TAG, "embed", it)
            return
        }
        when (mode) {
            Listen.SPEAK -> {
                val d = decide(learner.predict(emb), tiers, store.careful)
                lastEmbedding = emb
                val ms = (SystemClock.uptimeMillis() - clip.frames.last().tMs).toDouble()
                lastMs = ms
                _events.tryEmit(Event.Decided(d, ms))
                publish()
            }
            Listen.TEACH -> if (intent != null) {
                val check = learner.teach(intent, emb)
                examples.samples.getOrPut(intent) { mutableListOf() }.add(emb)
                store.save(encoder.id, examples)
                listen = Listen.PAUSED
                teaching = null
                publish()
                _events.tryEmit(Event.Taught(intent, check))
            }
            Listen.NEGATIVE -> {
                learner.addNegative(emb)
                examples.negatives.add(emb)
                store.save(encoder.id, examples)
                listen = Listen.PAUSED
                publish()
                _events.tryEmit(Event.NegativeAdded(examples.negatives.size))
            }
            Listen.PAUSED -> Unit
        }
    }

    private fun publish(report: EncoderReport? = null) {
        val k = _knowledge.value
        val pack = store.pack ?: defaultPack
        _knowledge.value = k.copy(
            encoder = report ?: k.encoder,
            ready = ready,
            pack = pack,
            counts = learner.intents.associateWith { learner.count(it) },
            negatives = learner.negativeCount,
            pairs = learner.separability(),
            next = learner.nextToTeach(pack),
            listen = listen,
            teaching = teaching,
            switchReady = personalSwitch != null,
            gazeReady = gazeModel != null,
            lastMs = lastMs,
            islReady = isl != null,
            islKnown = islKnown,
        )
    }

    // ---------------- UI calls ----------------

    fun listen(mode: Listen, intent: String? = null) = worker.execute {
        teaching = if (mode == Listen.TEACH) intent else null
        listen = mode
        analysis.execute { segmenter.reset() }
        publish()
    }

    /** The person picked [intent] after Mouna wasn't sure: learn from that mouthing (the Lab's tap-to-correct). */
    fun picked(intent: String) = worker.execute {
        val e = lastEmbedding ?: return@execute
        lastEmbedding = null
        learner.teach(intent, e)
        examples.samples.getOrPut(intent) { mutableListOf() }.add(e)
        store.save(encoder.id, examples)
        publish()
    }

    /** "None of these": that mouthing becomes a negative example (core.md: add every "none of these"). */
    fun noneOfThese() = worker.execute {
        val e = lastEmbedding ?: return@execute
        lastEmbedding = null
        learner.addNegative(e)
        examples.negatives.add(e)
        store.save(encoder.id, examples)
        publish()
    }

    fun setPack(ids: List<String>) = worker.execute {
        val removed = (store.pack ?: defaultPack) - ids.toSet()
        store.pack = ids
        for (k in removed) {
            learner.forget(k)
            examples.samples.remove(k)
        }
        if (removed.isNotEmpty()) store.save(encoder.id, examples)
        publish()
    }

    /** Forget one phrase's examples, e.g. to teach a different mouthing for it. */
    fun reteach(intent: String) = worker.execute {
        learner.forget(intent)
        examples.samples.remove(intent)
        store.save(encoder.id, examples)
        publish()
    }

    fun wipe() = worker.execute {
        store.wipe()
        learner = Learner()
        examples = Store.Examples(LinkedHashMap(), mutableListOf())
        personalSwitch = null
        gazeModel = null
        gaze = null
        publish()
    }

    fun refresh() = worker.execute { publish() }

    /** Records every frame for [ms] (setup screens: rest face, switch moves, eye looks). */
    suspend fun capture(ms: Long): List<Frame> {
        val out = Collections.synchronizedList(mutableListOf<Frame>())
        val tap: (Frame) -> Unit = { if (it.face) out.add(it) }
        taps.add(tap)
        try {
            delay(ms)
        } finally {
            taps.remove(tap)
        }
        return synchronized(out) { out.toList() }
    }

    fun learnSwitch(rest: List<Frame>, moves: List<List<Frame>>): SwitchTeachResult {
        val names = sensor?.blendNames.orEmpty()
        val r = teachSwitch(rest.mapNotNull { it.blend }, moves.map { m -> m.mapNotNull { it.blend } }, names)
        if (r is SwitchTeachResult.Learned) setSwitch(r.model)
        return r
    }

    fun setSwitch(m: SwitchModel?) {
        store.switchModel = m
        personalSwitch = m?.let { PersonalSwitch(it) }
        refresh()
    }

    fun setGaze(m: GazeModel?) {
        store.gaze = m
        gazeModel = m
        refresh()
    }

    /** Nod, shake and double blink are only armed while Mouna is asking, so mouthing can't trigger them. */
    fun gesturesOn(on: Boolean) {
        if (on && !gestures) analysis.execute { head = HeadGesture(); blink = DoubleBlink() }
        gestures = on
    }

    /** Look-to-choose is only armed while two pictures are on screen. */
    fun gazeOn(on: Boolean) {
        gaze = if (on) gazeModel?.let { GazeSelector(it) } else null
    }

    fun close() {
        analysis.execute { sensor?.close() }
        analysis.shutdown()
        worker.execute { encoder.close(); isl?.close() }
        worker.shutdown()
    }

    companion object {
        private const val TAG = "Mouna"
    }
}
