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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** What the lips are used for right now. */
enum class Listen { SPEAK, TEACH, NEGATIVE, PAUSED }

/** The camera analysis (face model) coming up: the UI shows the no-camera screen if it never does. */
enum class SensorState { STARTING, READY, FAILED }

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
    /** Free talk (open-vocabulary English, NPU): status line, and whether it is ready to read. */
    val freeTalk: String = "Free talk · Loading…",
    val freeReady: Boolean = false,
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
    /** Free talk read a sentence: candidates best first (empty: nothing readable). [ms]: end of mouthing to sentences. */
    data class Read(val sentences: List<String>, val ms: Double, val npuMs: Double, val options: List<app.mouna.core.Personal.Option> = emptyList()) : Event
    /** Recording for training (issue #5 B): the clip for [text] was saved; [read] is what free talk made of it. */
    data class Recorded(val text: String, val frames: Int, val read: String?) : Event
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
    /** Free talk owns its own thread: its first NPU compile takes minutes and must not hold up the lip encoder. */
    private val freeWorker = Executors.newSingleThreadExecutor()
    @Volatile private var openVsr: OpenVsr? = null
    @Volatile var freeTalkStatus = "Free talk · Loading…"
        private set
    @Volatile private var freeOn = false
    private var perfN = 0
    private var perfDt = 0L
    private var perfLm = 0f
    private var perfAn = 0f
    /**
     * Sentences, not phrases: up to 10 s; pauses between words (under ~0.7 s at the ~20 fps the front camera gives)
     * don't end the utterance; under ~1 s is a twitch, not a sentence (measured: those read as "THE", "THAT").
     */
    private val freeSegmenter = Segmenter(preRoll = 6, minFrames = 22, maxFrames = 300, tail = 14, keepTail = 6)

    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live.asStateFlow()
    private val _knowledge = MutableStateFlow(Knowledge())
    val knowledge: StateFlow<Knowledge> = _knowledge.asStateFlow()
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 32)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    @Volatile var sensor: Sensor? = null
        private set
    private val _sensorState = MutableStateFlow(SensorState.STARTING)
    val sensorState: StateFlow<SensorState> = _sensorState.asStateFlow()
    private val sensorBuilt = CompletableDeferred<Sensor?>()
    private var startedAt = 0L
    private var firstFrameSeen = false
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
    /** The switch setup (or anything else that captures frames) has run: the landmarker keeps its blendshape output. */
    @Volatile private var setupSeen = false
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

    /** The sensor once it is built, or null if the face model could not start at all (no camera screen, not a hang). */
    suspend fun awaitSensor(): Sensor? = sensorBuilt.await()

    /**
     * Brings the engine up. Order matters on stage: the camera first (the person sees themselves at once), then the lip
     * encoder (it gates "ready", and a first NPU compile can take minutes), and only then the ISL model; Whisper waits
     * for the encoder too (see [WhisperGate]), so nothing competes with it for the CPU.
     */
    fun start() {
        startedAt = SystemClock.elapsedRealtime()
        onAnalysis {
            val t0 = SystemClock.elapsedRealtime()
            val s = runCatching { Sensor(context, ::onFrame, blendshapes = personalSwitch != null) }.onFailure { Log.e(TAG, "sensor", it) }.getOrNull()
            if (s != null) {
                syncSensor(s) // flags set before the sensor existed (the persisted Sign channel, a taught switch) reach it now
                sensor = s
                syncSensor(s) // and any set in between
                Log.i(PERF, "sensor ready in ${SystemClock.elapsedRealtime() - t0} ms (${s.delegate})")
            }
            _sensorState.value = if (s != null) SensorState.READY else SensorState.FAILED
            sensorBuilt.complete(s)
        }
        onWorker {
            val t0 = SystemClock.elapsedRealtime()
            val (ort, report) = try {
                OrtEncoder.open(context) { Log.w(TAG, it) }
            } finally {
                WhisperGate.open() // whatever happened, Whisper must not wait on a dead start
            }
            encoder = ort ?: ShapeEncoder()
            examples = store.load(encoder.id)
            learner = Learner()
            for ((k, xs) in examples.samples) for (x in xs) learner.addSample(k, x)
            for (x in examples.negatives) learner.addNegative(x)
            ready = true
            publish(report)
            Log.i(PERF, "encoder ready in ${SystemClock.elapsedRealtime() - t0} ms (${encoder.label})")
            // Free talk after the lip encoder (start-up order above), on its own thread: a first NPU compile takes minutes.
            submit(freeWorker) {
                val (r, msg) = runCatching { OpenVsr.open(context) { ftLog(it) } }
                    .getOrElse { null to "Free talk: ${it.message}" }
                openVsr = r
                freeTalkStatus = msg
                ftLog(msg)
                _knowledge.value = _knowledge.value.copy(freeTalk = msg.lineSequence().first(), freeReady = r != null)
            }

            val t1 = SystemClock.elapsedRealtime()
            isl = runCatching { Isl.open(context) }.getOrNull()
            islKnown = true
            publish()
            Log.i(PERF, "isl ${if (isl != null) "ready" else "absent"} in ${SystemClock.elapsedRealtime() - t1} ms")
        }
    }

    /** The executors outlive nothing: a call that races [close] is dropped, not a crash. */
    private fun submit(ex: Executor, block: () -> Unit) {
        try {
            ex.execute(block)
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "engine closed: dropped a task")
        }
    }
    private fun onWorker(block: () -> Unit) = submit(worker, block)
    private fun onAnalysis(block: () -> Unit) = submit(analysis, block)

    /** Tells the sensor what is consumed right now, so it does not produce what nobody reads (see [Sensor.wantCrop]). */
    private fun syncSensor(s: Sensor) {
        val crop = ready && listen != Listen.PAUSED
        if (s.wantCrop != crop) s.wantCrop = crop
        val blend = personalSwitch != null || setupSeen
        if (s.wantBlend != blend) s.wantBlend = blend
        if (s.signing != signMode) s.signing = signMode
        if (s.wantAvsr != freeOn) s.wantAvsr = freeOn
    }

    // ---------------- analysis thread ----------------

    private fun onFrame(f: Frame) {
        sensor?.let { syncSensor(it) }
        if (!firstFrameSeen) {
            firstFrameSeen = true
            Log.i(PERF, "first frame ${SystemClock.elapsedRealtime() - startedAt} ms after engine start")
        }
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

        if (freeOn && f.face) {
            perfN++; perfDt += dt; perfLm += f.landmarkMs; perfAn += f.analyzeMs
            if (perfN == 150) {
                ftLog("camera: ${"%.1f".format(1000.0 * perfN / perfDt)} fps, face model ${"%.1f".format(perfLm / perfN)} ms, " +
                    "analyze ${"%.1f".format(perfAn / perfN)} ms per frame (${sensor?.delegate})")
                perfN = 0; perfDt = 0; perfLm = 0f; perfAn = 0f
            }
        }
        if (freeOn && openVsr != null) {
            freeSegmenter.push(f)?.let { c -> submit(freeWorker) { readClip(c) } }
        } else {
            freeSegmenter.reset()
        }
        val mode = listen
        var clip: Clip? = null
        if (ready && mode != Listen.PAUSED) clip = segmenter.push(f) else segmenter.reset()
        if (clip != null) {
            val intent = teaching
            onWorker { handle(clip, mode, intent) }
        }
        val prev = _live.value
        _live.value = Live(
            face = f.face,
            hearing = segmenter.recording || freeSegmenter.recording,
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
                onWorker {
                    val model = isl ?: return@onWorker
                    val g = runCatching { model.classify(frames) }.onFailure { Log.e(TAG, "isl", it) }.getOrNull() ?: return@onWorker
                    _events.tryEmit(Event.Signed(g, (SystemClock.uptimeMillis() - endMs).toDouble()))
                }
            }
        }
    }

    /** QA: classify recorded keypoints (frames of 27 x 2) and log the top words. */
    fun classifySign(frames: List<FloatArray>) = onWorker {
        val g = isl?.classify(frames)
        Log.i(TAG, "isl check -> " + (g?.joinToString { "${it.word} %.4f".format(it.p) } ?: "no model"))
    }

    /** Sign mode: the camera runs body + hands for ISL instead of the face. */
    fun signing(on: Boolean) {
        if (on == signMode) return
        signMode = on
        sensor?.let { syncSensor(it) } // not built yet: start() applies it
        onAnalysis { signFrames = null; handsUp = 0 }
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
            freeTalk = freeTalkStatus.lineSequence().first(),
            freeReady = openVsr != null,
        )
    }

    // ---------------- UI calls ----------------

    fun listen(mode: Listen, intent: String? = null) = onWorker {
        teaching = if (mode == Listen.TEACH) intent else null
        listen = mode
        onAnalysis { segmenter.reset() }
        publish()
    }

    /** The person picked [intent] after Mouna wasn't sure: learn from that mouthing (the Lab's tap-to-correct). */
    fun picked(intent: String) = onWorker {
        val e = lastEmbedding ?: return@onWorker
        lastEmbedding = null
        learner.teach(intent, e)
        examples.samples.getOrPut(intent) { mutableListOf() }.add(e)
        store.save(encoder.id, examples)
        publish()
    }

    /** "None of these": that mouthing becomes a negative example (core.md: add every "none of these"). */
    fun noneOfThese() = onWorker {
        val e = lastEmbedding ?: return@onWorker
        lastEmbedding = null
        learner.addNegative(e)
        examples.negatives.add(e)
        store.save(encoder.id, examples)
        publish()
    }

    fun setPack(ids: List<String>) = onWorker {
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
    fun reteach(intent: String) = onWorker {
        learner.forget(intent)
        examples.samples.remove(intent)
        store.save(encoder.id, examples)
        publish()
    }

    fun wipe() = onWorker {
        store.wipe()
        learner = Learner()
        examples = Store.Examples(LinkedHashMap(), mutableListOf())
        personalSwitch = null
        gazeModel = null
        gaze = null
        publish()
    }

    fun refresh() = onWorker { publish() }

    /** Records every frame for [ms] (setup screens: rest face, switch moves, eye looks). */
    suspend fun capture(ms: Long): List<Frame> {
        setupSeen = true // setup wants every output the face model has, blendshapes included
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
        if (on && !gestures) onAnalysis { head = HeadGesture(); blink = DoubleBlink() }
        gestures = on
    }

    /** Look-to-choose is only armed while two pictures are on screen. */
    fun gazeOn(on: Boolean) {
        gaze = if (on) gazeModel?.let { GazeSelector(it) } else null
    }

    /**
     * Free talk's log: logcat, and avsr/log.txt (vivo filters third-party logcat; `adb shell cat` reads the file).
     */
    private fun ftLog(msg: String, e: Throwable? = null) {
        Log.i(TAG, msg, e)
        runCatching {
            val line = "${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())} $msg" +
                (e?.let { " :: ${it.javaClass.simpleName}: ${it.message}" } ?: "") + "\n"
            java.io.File(OpenVsr.folder(context), "log.txt").appendText(line)
        }
    }

    /**
     * The person's own sentences for free talk (issue #5 A): what they confirmed before (newest first), then their
     * phrases and the family's words in English. Capped so scoring stays within a few NPU runs.
     */
    private fun personalSentences(): List<String> =
        (store.freeTalkSentences.asReversed() + phrases.phrases.map { it.say(Lang.EN) })
            .map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(MAX_PERSONAL)

    /** A confirmed free-talk sentence: offered again next time by the model's own score. */
    fun rememberSentence(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        store.freeTalkSentences = (store.freeTalkSentences.filter { !it.equals(t, ignoreCase = true) } + t).takeLast(MAX_LEARNED)
    }

    /** Free talk listens (Speak screen, Free talk channel, no prompt open). */
    fun freeTalk(on: Boolean) {
        freeOn = on
    }

    /** QA (debug builds, adb broadcast): keep each free-talk clip's mouth crops in avsr/clips/ to replay on the laptop. */
    @Volatile var saveClips = false

    /** The prompted sentences for recording (res/raw/freetalk_prompts.txt). */
    fun recordPrompts(): List<String> =
        context.resources.openRawResource(app.mouna.R.raw.freetalk_prompts).bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }

    /** Where recordings go: avsr/train/<session>/NNN.{bin,t.txt,txt} (adb pull for the laptop fine-tune). */
    fun trainFolder(): java.io.File = java.io.File(OpenVsr.folder(context), "train")

    /** Recording for training (issue #5 B): the next free-talk clip is saved as [file] (.bin crops, .t.txt ms, .txt text). */
    @Volatile private var recordTo: Pair<java.io.File, String>? = null

    fun recordNext(file: java.io.File?, text: String?) {
        recordTo = if (file != null && text != null) file to text else null
    }

    private fun readClip(c: Clip) {
        val r = openVsr ?: return
        val endMs = c.frames.last().tMs
        runCatching {
            val (crops, t) = c.avsrCrops()
            recordTo?.let { (f, text) ->
                recordTo = null
                f.parentFile?.mkdirs()
                java.io.File(f.path + ".bin").outputStream().use { o -> crops.forEach { o.write(it) } }
                java.io.File(f.path + ".t.txt").writeText(t.joinToString("\n") { (it - t[0]).toString() })
                java.io.File(f.path + ".txt").writeText(text)
                val read = runCatching { r.read(crops, t).sentences.firstOrNull() }.getOrNull()
                ftLog("free talk: recorded ${f.name} (${crops.size} frames) \"$text\" -> ${read ?: "-"}")
                _events.tryEmit(Event.Recorded(text, crops.size, read))
                return@runCatching
            }
            if (saveClips) runCatching {
                val dir = java.io.File(OpenVsr.folder(context), "clips").apply { mkdirs() }
                val name = java.text.SimpleDateFormat("HHmmss", java.util.Locale.US).format(java.util.Date())
                java.io.File(dir, "$name.bin").outputStream().use { o -> crops.forEach { o.write(it) } }
                java.io.File(dir, "$name.t.txt").writeText(t.joinToString("\n") { (it - t[0]).toString() })
                ftLog("free talk: saved clip $name (${crops.size} frames)")
            }
            val res = r.read(crops, t, personal = personalSentences())
            val ms = (SystemClock.uptimeMillis() - endMs).toDouble()
            ftLog("free talk: ${res.frames} frames, NPU ${"%.0f".format(res.npuMs)} ms, decode ${"%.0f".format(res.decodeMs)} ms (${res.steps} steps), " +
                "yours ${"%.0f".format(res.personalMs)} ms, total ${"%.0f".format(ms)} ms -> " +
                res.options.joinToString(" | ") { (if (it.personal) "*" else "") + it.text + " %.1f".format(it.score) })
            if (freeOn) _events.tryEmit(Event.Read(res.sentences, ms, res.npuMs, res.options))
        }.onFailure { ftLog("free talk", it) }
    }

    /**
     * QA: run each bucket on the pushed self-test input (avsr/selftest_t{T}_x.bin, _valid.bin) and compare with the
     * laptop's CTC log-probs (_logp.bin): max difference over the real frames, mean NPU time over 5 runs.
     */
    fun freeTalkSelfTest() = freeWorker.execute {
        val r = openVsr ?: run { ftLog("free talk self-test: $freeTalkStatus"); return@execute }
        val dir = OpenVsr.folder(context)
        for (t in app.mouna.core.FreeTalk.BUCKETS) {
            val fx = java.io.File(dir, "selftest_t${t}_x.bin")
            if (!fx.exists()) continue
            runCatching {
                val x = OpenVsr.readFloats(fx)
                val valid = OpenVsr.readFloats(java.io.File(dir, "selftest_t${t}_valid.bin"))
                val want = OpenVsr.readFloats(java.io.File(dir, "selftest_t${t}_logp.bin"))
                r.logits(x, valid) // warm-up
                val runs = List(5) { r.logits(x, valid) }
                val got = runs.last().first
                val n = valid.count { it > 0f }
                var diff = 0f
                var agree = 0
                for (i in 0 until n) {
                    var bg = 0; var bw = 0
                    for (k in 0 until OpenVsr.UNITS) {
                        val o = i * OpenVsr.UNITS + k
                        diff = maxOf(diff, kotlin.math.abs(got[o] - want[o]))
                        if (got[o] > got[i * OpenVsr.UNITS + bg]) bg = k
                        if (want[o] > want[i * OpenVsr.UNITS + bw]) bw = k
                    }
                    if (bg == bw) agree++
                }
                ftLog("free talk self-test t$t: max |logp diff| ${"%.4f".format(diff)}, argmax agrees $agree/$n frames, " +
                    "NPU ${"%.1f".format(runs.map { it.second }.average())} ms")
            }.onFailure { ftLog("free talk self-test t$t", it) }
        }
    }

    /** QA: read a clip of Auto-AVSR crops (uint8, frames x 96 x 96) recorded at [fps]; the sentences go to logcat. */
    fun freeTalkRead(file: java.io.File, fps: Double) = freeWorker.execute {
        val r = openVsr ?: run { ftLog("free talk read: $freeTalkStatus"); return@execute }
        runCatching {
            val bytes = file.readBytes()
            val n = bytes.size / (96 * 96)
            val crops = List(n) { bytes.copyOfRange(it * 96 * 96, (it + 1) * 96 * 96) }
            val t = LongArray(n) { (it * 1000.0 / fps).toLong() }
            r.dump = java.io.File(file.parentFile, "dump_" + file.nameWithoutExtension)
            val res = try { r.read(crops, t, personal = personalSentences()) } finally { r.dump = null }
            ftLog("free talk read ${file.name}: ${res.frames} frames (bucket ${res.bucket}), NPU ${"%.1f".format(res.npuMs)} ms, " +
                "decode ${"%.1f".format(res.decodeMs)} ms (${res.steps} steps)")
            res.options.forEach { o -> ftLog("  option ${if (o.personal) "(yours) " else ""}${o.text} (${"%.2f".format(o.score)})") }
            res.sentences.take(5).forEachIndexed { i, s -> ftLog("  ${i + 1}. $s  (${"%.2f".format(res.scores[i])})") }
        }.onFailure { ftLog("free talk read", it) }
    }

    fun close() {
        submit(freeWorker) { openVsr?.close() }
        freeWorker.shutdown()
        onAnalysis { sensor?.close() }
        analysis.shutdown()
        onWorker { encoder.close(); isl?.close() }
        worker.shutdown()
    }

    companion object {
        private const val TAG = "Mouna"
        private const val PERF = "MounaPerf"
        private const val MAX_PERSONAL = 64 // 8 scorer runs on the NPU
        private const val MAX_LEARNED = 200
    }
}
