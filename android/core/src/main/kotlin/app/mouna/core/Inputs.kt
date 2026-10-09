package app.mouna.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

/*
 * Inputs beyond the lips: ports of lab/src/core/switch.ts (personal switch over MediaPipe's 52 blendshape scores)
 * and lab/src/core/gaze.ts (look left / right to choose). Same constants, same behaviour; see those files.
 */

// ---------------- personal switch ----------------

data class SwitchChannel(val index: Int, val name: String, val rest: Double, val peak: Double, val z: Double)

data class SwitchModel(val channels: List<SwitchChannel>)

data class SwitchConfig(
    val minZ: Double = 4.0,
    val minOverRest: Double = 1.5,
    val maxChannels: Int = 3,
    val on: Double = 0.6,
    val off: Double = 0.3,
    val holdMs: Long = 120,
    val refractoryMs: Long = 900,
)

sealed interface SwitchTeachResult {
    data class Learned(val model: SwitchModel) : SwitchTeachResult
    data class Failed(val message: String) : SwitchTeachResult
}

private fun quantile(xs: List<Double>, q: Double): Double {
    if (xs.isEmpty()) return 0.0
    val s = xs.sorted()
    return s[min(s.size - 1, max(0, (q * (s.size - 1)).roundToInt()))]
}

/** rest: frames of normal face incl. some mouthing (2 s+). moves: one window of frames per cued example (3). */
fun teachSwitch(rest: List<FloatArray>, moves: List<List<FloatArray>>, names: List<String> = emptyList(), cfg: SwitchConfig = SwitchConfig()): SwitchTeachResult {
    if (rest.size < 30) return SwitchTeachResult.Failed("Need a few seconds of your normal face first.")
    if (moves.size < 2) return SwitchTeachResult.Failed("Show the movement at least twice.")
    val found = mutableListOf<SwitchChannel>()
    for (c in rest[0].indices) {
        val r = rest.map { it[c].toDouble() }
        val mu = r.average()
        val sigma = max(0.01, sqrt(r.sumOf { (it - mu) * (it - mu) } / r.size))
        val wobble = quantile(r.map { abs(it - mu) }, 0.99)
        val peaks = moves.map { m ->
            var hi = Double.NEGATIVE_INFINITY
            var lo = Double.POSITIVE_INFINITY
            for (f in m) {
                hi = max(hi, f[c] - mu)
                lo = min(lo, f[c] - mu)
            }
            if (abs(hi) >= abs(lo)) hi else lo
        }
        val peak = quantile(peaks, 0.5)
        val sameWay = peaks.count { sign(it) == sign(peak) && abs(it) >= cfg.minZ * sigma }
        val z = abs(peak) / sigma
        if (z >= cfg.minZ && abs(peak) >= cfg.minOverRest * wobble && sameWay >= ceil(moves.size * 0.66).toInt()) {
            found.add(SwitchChannel(c, names.getOrElse(c) { "channel $c" }, mu, peak, z))
        }
    }
    if (found.isEmpty()) return SwitchTeachResult.Failed("Mouna could not see that movement clearly. Try a bigger one, or another one.")
    return SwitchTeachResult.Learned(SwitchModel(found.sortedByDescending { it.z }.take(cfg.maxChannels)))
}

/** 0 at rest, 1 at the taught movement. */
fun activation(model: SwitchModel, f: FloatArray): Double {
    if (model.channels.isEmpty()) return 0.0
    return model.channels.sumOf { ch -> max(-1.0, min(2.0, (f[ch.index] - ch.rest) / ch.peak)) } / model.channels.size
}

class PersonalSwitch(val model: SwitchModel, val cfg: SwitchConfig = SwitchConfig()) {
    private var aboveSince = -1L
    private var latched = false
    private var quietUntil = Long.MIN_VALUE
    var level = 0.0
        private set

    /** Feed one frame of blendshape scores. True once per press, on the frame it is recognised. */
    fun push(f: FloatArray, tMs: Long): Boolean {
        level = activation(model, f)
        if (level < cfg.off) {
            latched = false
            aboveSince = -1
            return false
        }
        if (latched || tMs < quietUntil || level < cfg.on) return false
        if (aboveSince < 0) aboveSince = tMs
        if (tMs - aboveSince < cfg.holdMs) return false
        latched = true
        quietUntil = tMs + cfg.refractoryMs
        return true
    }
}

// ---------------- look to choose ----------------

/** Normalised landmark (MediaPipe x, y in 0..1). */
data class Pt(val x: Float, val y: Float)

private val EYES = listOf(Triple(468, 33, 133), Triple(473, 362, 263)) // iris centre, corner, corner

/** Iris position between the eye corners, both eyes: 0 at the image-left corner, 1 at the image-right one. */
fun irisPosition(lm: List<Pt>, w: Int, h: Int): Double {
    var s = 0.0
    for ((iris, ia, ib) in EYES) {
        var a = lm[ia]
        var b = lm[ib]
        if (a.x > b.x) a = b.also { b = a }
        val ax = a.x * w.toDouble()
        val ay = a.y * h.toDouble()
        val vx = b.x * w - ax
        val vy = b.y * h - ay
        val len2 = vx * vx + vy * vy
        s += if (len2 > 0) ((lm[iris].x * w - ax) * vx + (lm[iris].y * h - ay) * vy) / len2 else 0.5
    }
    return s / EYES.size
}

data class GazeModel(val center: Double, val left: Double, val right: Double)

const val MIN_GAZE_SPAN = 0.03

sealed interface GazeCalibration {
    data class Ready(val model: GazeModel) : GazeCalibration
    data class Failed(val message: String) : GazeCalibration
}

fun calibrateGaze(center: List<Double>, left: List<Double>, right: List<Double>): GazeCalibration {
    if (center.size < 5 || left.size < 5 || right.size < 5) return GazeCalibration.Failed("Look at each picture for a moment.")
    val med = { xs: List<Double> -> xs.sorted()[xs.size / 2] }
    val m = GazeModel(med(center), med(left), med(right))
    if (sign(m.left - m.center) == sign(m.right - m.center)) return GazeCalibration.Failed("Left and right looked the same. Try again, moving only the eyes.")
    if (abs(m.left - m.center) < MIN_GAZE_SPAN || abs(m.right - m.center) < MIN_GAZE_SPAN) {
        return GazeCalibration.Failed("The eyes moved too little to tell left from right. Move the phone closer.")
    }
    return GazeCalibration.Ready(m)
}

enum class Zone { LEFT, CENTER, RIGHT }

data class GazeConfig(val enter: Double = 0.55, val exit: Double = 0.35, val dwellMs: Long = 800, val refractoryMs: Long = 1200)

class GazeSelector(val model: GazeModel, val cfg: GazeConfig = GazeConfig()) {
    var zone = Zone.CENTER
        private set
    private var since = 0L
    private var quietUntil = Long.MIN_VALUE

    private fun progress(pos: Double, side: Zone) =
        (pos - model.center) / ((if (side == Zone.LEFT) model.left else model.right) - model.center)

    /** Feed one frame. Returns LEFT or RIGHT on the frame that side is selected by dwell, else null. */
    fun push(pos: Double, tMs: Long): Zone? {
        val next = when (zone) {
            Zone.CENTER -> when {
                progress(pos, Zone.LEFT) >= cfg.enter -> Zone.LEFT
                progress(pos, Zone.RIGHT) >= cfg.enter -> Zone.RIGHT
                else -> Zone.CENTER
            }
            else -> if (progress(pos, zone) < cfg.exit) Zone.CENTER else zone
        }
        if (next != zone) {
            zone = next
            since = tMs
        }
        if (zone == Zone.CENTER || tMs < quietUntil || tMs - since < cfg.dwellMs) return null
        quietUntil = tMs + cfg.refractoryMs
        since = tMs + cfg.refractoryMs
        return zone
    }
}
