package app.mouna.app.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import app.mouna.core.Ctc
import app.mouna.core.FreeTalk
import app.mouna.core.JointBeam
import app.mouna.core.Personal
import app.mouna.core.Spm
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
    /** Attention decoder per bucket (avsr_dec_t*.onnx). Without one, that bucket reads with CTC alone (much worse). */
    private val decoders: Map<Int, OrtSession>,
    val tokens: List<String>,
    /** Decoder lookup tables (dec_embed.bin: 5049 x 768 embeddings * sqrt(768); dec_pos.bin: 48 x 768 positions). */
    private val embed: FloatArray,
    private val pos: FloatArray,
    /** Whole-sentence scorer per bucket (avsr_score_t*.onnx) and its output layer (dec_out_w.bin, dec_out_b.bin). */
    private val scorers: Map<Int, OrtSession> = emptyMap(),
    private val outW: FloatArray = FloatArray(0),
    private val outB: FloatArray = FloatArray(0),
    private val spm: Spm? = null,
    /** Which model this is (Settings > Free talk model). */
    val model: Model? = null,
) : AutoCloseable {
    private val idsCache = HashMap<String, IntArray>()
    /** No-video attention score per sentence ([Personal.rank]): it depends only on the text, so it is computed once. */
    private val priorCache = HashMap<String, Double>()

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
        /** What "Did you mean…?" shows: open readings and the person's own sentences, merged ([Personal.merge]). */
        val options: List<Personal.Option> = emptyList(),
        val personalMs: Double = 0.0,
    )

    /** Free talk can score the person's own sentences (scorer graphs and tables are on the phone). */
    val canScore get() = spm != null && scorers.isNotEmpty()

    /** Grey 96 x 96 Auto-AVSR crops ([FreeTalk.cropMatrix]) with their camera timestamps -> sentences. */
    fun read(crops: List<ByteArray>, tMs: LongArray, beam: Int = 16, personal: List<String> = emptyList()): Reading {
        val (x, valid, n) = FreeTalk.input(crops, tMs)
        val t = valid.size
        val (logp, enc, npuMs) = encode(x, valid)
        val t0 = SystemClock.elapsedRealtimeNanos()
        val dec = decoders[t]
        var steps = 0
        dump?.let { d ->
            writeFloats(File(d.parentFile, d.name + "_ctc.bin"), logp.copyOfRange(0, n * UNITS))
            if (dec != null) writeFloats(File(d.parentFile, d.name + "_dec1.bin"), decode(dec, listOf(intArrayOf(SOS)), enc, valid)[0])
            val pf = File(d.parentFile, d.name + "_prefix.txt")
            if (dec != null && pf.exists()) {
                val ids = intArrayOf(SOS) + pf.readText().trim().split(Regex("\\s+")).map { it.toInt() }
                val prefixes = List(minOf(ids.size, DEC_BATCH)) { ids.copyOfRange(0, it + 1) }
                val out = decode(dec, prefixes, enc, valid)
                writeFloats(File(d.parentFile, d.name + "_decp.bin"), out.fold(FloatArray(0)) { a, b -> a + b })
                // the same graph on the CPU (QA only): separates the NPU compile from this file's input packing
                runCatching {
                    env.createSession(File(d.parentFile, "avsr_dec_t$t.onnx").absolutePath, OrtSession.SessionOptions()).use { cpu ->
                        val outCpu = decode(cpu, prefixes, enc, valid)
                        writeFloats(File(d.parentFile, d.name + "_decp_cpu.bin"), outCpu.fold(FloatArray(0)) { a, b -> a + b })
                    }
                }
            }
        }
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
        val t1 = SystemClock.elapsedRealtimeNanos()
        val listed = scorePersonal(personal, logp, n, enc, valid)
        val open = seen.entries.map { Personal.Option(it.key, it.value, personal = false) }
        val options = Personal.merge(open, listed)
        val personalMs = (SystemClock.elapsedRealtimeNanos() - t1) / 1e6
        return Reading(seen.keys.toList(), seen.values.toList(), n, t, npuMs, decodeMs, steps, options, personalMs)
    }

    /** The person's sentences, each with the model's own whole-sentence score for this clip (8 per NPU run). */
    private fun scorePersonal(sentences: List<String>, ctc: FloatArray, n: Int, enc: FloatArray, valid: FloatArray): List<Personal.Option> {
        val sc = scorers[valid.size] ?: return emptyList()
        val tok = spm ?: return emptyList()
        val items = sentences.distinct().mapNotNull { s ->
            val ids = idsCache.getOrPut(s) { tok.encode(s) }
            if (ids.isEmpty() || ids.size > DEC_LEN - 1) null else s to ids
        }
        val missing = items.filter { it.first !in priorCache }
        if (missing.isNotEmpty()) {
            // the prior: the same scorer with nothing to look at (zero memory, every frame masked), smallest bucket
            val t0 = FreeTalk.BUCKETS.first { it in scorers }
            val zeros = FloatArray(t0 * D)
            val none = FloatArray(t0)
            for (chunk in missing.chunked(DEC_BATCH)) {
                val att = attention(scorers.getValue(t0), chunk.map { it.second }, zeros, none)
                chunk.forEachIndexed { r, (text, _) -> priorCache[text] = att[r].sum() }
            }
        }
        val out = ArrayList<Personal.Option>()
        for (chunk in items.chunked(DEC_BATCH)) {
            val att = attention(sc, chunk.map { it.second }, enc, valid)
            chunk.forEachIndexed { r, (text, ids) ->
                val score = Personal.score(ctc, n, UNITS, ids, att[r])
                out += Personal.Option(text, score, personal = true, rank = Personal.rank(score, priorCache.getValue(text)))
            }
        }
        return out
    }

    /** log p_att of each target (the tokens, then eos) given its prefix, for up to 8 sentences in one scorer run. */
    private fun attention(sc: OrtSession, sentences: List<IntArray>, enc: FloatArray, valid: FloatArray): List<DoubleArray> {
        val (h, lse) = scoreRows(sc, sentences.map { intArrayOf(SOS) + it }, enc, valid)
        return sentences.mapIndexed { r, ids ->
            val targets = ids + SOS // eos = sos id
            DoubleArray(targets.size) { j ->
                val ho = (r * DEC_LEN + j) * D
                val wo = targets[j] * D
                var dot = 0.0
                for (k in 0 until D) dot += h[ho + k] * outW[wo + k]
                dot + outB[targets[j]] - lse[r * DEC_LEN + j]
            }
        }
    }

    /** One scorer run: final decoder states (B x L x 768) and log-sum-exp of the logits (B x L) for up to 8 prefixes. */
    private fun scoreRows(sc: OrtSession, prefixes: List<IntArray>, enc: FloatArray, valid: FloatArray): Pair<FloatArray, FloatArray> {
        val t = valid.size
        val x = embedRows(prefixes)
        val inputs = mapOf(
            "x" to OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(DEC_BATCH.toLong(), DEC_LEN.toLong(), D.toLong())),
            "memory" to OnnxTensor.createTensor(env, FloatBuffer.wrap(enc), longArrayOf(1, t.toLong(), 768)),
            "valid" to OnnxTensor.createTensor(env, FloatBuffer.wrap(valid), longArrayOf(1, t.toLong())),
        )
        try {
            sc.run(inputs).use { out ->
                val h = (out.get("h").get() as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                val lse = (out.get("lse").get() as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                return h to lse
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    /** Decoder input rows: token embedding * sqrt(768) + position, sos after each prefix (the NPU never sees ids). */
    private fun embedRows(prefixes: List<IntArray>): FloatArray {
        val x = FloatArray(DEC_BATCH * DEC_LEN * D)
        for (b in 0 until DEC_BATCH) {
            val p = prefixes.getOrNull(b)
            for (j in 0 until DEC_LEN) {
                val tok = if (p != null && j < p.size) p[j] else SOS
                val e = tok * D
                val q = j * D
                val o = (b * DEC_LEN + j) * D
                for (k in 0 until D) x[o + k] = embed[e + k] + pos[q + k]
            }
        }
        return x
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
        val sel = FloatArray(DEC_BATCH * DEC_LEN)
        prefixes.forEachIndexed { b, p -> sel[b * DEC_LEN + p.size - 1] = 1f }
        for (b in prefixes.size until DEC_BATCH) sel[b * DEC_LEN] = 1f
        // the embedding lookup runs here, not on the NPU (its compile of the Gather gave wrong rows past sos)
        val x = embedRows(prefixes)
        val inputs = mapOf(
            "x" to OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(DEC_BATCH.toLong(), DEC_LEN.toLong(), D.toLong())),
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

    /** QA: when set, the next read writes its CTC log-probs and the first decoder step next to this path. */
    @Volatile var dump: File? = null

    private fun writeFloats(f: File, a: FloatArray) {
        val b = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        b.asFloatBuffer().put(a)
        f.writeBytes(b.array())
    }

    override fun close() = (sessions.values + decoders.values + scorers.values).forEach { it.close() }

    /** A free-talk model on the phone: avsr/models/<id>/ (its NPU graphs, label.txt); [ready] once compiled. */
    data class Model(val id: String, val label: String, val dir: File, val ready: Boolean)

    companion object {
        const val UNITS = 5049
        const val SOS = UNITS - 1
        const val DEC_BATCH = 8
        const val DEC_LEN = 48
        private const val D = 768
        private const val ROI = FreeTalk.ROI.toLong()

        fun folder(context: Context): File = File(context.getExternalFilesDir(null), "avsr").apply { mkdirs() }

        /**
         * Every model pushed to the phone (python -m mouna_encoder avsr-export --out ... --label ...), e.g. the original
         * model and one tuned to this person. Without avsr/models, the graphs in avsr/ itself are the one model.
         */
        fun models(context: Context): List<Model> {
            val root = folder(context)
            val dirs = File(root, "models").listFiles()?.filter { it.isDirectory && File(it, "avsr_vsr_t64.onnx").let { f -> f.exists() || File(it, "avsr_vsr_t64.qnn_ctx_fp16.onnx").exists() } }
                ?.sortedBy { it.name }.orEmpty()
            val all = dirs.ifEmpty { if (File(root, "avsr_vsr_t64.onnx").exists() || File(root, "avsr_vsr_t64.qnn_ctx_fp16.onnx").exists()) listOf(root) else emptyList() }
            return all.map { d ->
                val label = File(d, "label.txt").takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null } ?: if (d == root) "Free talk" else d.name
                val ready = FreeTalk.BUCKETS.all { t -> listOf("vsr", "dec", "score").all { File(d, "avsr_${it}_t$t.qnn_ctx_fp16.onnx").exists() } }
                Model(if (d == root) "" else d.name, label, d, ready)
            }
        }

        /**
         * Opens every bucket on the NPU (compiling and caching the context binary the first time, which takes a while).
         * NPU only: no CPU fallback. Returns the reader, or null and why.
         */
        fun open(context: Context, model: String?, log: (String) -> Unit): Pair<OpenVsr?, String> {
            val all = models(context)
            val m = all.firstOrNull { it.id == model } ?: all.firstOrNull()
                ?: return null to "Free talk: no model in ${folder(context).absolutePath}"
            val dir = m.dir
            val root = folder(context)
            /** Lookup tables shared by every model live in avsr/; a model's own copy wins. */
            fun shared(name: String) = File(dir, name).takeIf { it.exists() } ?: File(root, name)
            val tokFile = shared("tokens.txt")
            if (!tokFile.exists()) return null to "Free talk: no model in ${dir.absolutePath}"
            val tokens = tokFile.readLines()
            if (tokens.size != UNITS) return null to "Free talk: tokens.txt has ${tokens.size} units, expected $UNITS"
            val env = OrtEnvironment.getEnvironment()
            val sessions = LinkedHashMap<Int, OrtSession>()
            val notes = mutableListOf<String>()
            val decoders = LinkedHashMap<Int, OrtSession>()
            val scorers = LinkedHashMap<Int, OrtSession>()
            // Compile what is missing first, one graph at a time, releasing each: compiling the 10 s graph while six
            // others were loaded got the app killed for memory (lmkd, measured on the iQOO 15). Then load from cache.
            for (t in FreeTalk.BUCKETS) for (kind in listOf("vsr", "dec", "score")) {
                val name = "avsr_${kind}_t$t"
                val plain = File(dir, "$name.onnx")
                val ctx = File(dir, "$name.qnn_ctx_fp16.onnx")
                val mark = File(dir, ".$name.crashed")
                if (ctx.exists() || !plain.exists() || mark.exists()) continue
                mark.writeText("compiling")
                log("free talk $name: compiling for the NPU (first start, takes minutes)…")
                val t0 = SystemClock.elapsedRealtimeNanos()
                val compiled = runCatching { env.createSession(plain.absolutePath, qnn(compileTo = ctx)).close() }
                mark.delete()
                compiled.exceptionOrNull()?.let {
                    ctx.delete()
                    log("free talk $name failed: ${it.message}")
                } ?: log("free talk $name compiled in ${"%.0f".format((SystemClock.elapsedRealtimeNanos() - t0) / 1e6)} ms")
            }
            for (t in FreeTalk.BUCKETS) {
                for ((kind, into) in listOf("vsr" to sessions, "dec" to decoders, "score" to scorers)) {
                    val name = "avsr_${kind}_t$t"
                    val plain = File(dir, "$name.onnx")
                    val ctx = File(dir, "$name.qnn_ctx_fp16.onnx")
                    val mark = File(dir, ".$name.crashed")
                    if (mark.exists()) { notes += "$name crashed last time: skipped (delete ${mark.name} to retry)"; continue }
                    if (!ctx.exists() && !plain.exists()) continue
                    if (kind != "vsr" && t !in sessions) continue
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
            val embedF = shared("dec_embed.bin")
            val posF = shared("dec_pos.bin")
            if (decoders.isNotEmpty() && (!embedF.exists() || !posF.exists())) {
                decoders.values.forEach { it.close() }
                decoders.clear()
                notes += "decoder tables (dec_embed.bin, dec_pos.bin) missing: CTC only"
            }
            val embed = if (decoders.isEmpty()) FloatArray(0) else readFloats(embedF)
            val pos = if (decoders.isEmpty()) FloatArray(0) else readFloats(posF)
            // personal sentences (issue #5 A): the scorer graphs, the decoder's output layer and the tokenizer
            val wF = shared("dec_out_w.bin")
            val bF = shared("dec_out_b.bin")
            val pF = shared("spm_pieces.tsv")
            val score = decoders.isNotEmpty() && scorers.isNotEmpty() && wF.exists() && bF.exists() && pF.exists()
            if (!score) {
                scorers.values.forEach { it.close() }
                scorers.clear()
            }
            val spm = if (score) Spm(Spm.parse(pF.readLines()), tokens) else null
            return OpenVsr(
                env, sessions, decoders, tokens, embed, pos,
                scorers, if (score) readFloats(wF) else FloatArray(0), if (score) readFloats(bF) else FloatArray(0), spm, m,
            ) to "Free talk: ${m.label} · NPU · fp16 · buckets ${sessions.keys.joinToString("/")}" +
                " · decoder ${if (decoders.isEmpty()) "none (CTC only)" else decoders.keys.joinToString("/")}" +
                " · personal ${if (scorers.isEmpty()) "off" else "on"}" +
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
