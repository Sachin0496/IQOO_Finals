package app.mouna.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Free talk input (docs/open-vocab-plan.md): Auto-AVSR's mouth crop and model input, port of
 * `encoder/mouna_encoder/avsr.py` (stable_points, similarity, crop_matrix, model_input, pad_to).
 */
object FreeTalk {
    const val CROP = 96
    const val ROI = 88
    const val FPS = 25.0
    const val MEAN = 0.421f
    const val STD = 0.165f
    /** One NPU graph per length, in frames at 25 fps (2.6 / 5.1 / 10.2 s). */
    val BUCKETS = intArrayOf(64, 128, 256)

    /** Subject's right eye, left eye, nose (iBUG 31-35), mouth in Auto-AVSR's 256 x 256 mean face. */
    private val REFERENCE = arrayOf(
        doubleArrayOf(102.0739, 94.2723),
        doubleArrayOf(156.3613, 93.5782),
        doubleArrayOf(129.0037, 135.9034),
        doubleArrayOf(129.3134, 157.8230),
    )
    private val GROUPS = arrayOf(
        intArrayOf(33, 160, 158, 133, 153, 144),
        intArrayOf(362, 385, 387, 263, 373, 380),
        intArrayOf(98, 97, 2, 326, 327),
        intArrayOf(61, 40, 37, 0, 267, 270, 291, 321, 314, 17, 84, 91, 78, 81, 13, 311, 308, 402, 14, 178),
    )

    /** Face-mesh points in pixels -> the four stable points as (x0, y0, x1, y1, ...). */
    fun stablePoints(xs: FloatArray, ys: FloatArray): DoubleArray {
        val out = DoubleArray(8)
        for ((g, idx) in GROUPS.withIndex()) {
            var x = 0.0
            var y = 0.0
            for (i in idx) { x += xs[i]; y += ys[i] }
            out[2 * g] = x / idx.size
            out[2 * g + 1] = y / idx.size
        }
        return out
    }

    /**
     * Frame pixels -> 96 x 96 crop pixels, as a 2 x 3 matrix (a, -b, tx, b, a, ty): least-squares similarity onto the
     * mean face, then shifted so the mouth lands in the centre of the patch.
     */
    fun cropMatrix(p: DoubleArray): DoubleArray {
        var smx = 0.0; var smy = 0.0; var dmx = 0.0; var dmy = 0.0
        for (i in 0 until 4) { smx += p[2 * i]; smy += p[2 * i + 1]; dmx += REFERENCE[i][0]; dmy += REFERENCE[i][1] }
        smx /= 4; smy /= 4; dmx /= 4; dmy /= 4
        var den = 0.0; var na = 0.0; var nb = 0.0
        for (i in 0 until 4) {
            val sx = p[2 * i] - smx; val sy = p[2 * i + 1] - smy
            val dx = REFERENCE[i][0] - dmx; val dy = REFERENCE[i][1] - dmy
            den += sx * sx + sy * sy
            na += sx * dx + sy * dy
            nb += sx * dy - sy * dx
        }
        val a = na / den
        val b = nb / den
        var tx = dmx - (a * smx - b * smy)
        var ty = dmy - (b * smx + a * smy)
        val mx = a * p[6] - b * p[7] + tx
        val my = b * p[6] + a * p[7] + ty
        tx += CROP / 2.0 - mx
        ty += CROP / 2.0 - my
        return doubleArrayOf(a, -b, tx, b, a, ty)
    }

    private const val TRIM_REL = 0.35
    private const val TRIM_MIN = 1.2
    private const val TRIM_PAD = 6

    /**
     * [start, end) of the moving part of a clip, from the mouth crops themselves (port of avsr.py active_span): mean
     * absolute frame-to-frame change over the mouth (rows 24-71, columns 16-79), averaged over 5 frames, above
     * max(1.2, 0.35 x its 90th percentile), padded by 6 frames. Measured on 24 silent recordings: clips ran on for
     * seconds and the decoder filled the stillness with invented words; trimming took WER 114% -> 85%.
     */
    fun activeSpan(crops: List<ByteArray>): IntRange {
        val n = crops.size
        if (n == 0) return IntRange.EMPTY
        val mv = DoubleArray(n)
        for (i in 1 until n) {
            val a = crops[i - 1]
            val b = crops[i]
            var sum = 0L
            for (r in 24 until 72) for (c in 16 until 80) {
                val o = r * CROP + c
                sum += kotlin.math.abs((b[o].toInt() and 0xff) - (a[o].toInt() and 0xff))
            }
            mv[i] = sum / (48.0 * 64.0)
        }
        val sm = DoubleArray(n) { i -> (maxOf(0, i - 2)..minOf(n - 1, i + 2)).sumOf { mv[it] * 0.2 } }
        val sorted = sm.sorted()
        val h = (n - 1) * 0.9
        val lo = kotlin.math.floor(h).toInt()
        val p90 = if (lo + 1 < n) sorted[lo] + (h - lo) * (sorted[lo + 1] - sorted[lo]) else sorted[lo]
        val thr = maxOf(TRIM_MIN, TRIM_REL * p90)
        val first = sm.indexOfFirst { it > thr }
        if (first < 0) return 0 until n
        val last = sm.indexOfLast { it > thr }
        return maxOf(0, first - TRIM_PAD) until minOf(n, last + TRIM_PAD + 1)
    }

    /** Index of the bucket a clip of [frames] frames runs in (the longest one if it is longer: the clip is cut). */
    fun bucket(frames: Int): Int = BUCKETS.firstOrNull { frames <= it } ?: BUCKETS.last()

    /**
     * Grey 96 x 96 crops at the camera's rate (timestamps in ms) -> the model's (1, T, 88, 88) input at 25 fps for a
     * bucket of T frames, zeros after the clip, plus the (1, T) mask of real frames. Returns (x, valid, frames used).
     */
    fun input(crops: List<ByteArray>, tMs: LongArray): Triple<FloatArray, FloatArray, Int> {
        val dur = (tMs.last() - tMs.first()) / 1000.0
        val n25 = maxOf(1, (dur * FPS).roundToInt() + 1)
        val t = bucket(n25)
        val n = minOf(n25, t)
        val x = FloatArray(t * ROI * ROI)
        val valid = FloatArray(t)
        val o = (CROP - ROI) / 2
        var j = 0
        for (i in 0 until n) {
            val want = tMs.first() + (i * 1000.0 / FPS)
            while (j + 1 < tMs.size && abs(tMs[j + 1] - want) <= abs(tMs[j] - want)) j++
            val c = crops[j]
            val base = i * ROI * ROI
            for (r in 0 until ROI) for (q in 0 until ROI) {
                val v = (c[(r + o) * CROP + q + o].toInt() and 0xff) / 255f
                x[base + r * ROI + q] = (v - MEAN) / STD
            }
            valid[i] = 1f
        }
        return Triple(x, valid, n)
    }
}
