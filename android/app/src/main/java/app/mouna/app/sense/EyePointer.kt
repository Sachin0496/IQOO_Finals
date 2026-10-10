package app.mouna.app.sense

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Eye control: look at a button to outline it (as Tab does on a laptop), a long blink presses it, two quick blinks go
 * back. Pure maths, unit-tested on the JVM; the Android side (the outline, the clicks) is ui/EyeControl.kt.
 *
 * The phone's selfie camera sees the iris move only a few pixels across the screen, so where the person looks is
 * learned per person: a calibration (9 dots quick, 21 accurate) fits the eyes (iris across and up/down, how open the
 * lids are) and the head (yaw, pitch) to screen positions. The pointer is then snapped to the nearest button, which is
 * what makes a coarse estimate usable.
 */

/** Iris centre across the eye's height, both eyes: 0 on the corner-to-corner line, + towards the lower lid. */
fun irisVertical(xs: FloatArray, ys: FloatArray, w: Int, h: Int): Float {
    var s = 0f
    for ((iris, ia, ib) in EYE_IRIS) {
        var ax = xs[ia] * w; var ay = ys[ia] * h
        var bx = xs[ib] * w; var by = ys[ib] * h
        if (ax > bx) { ax = bx.also { bx = ax }; ay = by.also { by = ay } }
        val vx = bx - ax; val vy = by - ay
        val len2 = vx * vx + vy * vy
        if (len2 <= 0f) return 0f
        // (-vy, vx) points down the image when the corners run left to right
        s += ((xs[iris] * w - ax) * -vy + (ys[iris] * h - ay) * vx) / len2
    }
    return s / EYE_IRIS.size
}

private val EYE_IRIS = listOf(Triple(468, 33, 133), Triple(473, 362, 263))

/** What the gaze map reads from a frame: iris across, iris up/down, lid opening, head yaw, head pitch. Null without eyes. */
fun gazeFeatures(f: Frame): DoubleArray? =
    if (!f.face || f.iris.isNaN()) null
    else doubleArrayOf(f.iris, f.irisY.toDouble(), f.eyeOpen.toDouble(), f.yawDeg.toDouble(), f.pitch.toDouble())

/** One calibration dot: where it was on screen (px) and the features of every frame while the person looked at it. */
class GazePoint(val x: Double, val y: Double, val samples: List<DoubleArray>)

sealed interface GazeFit {
    data class Ready(val map: GazeMap) : GazeFit
    data class Failed(val message: String) : GazeFit
}

/**
 * Features to screen position: two ridge regressions on standardised features. With enough dots ([CURVED_MIN]) the map
 * may also bend ([curved]: squares and the product of the two iris terms), which follows the eye's flattening towards the
 * screen's edges; it is kept only if it predicts unseen dots better. [errorPx] is the leave-one-dot-out error of the
 * calibration (honest: each dot is predicted by a map that never saw it). [closedBelow] is the lid opening under which
 * the eyes count as shut, set below the narrowest opening seen while looking at any dot (looking down lowers the lids).
 */
class GazeMap(
    val mean: DoubleArray,
    val scale: DoubleArray,
    val wx: DoubleArray,
    val wy: DoubleArray,
    val closedBelow: Double,
    val errorPx: Double,
) {
    val curved get() = wx.size == CURVED_TERMS + 1

    fun predict(f: DoubleArray): Pair<Double, Double> {
        val z = terms(f, mean, scale, curved)
        var x = wx[0]
        var y = wy[0]
        for (i in z.indices) {
            x += wx[i + 1] * z[i]
            y += wy[i + 1] * z[i]
        }
        return x to y
    }

    /** One line for the store. */
    fun toText(): String = listOf(mean, scale, wx, wy, doubleArrayOf(closedBelow, errorPx)).joinToString(";") { it.joinToString(",") }

    companion object {
        const val FEATURES = 5
        /** Linear terms, then iris across squared, iris up/down squared, and their product. */
        private const val CURVED_TERMS = FEATURES + 3
        /** Fewer dots than this and a curved map would fit noise: it is not tried. */
        const val CURVED_MIN = 15
        private const val RIDGE = 0.5

        private fun terms(f: DoubleArray, mean: DoubleArray, scale: DoubleArray, curved: Boolean): DoubleArray {
            val z = DoubleArray(if (curved) CURVED_TERMS else FEATURES)
            for (i in 0 until FEATURES) z[i] = (f[i] - mean[i]) / scale[i]
            if (curved) {
                z[FEATURES] = z[0] * z[0] - 1
                z[FEATURES + 1] = z[1] * z[1] - 1
                z[FEATURES + 2] = z[0] * z[1]
            }
            return z
        }

        fun fromText(s: String): GazeMap? = runCatching {
            val p = s.split(";").map { part -> part.split(",").map { it.toDouble() }.toDoubleArray() }
            require(p.size == 5 && p[0].size == FEATURES && p[1].size == FEATURES)
            require(p[2].size == p[3].size && (p[2].size == FEATURES + 1 || p[2].size == CURVED_TERMS + 1))
            GazeMap(p[0], p[1], p[2], p[3], p[4][0], p[4][1])
        }.getOrNull()

        /** [maxErrorPx]: a calibration whose dots are predicted worse than this is refused (the eyes moved too little). */
        fun fit(points: List<GazePoint>, maxErrorPx: Double): GazeFit {
            val used = points.filter { it.samples.size >= 8 }
            if (used.size < 6) return GazeFit.Failed("Mouna could not see your eyes at most of the dots. Hold the phone in front of your face, in good light.")
            val shapes = if (used.size >= CURVED_MIN) listOf(false, true) else listOf(false)
            val (curved, err) = shapes.map { it to leaveOneOut(used, it) }.minBy { it.second }
            if (err > maxErrorPx) return GazeFit.Failed("Your eyes moved too little to tell the dots apart. Hold the phone a little closer and follow each dot with your eyes.")
            val map = solve(used, curved)
            val narrowest = used.minOf { p -> p.samples.map { it[2] }.sorted()[p.samples.size / 2] }
            return GazeFit.Ready(GazeMap(map.mean, map.scale, map.wx, map.wy, closedBelow = 0.6 * narrowest, errorPx = err))
        }

        /** Each dot left out in turn and predicted from the others (median of its frames): the mean miss in pixels. */
        private fun leaveOneOut(used: List<GazePoint>, curved: Boolean): Double {
            var err = 0.0
            for (i in used.indices) {
                val m = solve(used.filterIndexed { j, _ -> j != i }, curved)
                val p = used[i]
                val xs = p.samples.map { m.predict(it).first }.sorted()
                val ys = p.samples.map { m.predict(it).second }.sorted()
                err += hypot(xs[xs.size / 2] - p.x, ys[ys.size / 2] - p.y)
            }
            return err / used.size
        }

        private fun solve(points: List<GazePoint>, curved: Boolean): GazeMap {
            val rows = points.flatMap { it.samples }
            val n = rows.size
            val mean = DoubleArray(FEATURES) { c -> rows.sumOf { it[c] } / n }
            val scale = DoubleArray(FEATURES) { c -> max(1e-6, sqrt(rows.sumOf { (it[c] - mean[c]).let { d -> d * d } } / n)) }
            val k = if (curved) CURVED_TERMS else FEATURES
            // Normal equations of [1, terms] with a ridge on everything but the intercept.
            val a = Array(k + 1) { DoubleArray(k + 1) }
            val bx = DoubleArray(k + 1)
            val by = DoubleArray(k + 1)
            val z = DoubleArray(k + 1)
            for (p in points) for (r in p.samples) {
                z[0] = 1.0
                terms(r, mean, scale, curved).copyInto(z, 1)
                for (i in 0..k) {
                    for (j in 0..k) a[i][j] += z[i] * z[j]
                    bx[i] += z[i] * p.x
                    by[i] += z[i] * p.y
                }
            }
            for (i in 1..k) a[i][i] += RIDGE * n / points.size
            return GazeMap(mean, scale, linSolve(a, bx), linSolve(a, by), 0.0, 0.0)
        }

        /** Gaussian elimination with partial pivoting (at most a 9 x 9 system). */
        private fun linSolve(a0: Array<DoubleArray>, b0: DoubleArray): DoubleArray {
            val n = b0.size
            val a = Array(n) { a0[it].copyOf() }
            val b = b0.copyOf()
            for (c in 0 until n) {
                val p = (c until n).maxBy { abs(a[it][c]) }
                a[c] = a[p].also { a[p] = a[c] }
                b[c] = b[p].also { b[p] = b[c] }
                if (abs(a[c][c]) < 1e-12) continue
                for (r in c + 1 until n) {
                    val f = a[r][c] / a[c][c]
                    for (j in c until n) a[r][j] -= f * a[c][j]
                    b[r] -= f * b[c]
                }
            }
            val x = DoubleArray(n)
            for (r in n - 1 downTo 0) {
                var s = b[r]
                for (j in r + 1 until n) s -= a[r][j] * x[j]
                x[r] = if (abs(a[r][r]) < 1e-12) 0.0 else s / a[r][r]
            }
            return x
        }
    }
}

enum class EyeAction { CLICK, BACK }

/**
 * Blinks as buttons. A natural blink (about 0.1-0.3 s, many a minute) must do nothing, so:
 *  - press = one long blink: eyes shut for [holdMs] to [maxHoldMs], acted on when they open again;
 *  - back = two quick blinks, the second starting within [gapMs] of the first ending.
 * [closed] is true while the eyes are shut or closing, so the pointer can stand still (the iris is lost under the lids).
 */
class BlinkClicks(
    private val closedBelow: Double,
    private val minBlinkMs: Long = 40,
    private val quickMs: Long = 350,
    private val holdMs: Long = 450,
    private val maxHoldMs: Long = 2000,
    private val gapMs: Long = 600,
    private val refractoryMs: Long = 800,
) {
    private var shutAt = -1L
    private var lastQuickEnd = Long.MIN_VALUE / 2
    private var quietUntil = Long.MIN_VALUE
    var closed = false
        private set
    /** How long the eyes have been shut, for the outline's "hold" feedback. 0 while open. */
    var shutMs = 0L
        private set

    fun push(openness: Double, t: Long): EyeAction? {
        val shut = openness < closedBelow
        closed = openness < closedBelow / 0.6 * 0.8 // closing: well under the narrowest open look
        if (shut) {
            if (shutAt < 0) shutAt = t
            shutMs = t - shutAt
            return null
        }
        shutMs = 0
        if (shutAt < 0) return null
        val len = t - shutAt // the eyes just opened: what was that?
        shutAt = -1
        if (t < quietUntil || len < minBlinkMs) return null
        if (len in holdMs..maxHoldMs) {
            quietUntil = t + refractoryMs
            lastQuickEnd = Long.MIN_VALUE / 2
            return EyeAction.CLICK
        }
        if (len <= quickMs) {
            if (t - len - lastQuickEnd <= gapMs) {
                quietUntil = t + refractoryMs
                lastQuickEnd = Long.MIN_VALUE / 2
                return EyeAction.BACK
            }
            lastQuickEnd = t
        }
        return null
    }
}

/** A rectangle in screen pixels (android.graphics.RectF is a stub in JVM tests). */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val area get() = (right - left) * (bottom - top)
    fun distance(x: Float, y: Float): Float {
        val dx = max(0f, max(left - x, x - right))
        val dy = max(0f, max(top - y, y - bottom))
        return hypot(dx, dy)
    }
}

/**
 * Which button is outlined. The nearest one to the gaze (inside two, the smaller: a button on a card beats the card),
 * but the outline only moves once another button has been the nearest for [dwellMs] and is clearly nearer than the
 * outlined one by [marginPx]: the estimate jitters, the outline must not.
 */
class FocusPicker(private val dwellMs: Long = 180, private val marginPx: Float = 24f) {
    var current: Int? = null
        private set
    private var candidate: Int? = null
    private var since = 0L

    fun reset() {
        current = null
        candidate = null
    }

    /** [targets]: id to box. Returns the outlined id. */
    fun push(targets: Map<Int, Box>, x: Float, y: Float, t: Long): Int? {
        if (targets.isEmpty()) {
            reset()
            return null
        }
        val best = targets.minWith(compareBy<Map.Entry<Int, Box>> { it.value.distance(x, y) }.thenBy { it.value.area }).key
        val cur = current
        val curBox = cur?.let { targets[it] }
        if (curBox == null) { // nothing outlined yet, or it has gone (a new screen)
            current = best
            candidate = null
            return best
        }
        if (best == cur) {
            candidate = null
            return cur
        }
        val bestBox = targets.getValue(best)
        val clearlyNearer = bestBox.distance(x, y) + marginPx < curBox.distance(x, y) ||
            (bestBox.distance(x, y) == 0f && curBox.distance(x, y) == 0f && bestBox.area < curBox.area) // nested: the inner one
        if (!clearlyNearer) {
            candidate = null
            return cur
        }
        if (candidate != best) {
            candidate = best
            since = t
        }
        if (t - since >= dwellMs) {
            current = best
            candidate = null
        }
        return current
    }
}

/** Light smoothing for the gaze estimate: an exponential average whose weight rises with big jumps (a new look). */
class GazeSmoother(private val slow: Double = 0.18, private val fast: Double = 0.6, private val jumpPx: Double = 160.0) {
    private var x = Double.NaN
    private var y = Double.NaN

    fun reset() {
        x = Double.NaN
        y = Double.NaN
    }

    fun push(nx: Double, ny: Double): Pair<Double, Double> {
        if (x.isNaN()) {
            x = nx; y = ny
        } else {
            val a = slow + (fast - slow) * min(1.0, hypot(nx - x, ny - y) / jumpPx)
            x += a * (nx - x)
            y += a * (ny - y)
        }
        return x to y
    }
}
