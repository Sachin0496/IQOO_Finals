package app.mouna.app.engine

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Speech for people whose voice is weak or unclear (Parkinson's, mild dysarthria after a stroke, CP).
 * An existing model, Whisper tiny.en, runs on the phone through sherpa-onnx; nothing is trained. Mouna personalises by
 * remembering what Whisper heard when this person said each of their phrases (see [VoiceMatcher]).
 */
class Hearing(private val context: Context) : AutoCloseable {
    private var recognizer: OfflineRecognizer? = null
    @Volatile var ready = false
        private set
    @Volatile var status = "Voice model not loaded"
        private set

    /** adb push the sherpa-onnx Whisper files here: tiny.en-encoder.int8.onnx, tiny.en-decoder.int8.onnx, tiny.en-tokens.txt */
    fun folder(): File = File(context.getExternalFilesDir(null), "asr").apply { mkdirs() }

    fun load() {
        val dir = folder()
        fun pick(suffix: String) = dir.listFiles().orEmpty().filter { it.name.endsWith(suffix) }
            .sortedBy { if ("int8" in it.name) 0 else 1 }.firstOrNull()
        val enc = pick("encoder.int8.onnx") ?: pick("encoder.onnx")
        val dec = pick("decoder.int8.onnx") ?: pick("decoder.onnx")
        val tokens = pick("tokens.txt")
        if (enc == null || dec == null || tokens == null) {
            status = "No voice model in ${dir.absolutePath}"
            return
        }
        runCatching {
            val cfg = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(encoder = enc.absolutePath, decoder = dec.absolutePath, language = "en", task = "transcribe"),
                    tokens = tokens.absolutePath,
                    numThreads = 4,
                    provider = "cpu",
                    modelType = "whisper",
                ),
            )
            recognizer = OfflineRecognizer(config = cfg)
            ready = true
            status = "Whisper tiny.en · on this phone"
        }.onFailure {
            status = "Voice model failed: ${it.message?.take(120)}"
            Log.e("Mouna", "whisper", it)
        }
    }

    fun transcribe(samples: FloatArray): String {
        val r = recognizer ?: return ""
        val s = r.createStream()
        s.acceptWaveform(samples, RATE)
        r.decode(s)
        val text = r.getResult(s).text.trim()
        s.release()
        return text
    }

    override fun close() {
        recognizer?.release()
        recognizer = null
    }

    companion object {
        const val RATE = 16000
    }
}

/**
 * Hands-free utterance capture: starts when the voice rises above the room, ends after a pause.
 * The threshold adapts to the room, so a quiet (hypophonic) voice still counts if it is clearly above the noise.
 */
class Listener(private val onLevel: (Float) -> Unit, private val onUtterance: (FloatArray) -> Unit) {
    @Volatile private var running = false
    private var worker: Thread? = null

    @SuppressLint("MissingPermission") // checked by the caller before start()
    fun start() {
        if (running) return
        running = true
        worker = thread(name = "mouna-mic") {
            val minBuf = AudioRecord.getMinBufferSize(Hearing.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val rec = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Hearing.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, max(minBuf, FRAME * 8))
            }.getOrNull()
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                running = false
                return@thread
            }
            rec.startRecording()
            val frame = FloatArray(FRAME)
            var noise = 0.003f
            val pre = ArrayDeque<FloatArray>()
            val speech = mutableListOf<FloatArray>()
            var voiced = 0
            var quiet = 0
            var startedAt = 0L
            while (running) {
                val n = rec.read(frame, 0, FRAME, AudioRecord.READ_BLOCKING)
                if (n <= 0) continue
                val chunk = frame.copyOf(n)
                var e = 0f
                for (x in chunk) e += x * x
                val rms = sqrt(e / n)
                onLevel(min(1f, rms / (noise * 12)))
                val loud = rms > max(noise * 3.2f, 0.004f)
                if (speech.isEmpty()) {
                    if (!loud) noise = 0.95f * noise + 0.05f * rms // learn the room only between utterances
                    pre.addLast(chunk)
                    while (pre.size > PRE_FRAMES) pre.removeFirst()
                    voiced = if (loud) voiced + 1 else 0
                    if (voiced >= START_FRAMES) {
                        speech.addAll(pre)
                        pre.clear()
                        quiet = 0
                        startedAt = SystemClock.uptimeMillis()
                    }
                } else {
                    speech.add(chunk)
                    quiet = if (loud) 0 else quiet + 1
                    val long = SystemClock.uptimeMillis() - startedAt > MAX_MS
                    if (quiet >= END_FRAMES || long) {
                        val total = speech.sumOf { it.size }
                        val out = FloatArray(total)
                        var o = 0
                        for (c in speech) { c.copyInto(out, o); o += c.size }
                        speech.clear()
                        voiced = 0
                        if (total > Hearing.RATE * MIN_S) onUtterance(out)
                    }
                }
            }
            rec.stop()
            rec.release()
        }
    }

    fun stop() {
        running = false
        worker?.join(500)
        worker = null
    }

    companion object {
        const val FRAME = 480 // 30 ms
        const val PRE_FRAMES = 10 // 300 ms kept from before the voice started
        const val START_FRAMES = 4 // 120 ms of voice starts an utterance
        const val END_FRAMES = 27 // 800 ms of quiet ends it (slow, effortful speech has long pauses)
        const val MAX_MS = 8000L
        const val MIN_S = 0.35f
    }
}

/**
 * Matches what Whisper heard against this person's phrases: the phrase's own words plus every transcript Whisper
 * produced when the person taught it. Unclear speech is often misheard the same way each time ("eye need wadder"),
 * so their own mishearings are the best template. Word-level similarity, no model training.
 */
object VoiceMatcher {
    data class Match(val id: String, val score: Double)

    fun words(s: String): List<String> = s.lowercase().replace(Regex("[^a-z0-9' ]"), " ").split(" ").filter { it.isNotBlank() && it !in STOP }

    /** Similarity of two word lists, 0..1: soft word overlap with edit distance, so "wadder" still meets "water". */
    fun similarity(a: List<String>, b: List<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        fun best(w: String, other: List<String>) = other.maxOf { 1.0 - lev(w, it).toDouble() / max(w.length, it.length) }
        val ab = a.sumOf { best(it, b) } / a.size
        val ba = b.sumOf { best(it, a) } / b.size
        return (ab + ba) / 2
    }

    fun rank(heard: String, templates: Map<String, List<String>>): List<Match> {
        val h = words(heard)
        return templates.map { (id, texts) -> Match(id, texts.maxOf { similarity(h, words(it)) }) }.sortedByDescending { it.score }
    }

    private fun lev(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]
            d[0] = i
            for (j in 1..b.length) {
                val t = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = t
            }
        }
        return d[b.length]
    }

    // Filler words carry no meaning here; "you" alone must not pull "Can you open the window" onto "I love you".
    private val STOP = setOf(
        "i", "a", "an", "the", "to", "me", "my", "please", "am", "is", "are", "uh", "um", "you", "can", "could", "it",
        "in", "of", "for", "do", "and", "be", "with", "this", "that", "on",
    )

    /** Speak when the best is clearly good and clearly ahead; otherwise show the top choices. */
    const val SPEAK = 0.78
    const val MARGIN = 0.12
    const val SHOW = 0.5
}
