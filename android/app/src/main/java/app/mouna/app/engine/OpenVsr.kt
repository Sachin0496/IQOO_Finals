package app.mouna.app.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import app.mouna.core.Ctc
import app.mouna.core.FreeTalk
import app.mouna.core.JointBeam
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * Free talk (docs/open-vocab-plan.md): open-vocabulary English from silent mouthing. Auto-AVSR's visual encoder +
 * CTC head (`python -m mouna_encoder avsr-export`), one fixed-length graph per bucket, on the Hexagon NPU only, then
 * CTC prefix beam search on the CPU ([Ctc]). Every sentence it proposes is shown and confirmed before it is spoken.
 *
 * Files, pushed with adb (weights stay out of git): /sdcard/Android/data/app.mouna/files/avsr/
 *   avsr_vsr_t{64,128,256}.onnx   plain fp32 graphs; compiled for the NPU on the phone at first start (fp16) and
 *                                 cached next to them as avsr_vsr_t*.qnn_ctx_fp16.onnx
 *   tokens.txt                    5,049 units, one per line (0 = blank)
 */
class OpenVsr private constructor(
    private val env: OrtEnvironment,
    private val sessions: Map<Int, OrtSession>,
    /** Attention decoder per bucket (avsr_dec_t*.onnx). Without one, that bucket reads with CTC alone (much worse). */
    private val decoders: Map<Int, OrtSession>,
    val tokens: List<String>,
) : AutoCloseable {

    data class Reading(
        /** Best first, distinct sentences. */
        val sentences: List<String>,
        val scores: List<Double>,
        val frames: Int,
        val bucket: Int,
        val npuMs: Double,
        val decodeMs: Double,
        /** Decoder runs on the NPU (one per output token), 0 with CTC alone. */
        val steps: Int = 0,
    )

    /** Grey 96 x 96 Auto-AVSR crops ([FreeTalk.cropMatrix]) with their camera timestamps -> sentences. */
    fun read(crops: List<ByteArray>, tMs: LongArray, beam: Int = 16): Reading {
        val (x, valid, n) = FreeTalk.input(crops, tMs)
        val t = valid.size
        val (logp, enc, npuMs) = encode(x, valid)
        val t0 = SystemClock.elapsedRealtimeNanos()
        val dec = decoders[t]
        var steps = 0
        val hyps: List<Pair<IntArray, Double>> = if (dec != null) {
            JointBeam.search(logp, n, UNITS, SOS, decode = { ps -> steps++; decode(dec, ps, enc, valid) }, maxLen = minOf(DEC_LEN - 1, n))
                .map { it.ids to it.score }
        } else {
            Ctc.prefixBeam(logp, n, UNITS, beam).map { it.ids to it.logp }
        }
        val seen = LinkedHashMap<String, Double>()
        for ((ids, sc) in hyps) {
            val s = Ctc.detokenize(ids, tokens)
            if (s.isNotEmpty() && s !in seen) seen[s] = sc
        }
        val decodeMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        return Reading(seen.keys.toList(), seen.values.toList(), n, t, npuMs, decodeMs, steps)
    }

    /** One encoder run: CTC log-probs (T x 5049), encoder states (T x 768) and the NPU time. */
    private fun encode(x: FloatArray, valid: FloatArray): Triple<FloatArray, FloatArray, Double> {
        val t = valid.size
        val s = checkNotNull(sessions[t]) { "no graph for $t frames" }
        val t0 = SystemClock.elapsedRealtimeNanos()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, t.toLong(), ROI, ROI)).use { xt ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(valid), longArrayOf(1, t.toLong())).use { vt ->
                s.run(mapOf("x" to xt, "valid" to vt)).use { out ->
                    val lp = (out.get("logp").get() as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                    val enc = (out.get("enc").get() as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                    return Triple(lp, enc, (SystemClock.elapsedRealtimeNanos() - t0) / 1e6)
                }
            }
        }
    }

    /** Next-token log-probs for up to [DEC_BATCH] prefixes in one NPU run (rows past the prefixes are padding). */
    private fun decode(dec: OrtSession, prefixes: List<IntArray>, enc: FloatArray, valid: FloatArray): List<FloatArray> {
        val t = valid.size
        val ys = IntArray(DEC_BATCH * DEC_LEN) { SOS }
        val sel = FloatArray(DEC_BATCH * DEC_LEN)
        prefixes.forEachIndexed { b, p ->
            p.copyInto(ys, b * DEC_LEN)
            sel[b * DEC_LEN + p.size - 1] = 1f
        }
        for (b in prefixes.size until DEC_BATCH) sel[b * DEC_LEN] = 1f
        val inputs = mapOf(
            "ys" to OnnxTensor.createTensor(env, IntBuffer.wrap(ys), longArrayOf(DEC_BATCH.toLong(), DEC_LEN.toLong())),
            "memory" to OnnxTensor.createTensor(env, FloatBuffer.wrap(enc), longArrayOf(1, t.toLong(), 768)),
            "valid" to OnnxTensor.createTensor(env, FloatBuffer.wrap(valid), longArrayOf(1, t.toLong())),
            "sel" to OnnxTensor.createTensor(env, FloatBuffer.wrap(sel), longArrayOf(DEC_BATCH.toLong(), DEC_LEN.toLong())),
        )
        try {
            dec.run(inputs).use { out ->
                val b = (out.get(0) as OnnxTensor).floatBuffer
                val all = FloatArray(b.remaining()).also { b.get(it) }
                return List(prefixes.size) { all.copyOfRange(it * UNITS, (it + 1) * UNITS) }
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    /** One NPU run: (1, T, 88, 88) + (1, T) mask -> (T x 5049) CTC log-probs, and the time it took. */
    fun logits(x: FloatArray, valid: FloatArray): Pair<FloatArray, Double> {
        val t = valid.size
        val s = checkNotNull(sessions[t]) { "no graph for $t frames" }
        val t0 = SystemClock.elapsedRealtimeNanos()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, t.toLong(), ROI, ROI)).use { xt ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(valid), longArrayOf(1, t.toLong())).use { vt ->
                s.run(mapOf("x" to xt, "valid" to vt), setOf("logp")).use { out ->
                    val b = (out.get(0) as OnnxTensor).floatBuffer
                    val y = FloatArray(b.remaining()).also { b.get(it) }
                    return y to (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
                }
            }
        }
    }

    override fun close() = (sessions.values + decoders.values).forEach { it.close() }

    companion object {
        const val UNITS = 5049
        const val SOS = UNITS - 1
        const val DEC_BATCH = 8
        const val DEC_LEN = 48
        private const val ROI = FreeTalk.ROI.toLong()

        fun folder(context: Context): File = File(context.getExternalFilesDir(null), "avsr").apply { mkdirs() }

        /**
         * Opens every bucket on the NPU (compiling and caching the context binary the first time, which takes a while).
         * NPU only: no CPU fallback. Returns the reader, or null and why.
         */
        fun open(context: Context, log: (String) -> Unit): Pair<OpenVsr?, String> {
            val dir = folder(context)
            val tokFile = File(dir, "tokens.txt")
            if (!tokFile.exists()) return null to "Free talk: no model in ${dir.absolutePath}"
            val tokens = tokFile.readLines()
            if (tokens.size != UNITS) return null to "Free talk: tokens.txt has ${tokens.size} units, expected $UNITS"
            val env = OrtEnvironment.getEnvironment()
            val sessions = LinkedHashMap<Int, OrtSession>()
            val notes = mutableListOf<String>()
            val decoders = LinkedHashMap<Int, OrtSession>()
            for (t in FreeTalk.BUCKETS) {
                for ((kind, into) in listOf("vsr" to sessions, "dec" to decoders)) {
                    val name = "avsr_${kind}_t$t"
                    val plain = File(dir, "$name.onnx")
                    val ctx = File(dir, "$name.qnn_ctx_fp16.onnx")
                    val mark = File(dir, ".$name.crashed")
                    if (mark.exists()) { notes += "$name crashed last time: skipped (delete ${mark.name} to retry)"; continue }
                    if (!ctx.exists() && !plain.exists()) continue
                    if (kind == "dec" && t !in sessions) continue
                    mark.writeText("trying")
                    val cached = ctx.exists()
                    log("free talk $name: ${if (cached) "loading the context binary" else "compiling for the NPU (first start, takes minutes)"}…")
                    val t0 = SystemClock.elapsedRealtimeNanos()
                    val opened = runCatching {
                        if (cached) env.createSession(ctx.absolutePath, qnn(compileTo = null))
                        else env.createSession(plain.absolutePath, qnn(compileTo = ctx))
                    }
                    mark.delete()
                    val s = opened.getOrNull()
                    if (s == null) {
                        if (!cached) ctx.delete()
                        notes += "$name: ${opened.exceptionOrNull()?.message?.take(200)}"
                        log("free talk $name failed: ${opened.exceptionOrNull()?.message}")
                        continue
                    }
                    val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
                    log("free talk $name on the NPU in ${"%.0f".format(ms)} ms (${if (cached) "cached context binary" else "compiled on the phone"})")
                    into[t] = s
                }
            }
            if (sessions.isEmpty()) return null to (listOf("Free talk: no graph opened on the NPU") + notes).joinToString("\n")
            return OpenVsr(env, sessions, decoders, tokens) to "Free talk: NPU · fp16 · buckets ${sessions.keys.joinToString("/")}" +
                " · decoder ${if (decoders.isEmpty()) "none (CTC only)" else decoders.keys.joinToString("/")}" +
                if (notes.isEmpty()) "" else "\n" + notes.joinToString("\n")
        }

        private fun qnn(compileTo: File?): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
            val o = mutableMapOf(
                "backend_path" to "libQnnHtp.so",
                "htp_performance_mode" to "burst",
                "htp_graph_finalization_optimization_mode" to "3",
                "enable_htp_fp16_precision" to "1",
            )
            if (compileTo != null) {
                addConfigEntry("ep.context_enable", "1")
                addConfigEntry("ep.context_embed_mode", "1")
                addConfigEntry("ep.context_file_path", compileTo.absolutePath)
            }
            addQnn(o)
            addConfigEntry("session.disable_cpu_ep_fallback", "1") // all on the NPU, or fail loudly
        }

        /** Little-endian float32 file (QA self-test files written by the Python export). */
        fun readFloats(f: File): FloatArray {
            val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(b.remaining()).also { b.get(it) }
        }
    }
}
