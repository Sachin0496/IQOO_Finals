package app.mouna.app.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import android.os.SystemClock
import app.mouna.app.sense.Sensor
import app.mouna.core.EncoderInput
import app.mouna.core.SelfTest
import app.mouna.core.cosine
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Turns one utterance into a fixed-size embedding for the few-shot Learner. */
interface LipEncoder : AutoCloseable {
    /** Stable id: embeddings from different encoders are not comparable, so each has its own saved samples. */
    val id: String
    /** What the status pill shows, e.g. "NPU · fp16". */
    val label: String
    val onNpu: Boolean
    fun embed(clip: Clip): FloatArray
    override fun close() {}
}

/** How the encoder came up at start: shown in Settings, so a CPU fallback is never silent. */
data class EncoderReport(
    val label: String,
    val detail: String,
    val selfTestCosine: Double? = null,
    val warmMs: Double? = null,
)

/**
 * The lip encoder (encoder_static48: (48, 5, 88, 88) -> 500-d) on ONNX Runtime. On the NPU it is a QNN context
 * binary precompiled per chip, wrapped in an ONNX file (AI Hub precompiled_qnn_onnx); on the CPU, the plain ONNX.
 */
class OrtEncoder private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    override val id: String,
    override val label: String,
    override val onNpu: Boolean,
) : LipEncoder {
    private val inputName = session.inputNames.first()
    private val shape = EncoderInput.SHAPE.map { it.toLong() }.toLongArray()
    // One direct buffer for every utterance: a heap FloatBuffer makes ONNX Runtime allocate and copy a fresh 7.4 MB native
    // buffer each time. Only the worker thread runs the encoder, so one is enough.
    private val inputBuf: FloatBuffer = ByteBuffer.allocateDirect(EncoderInput.SHAPE.fold(1) { a, b -> a * b } * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    override fun embed(clip: Clip): FloatArray {
        val (crops, t) = clip.crops()
        return run(EncoderInput.build(crops, clip.size, Sensor.CROP, t))
    }

    fun run(input: FloatArray): FloatArray {
        inputBuf.clear()
        inputBuf.put(input)
        inputBuf.rewind()
        OnnxTensor.createTensor(env, inputBuf, shape).use { x ->
            session.run(mapOf(inputName to x)).use { out ->
                val y = out.get(0) as OnnxTensor
                val b = y.floatBuffer
                return FloatArray(b.remaining()).also { b.get(it) }
            }
        }
    }

    override fun close() = session.close()

    companion object {
        private fun snapdragon(): Boolean =
            (Build.VERSION.SDK_INT >= 31 && Build.SOC_MANUFACTURER.contains("QTI", ignoreCase = true)) ||
                Build.HARDWARE.contains("qcom", ignoreCase = true)

        /** adb push the model files here: /sdcard/Android/data/app.mouna/files/encoder/ (gitignored weights). */
        fun folder(context: Context): File = File(context.getExternalFilesDir(null), "encoder").apply { mkdirs() }

        /**
         * Tries a QNN context binary on the NPU, then compiles the plain ONNX for the NPU on the phone, then the plain
         * ONNX on the CPU. Each must pass the start-up
         * self-test (a real clip, cosine >= 0.99 against the PyTorch embedding) or it is not used.
         */
        fun open(context: Context, log: (String) -> Unit): Pair<OrtEncoder?, EncoderReport> {
            val dir = folder(context)
            discardCutShortCompiles(context, dir)
            val files = dir.listFiles().orEmpty().filter { it.name.endsWith(".onnx") }
            if (files.isEmpty()) {
                return null to EncoderReport("Landmarks", "No encoder in ${dir.absolutePath}. Using lip landmarks until it is pushed.")
            }
            val env = OrtEnvironment.getEnvironment()
            val test = runCatching { SelfTest.load() }.getOrNull()
            val notes = mutableListOf<String>()
            val npu = files.filter { "ctx" in it.name || "qnn" in it.name }.sortedBy { if ("fp16" in it.name) 0 else 1 }
            val cpu = files - npu.toSet()
            for (f in npu) {
                val int8 = "int8" in f.name
                val opened = guarded(context, f, "NPU") { attempt(env, f, qnn = true, test, int8, log) }
                if (opened.first != null) return opened.first to opened.second
                notes += opened.second.detail
            }
            // No precompiled binary that works: compile the plain ONNX for the Hexagon NPU on the phone itself (fp16),
            // saving the context binary next to it so the next start loads in seconds. Snapdragon only.
            if (snapdragon()) for (f in cpu) {
                val ctx = File(dir, "${f.nameWithoutExtension}.qnn_ctx_fp16.onnx")
                if (ctx.exists()) continue
                val opened = guarded(context, f, "NPU-compile") { attempt(env, f, qnn = true, test, int8 = false, log, compileTo = ctx) }
                if (opened.first != null) return opened.first to opened.second.copy(detail = (notes + opened.second.detail).joinToString("\n"))
                notes += opened.second.detail
                ctx.delete()
            }
            for (f in cpu) {
                val opened = guarded(context, f, "CPU") { attempt(env, f, qnn = false, test, int8 = false, log) }
                if (opened.first != null) {
                    return opened.first to opened.second.copy(detail = (notes + opened.second.detail).joinToString("\n"))
                }
                notes += opened.second.detail
            }
            return null to EncoderReport("Landmarks", (notes + "Falling back to lip landmarks.").joinToString("\n"))
        }

        /**
         * A model that crashes natively takes the app down with it. Mark each attempt on disk first; if the app dies
         * during it and Android says it was a real crash, the mark is still there next start and that model (or mode) is
         * skipped instead of crash-looping (see [CrashMarks]).
         */
        private fun guarded(context: Context, f: File, mode: String, run: () -> Pair<OrtEncoder?, EncoderReport>): Pair<OrtEncoder?, EncoderReport> {
            val mark = markFile(f, mode)
            if (CrashMarks.blocks(context, mark)) return null to EncoderReport(mode, "${f.name} on $mode crashed last time: skipped (delete ${mark.name} to retry)")
            CrashMarks.begin(mark)
            return run().also { mark.delete() }
        }

        private fun markFile(f: File, mode: String) = File(f.parentFile, ".${f.name}.$mode.crashed")

        /**
         * The on-phone NPU compile writes its context binary at the path it was given. If the app was killed during the
         * compile (it takes minutes the first time), that file is half-written but exists, and the "already compiled"
         * check would keep the encoder on the CPU forever. A compile mark that no longer counts as a crash means the
         * file is junk: delete both so the next start compiles again.
         */
        private fun discardCutShortCompiles(context: Context, dir: File) {
            val suffix = ".NPU-compile.crashed"
            for (mark in dir.listFiles { x -> x.name.startsWith(".") && x.name.endsWith(suffix) }.orEmpty()) {
                if (CrashMarks.blocks(context, mark)) continue // a real crash: leave everything, the guard skips the compile
                val onnx = mark.name.removePrefix(".").removeSuffix(suffix).removeSuffix(".onnx")
                File(dir, "$onnx.qnn_ctx_fp16.onnx").delete()
            }
        }

        private fun attempt(
            env: OrtEnvironment,
            f: File,
            qnn: Boolean,
            test: SelfTest?,
            int8: Boolean,
            log: (String) -> Unit,
            compileTo: File? = null,
        ): Pair<OrtEncoder?, EncoderReport> {
            val where = if (qnn) "NPU" else "CPU"
            val session = runCatching {
                // The options are only needed to create the session; closing them frees their native memory.
                (if (qnn) OrtSession.SessionOptions() else cpuOptions(4)).use { opts ->
                    if (qnn) {
                        val qnnOptions = mutableMapOf(
                            "backend_path" to "libQnnHtp.so",
                            "htp_performance_mode" to "burst",
                            "htp_graph_finalization_optimization_mode" to "3",
                        )
                        if (compileTo != null) {
                            qnnOptions["enable_htp_fp16_precision"] = "1" // fp32 graph run in fp16 on HTP V73+ (SM8650, SM8850)
                            opts.addConfigEntry("ep.context_enable", "1")
                            opts.addConfigEntry("ep.context_embed_mode", "1")
                            opts.addConfigEntry("ep.context_file_path", compileTo.absolutePath)
                        }
                        opts.addQnn(qnnOptions)
                        opts.addConfigEntry("session.disable_cpu_ep_fallback", "1") // all on the NPU, or fail loudly
                    }
                    env.createSession(f.absolutePath, opts)
                }
            }.getOrElse {
                log("encoder: ${f.name} on $where failed: ${it.message}")
                return null to EncoderReport(where, "${f.name} on $where: ${it.message?.take(160)}")
            }
            val precision = if (int8) "int8" else if ("fp16" in f.name || compileTo != null) "fp16" else "fp32"
            val enc = OrtEncoder(env, session, "ort-${f.nameWithoutExtension}", "$where · $precision", qnn)
            if (test == null) return enc to EncoderReport(enc.label, "${f.name}: self-test clip missing, not checked")
            return runCatching {
                enc.run(test.input) // warm-up: the first QNN run finalises the graph
                val t0 = SystemClock.elapsedRealtimeNanos()
                val y = enc.run(test.input)
                val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
                val cos = cosine(y, if (int8) test.expectedInt8 else test.expected)
                if (test.passes(y, int8)) {
                    enc to EncoderReport(enc.label, "${f.name}: self-test passed", cos, ms)
                } else {
                    enc.close()
                    null to EncoderReport(where, "${f.name} on $where: self-test failed (cosine ${"%.4f".format(cos)})", cos, ms)
                }
            }.getOrElse {
                enc.close()
                null to EncoderReport(where, "${f.name} on $where: ${it.message?.take(160)}")
            }
        }
    }
}

/**
 * Fallback until the encoder weights are on the phone: the lip-shape trajectory itself. Each frame's 40 aligned lip
 * points minus the clip's mean shape (so it is the movement, not the face), resampled to 12 steps. Weaker than the
 * encoder; the app says so on screen. Lets Teach / Speak / choices run end to end on any phone today.
 */
class ShapeEncoder : LipEncoder {
    override val id = "shape12"
    override val label = "Landmarks"
    override val onNpu = false

    override fun embed(clip: Clip): FloatArray {
        val xs = clip.frames.map { it.features!! }
        val ap = clip.frames.map { it.aperture }
        val d = xs[0].size
        val mean = FloatArray(d)
        for (x in xs) for (i in 0 until d) mean[i] += x[i] / xs.size
        val apMean = ap.average().toFloat()
        val out = FloatArray(STEPS * (d + 1))
        for (s in 0 until STEPS) {
            val pos = s * (xs.size - 1f) / (STEPS - 1)
            val a = pos.toInt()
            val b = minOf(a + 1, xs.size - 1)
            val w = pos - a
            for (i in 0 until d) out[s * (d + 1) + i] = (1 - w) * (xs[a][i] - mean[i]) + w * (xs[b][i] - mean[i])
            out[s * (d + 1) + d] = APERTURE_WEIGHT * ((1 - w) * ap[a] + w * ap[b] - apMean)
        }
        return out
    }

    companion object {
        const val STEPS = 12
        const val APERTURE_WEIGHT = 3f
    }
}
