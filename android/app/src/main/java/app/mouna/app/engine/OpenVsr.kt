package app.mouna.app.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import app.mouna.core.Ctc
import app.mouna.core.FreeTalk
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

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
    )

    /** Grey 96 x 96 Auto-AVSR crops ([FreeTalk.cropMatrix]) with their camera timestamps -> sentences. */
    fun read(crops: List<ByteArray>, tMs: LongArray, beam: Int = 16): Reading {
        val (x, valid, n) = FreeTalk.input(crops, tMs)
        val (logp, npuMs) = logits(x, valid)
        val t0 = SystemClock.elapsedRealtimeNanos()
        val hyps = Ctc.prefixBeam(logp, n, UNITS, beam)
        val seen = LinkedHashMap<String, Double>()
        for (h in hyps) {
            val s = Ctc.detokenize(h.ids, tokens)
            if (s.isNotEmpty() && s !in seen) seen[s] = h.logp
        }
        val decodeMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        return Reading(seen.keys.toList(), seen.values.toList(), n, valid.size, npuMs, decodeMs)
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

    override fun close() = sessions.values.forEach { it.close() }

    companion object {
        const val UNITS = 5049
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
            for (t in FreeTalk.BUCKETS) {
                val plain = File(dir, "avsr_vsr_t$t.onnx")
                val ctx = File(dir, "avsr_vsr_t$t.qnn_ctx_fp16.onnx")
                val mark = File(dir, ".avsr_t$t.crashed")
                if (mark.exists()) { notes += "t$t crashed last time: skipped (delete ${mark.name} to retry)"; continue }
                if (!ctx.exists() && !plain.exists()) continue
                mark.writeText("trying")
                log("free talk t$t: ${if (ctx.exists()) "loading the context binary" else "compiling for the NPU (first start, takes minutes)"}…")
                val t0 = SystemClock.elapsedRealtimeNanos()
                val cached = ctx.exists()
                val opened = runCatching {
                    if (cached) env.createSession(ctx.absolutePath, qnn(compileTo = null))
                    else env.createSession(plain.absolutePath, qnn(compileTo = ctx))
                }
                mark.delete()
                val s = opened.getOrNull()
                if (s == null) {
                    if (!cached) ctx.delete()
                    notes += "t$t: ${opened.exceptionOrNull()?.message?.take(200)}"
                    log("free talk t$t failed: ${opened.exceptionOrNull()?.message}")
                    continue
                }
                val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
                log("free talk t$t on the NPU in ${"%.0f".format(ms)} ms (${if (cached) "cached context binary" else "compiled on the phone"})")
                sessions[t] = s
            }
            if (sessions.isEmpty()) return null to (listOf("Free talk: no graph opened on the NPU") + notes).joinToString("\n")
            return OpenVsr(env, sessions, tokens) to "Free talk: NPU · fp16 · buckets ${sessions.keys.joinToString("/")}" +
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
