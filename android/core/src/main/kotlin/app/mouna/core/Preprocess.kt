package app.mouna.core

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Mouth crops -> static encoder input: a port of encoder/mouna_encoder/preprocess.py (to_encoder_input with
 * frames = 48) followed by model.py stack_frames. Pinned by src/test/resources/selftest (SelfTestParityTest).
 *
 * crops: `frames` grey images of size x size (the Lab/app 96 px mouth crop), frame after frame.
 * tMs: the timestamp of each frame. Returns (48, 5, 88, 88) float32 in row-major order, ready for the encoder's
 * `frames` input.
 */
object EncoderInput {
    const val WINDOW = 48
    const val STACK = 5
    const val ROI = 128 // the crop is scaled to the LRW mouth ROI, then centre-cropped
    const val INPUT = 88
    val SHAPE = intArrayOf(WINDOW, STACK, INPUT, INPUT)

    fun build(crops: ByteArray, frames: Int, size: Int, tMs: DoubleArray): FloatArray {
        require(frames >= 1 && crops.size >= frames * size * size && tMs.size == frames)
        // 1. stretch the clip to a fixed 48-frame window (numpy linspace + searchsorted, side left)
        val pick = IntArray(WINDOW) { i ->
            val g = if (WINDOW == 1) tMs[0] else tMs[0] + (tMs[frames - 1] - tMs[0]) * i / (WINDOW - 1)
            min(lowerBound(tMs, g), frames - 1)
        }
        // 2. bilinear resize size -> ROI (PyTorch align_corners=False), 3. centre crop INPUT
        val o = (ROI - INPUT) / 2
        val scale = size.toDouble() / ROI
        val x0 = IntArray(INPUT)
        val x1 = IntArray(INPUT)
        val lx = FloatArray(INPUT)
        for (j in 0 until INPUT) {
            var src = (scale * (j + o + 0.5) - 0.5).toFloat()
            if (src < 0) src = 0f
            x0[j] = src.toInt()
            x1[j] = if (x0[j] < size - 1) x0[j] + 1 else x0[j]
            lx[j] = src - x0[j]
        }
        val plane = INPUT * INPUT
        val resized = Array(WINDOW) { FloatArray(plane) }
        for (f in 0 until WINDOW) {
            val base = pick[f] * size * size
            val out = resized[f]
            for (r in 0 until INPUT) {
                val y0 = x0[r]
                val y1 = x1[r]
                val ly = lx[r]
                for (c in 0 until INPUT) {
                    val a = px(crops, base + y0 * size + x0[c])
                    val b = px(crops, base + y0 * size + x1[c])
                    val d = px(crops, base + y1 * size + x0[c])
                    val e = px(crops, base + y1 * size + x1[c])
                    val top = a + (b - a) * lx[c]
                    val bottom = d + (e - d) * lx[c]
                    out[r * INPUT + c] = top + (bottom - top) * ly
                }
            }
        }
        // 4. each frame with its two neighbours either side, zero-padded in time
        val x = FloatArray(WINDOW * STACK * plane)
        for (t in 0 until WINDOW) for (k in 0 until STACK) {
            val src = t + k - 2
            if (src < 0 || src >= WINDOW) continue
            System.arraycopy(resized[src], 0, x, (t * STACK + k) * plane, plane)
        }
        return x
    }

    private fun px(crops: ByteArray, i: Int) = (crops[i].toInt() and 0xff) / 255f

    /** First index with a[i] >= v (numpy searchsorted, side="left"). */
    private fun lowerBound(a: DoubleArray, v: Double): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < v) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

/** Cosine similarity, for the start-up self-test: phone embedding vs the expected one (pass at 0.99). */
fun cosine(a: FloatArray, b: FloatArray): Double {
    var ab = 0.0
    var aa = 0.0
    var bb = 0.0
    for (i in a.indices) {
        ab += a[i] * b[i].toDouble()
        aa += a[i] * a[i].toDouble()
        bb += b[i] * b[i].toDouble()
    }
    return ab / (sqrt(aa) * sqrt(bb) + 1e-12)
}

/**
 * The start-up self-test clip (encoder/mouna_encoder/selftest.py): run [input] through the encoder on the NPU and
 * require cosine(embedding, [expected]) >= [passCosine]; otherwise fall back to the CPU and say so on screen.
 */
class SelfTest private constructor(val input: FloatArray, val expected: FloatArray, val expectedInt8: FloatArray, val passCosine: Double) {
    fun passes(embedding: FloatArray, int8: Boolean = false) = cosine(embedding, if (int8) expectedInt8 else expected) >= passCosine

    companion object {
        fun load(): SelfTest {
            fun res(n: String) = SelfTest::class.java.getResourceAsStream("/mouna/selftest/$n")?.readBytes() ?: error("missing /mouna/selftest/$n")
            val d = org.json.JSONObject(String(res("selftest.json"), Charsets.UTF_8))
            val t = d.getJSONArray("t_ms").let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
            fun floats(k: String) = d.getJSONArray(k).let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
            val x = EncoderInput.build(res("clip.u8"), d.getInt("frames"), d.getInt("size"), t)
            return SelfTest(x, floats("embedding_fp32"), floats("embedding_int8"), d.getDouble("pass_cosine"))
        }
    }
}
