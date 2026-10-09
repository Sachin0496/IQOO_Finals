package app.mouna.app.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import org.json.JSONArray
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Indian Sign Language with AI4Bharat's existing OpenHands SL-GCN (INCLUDE, 263 isolated signs). We only exported it
 * (models/isl). Input: one sign's keypoints from [app.mouna.app.sense.Signer]; output: the most likely words.
 */
class Isl private constructor(private val session: OrtSession, private val env: OrtEnvironment, val labels: List<String>) : AutoCloseable {
    data class Guess(val word: String, val p: Float)

    fun classify(frames: List<FloatArray>): List<Guess> {
        val t = frames.size
        val x = normalise(frames)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, 2, t.toLong(), V.toLong())).use { input ->
            session.run(mapOf("keypoints" to input)).use { out ->
                val p = (out.get(0) as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                return p.indices.sortedByDescending { p[it] }.take(5).map { Guess(word(labels[it]), p[it]) }
            }
        }
    }

    override fun close() = session.close()

    companion object {
        const val V = 27
        private const val S1 = 3 // left shoulder in the 27
        private const val S2 = 4 // right shoulder

    /**
     * OpenHands' CenterAndScaleNormalize (clip level): centre on the mean shoulder midpoint, scale by the mean shoulder
     * distance. frames: each 27 x 2 points. Returns (1, C=2, T, V=27), channel-major, the model's input.
     */
    fun normalise(frames: List<FloatArray>): FloatArray {
        val t = frames.size
        var cx = 0f; var cy = 0f; var dist = 0f
        for (f in frames) {
            cx += (f[2 * S1] + f[2 * S2]) / 2
            cy += (f[2 * S1 + 1] + f[2 * S2 + 1]) / 2
            val dx = f[2 * S1] - f[2 * S2]
            val dy = f[2 * S1 + 1] - f[2 * S2 + 1]
            dist += sqrt(dx * dx + dy * dy)
        }
        cx /= t; cy /= t; dist /= t
        val scale = if (dist > 0) 1f / dist else 1f
        val x = FloatArray(2 * t * V)
        for ((ti, f) in frames.withIndex()) for (v in 0 until V) {
            x[(0 * t + ti) * V + v] = (f[2 * v] - cx) * scale
            x[(1 * t + ti) * V + v] = (f[2 * v + 1] - cy) * scale
        }
        return x
    }

        /** "4.sad" -> "sad", "10.Energy" -> "energy" */
        fun word(label: String) = label.substringAfter('.').trim().lowercase()

        fun folder(context: Context) = File(context.getExternalFilesDir(null), "isl").apply { mkdirs() }

        fun open(context: Context): Isl? {
            // This ONNX Runtime (QNN edition, no XNNPACK / NNAPI) traps on the emulator's CPU for this model (Apple M4
            // hosts advertise SME, then fault). Phones are fine. Verified on the Mac instead: IslTest + models/isl.
            if (isEmulator) return null
            val dir = folder(context)
            val model = dir.listFiles().orEmpty().firstOrNull { it.name.endsWith(".onnx") } ?: return null
            val labels = File(dir, "isl_include_labels.json").takeIf { it.exists() }?.readText()?.let { s ->
                JSONArray(s).let { a -> List(a.length()) { a.getString(it) } }
            } ?: return null
            val env = OrtEnvironment.getEnvironment()
            return crashGuarded(dir, model.name) {
                runCatching {
                    Isl(env.createSession(model.absolutePath, cpuOptions(2)), env, labels).also { m ->
                        m.classify(List(16) { FloatArray(V * 2) { k -> (k % 7).toFloat() } }) // warm-up inside the guard
                    }
                }.onFailure { android.util.Log.e("Mouna", "isl: ${it.message}", it) }.getOrNull()
            }
        }
    }
}
