package app.mouna.app.sense

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/*
 * Yes and no with no hands, no voice and no setup: nod, shake, or blink twice. Ports of lab/src/core/nod.ts and
 * blink.ts, same constants. Computed from the face landmarks already tracked for the lips.
 */

private const val EYE_R = 33
private const val EYE_L = 263
private const val NOSE_TIP = 1

/** How far the nose tip sits below the eye line, in eye distances. Only its swings matter. */
fun pitchProxy(xs: FloatArray, ys: FloatArray, w: Int, h: Int): Float {
    val rx = xs[EYE_R] * w; val ry = ys[EYE_R] * h
    val lx = xs[EYE_L] * w; val ly = ys[EYE_L] * h
    val nx = xs[NOSE_TIP] * w; val ny = ys[NOSE_TIP] * h
    val iod = hypot(lx - rx, ly - ry)
    if (iod == 0f) return 0f
    val roll = atan2(ly - ry, lx - rx)
    val mx = (rx + lx) / 2; val my = (ry + ly) / 2
    return (-(nx - mx) * sin(roll) + (ny - my) * cos(roll)) / iod
}

private class Eye(val outer: Int, val inner: Int, val up: IntArray, val down: IntArray)
private val RIGHT = Eye(33, 133, intArrayOf(159, 158), intArrayOf(145, 153))
private val LEFT = Eye(263, 362, intArrayOf(386, 385), intArrayOf(374, 380))

/** Mean eye aspect ratio of both eyes: about 0.25-0.35 open, below 0.15 closed. */
fun eyeOpenness(xs: FloatArray, ys: FloatArray, w: Int, h: Int): Float {
    fun d(a: Int, b: Int) = hypot((xs[a] - xs[b]) * w, (ys[a] - ys[b]) * h)
    fun aspect(e: Eye): Float {
        val width = d(e.outer, e.inner)
        val height = (d(e.up[0], e.down[0]) + d(e.up[1], e.down[1])) / 2
        return if (width > 0) height / width else 0f
    }
    return (aspect(RIGHT) + aspect(LEFT)) / 2
}

enum class Gesture { NOD, SHAKE }

/** Nod (down and back) = yes, shake (one way and the other) = no, within 1.4 s; a diagonal wobble is neither. */
class HeadGesture(
    private val yawDeg: Float = 8f,
    private val pitch: Float = 0.045f,
    private val windowMs: Long = 1400,
    private val crossRatio: Float = 0.8f,
    private val refractoryMs: Long = 1500,
) {
    private var restYaw = Float.NaN
    private var restPitch = 0f
    private val buf = ArrayDeque<Triple<Long, Float, Float>>()
    private var quietUntil = Long.MIN_VALUE

    fun push(yaw: Float, p: Float, t: Long): Gesture? {
        if (restYaw.isNaN()) { restYaw = yaw; restPitch = p }
        val dy = yaw - restYaw
        val dp = p - restPitch
        if (abs(dy) < yawDeg / 2 && abs(dp) < pitch / 2) { // the rest pose follows slow drift, not the swings
            restYaw += 0.05f * dy
            restPitch += 0.05f * dp
        }
        buf.addLast(Triple(t, dy, dp))
        while (buf.isNotEmpty() && t - buf.first().first > windowMs) buf.removeFirst()
        if (t < quietUntil) return null
        val ys = buf.map { it.second }
        val ps = buf.map { it.third }
        val g = when {
            swungBothWays(ys, yawDeg) && ps.maxOf { abs(it) } < pitch * 2 -> Gesture.SHAKE
            wentAndCameBack(ps, pitch) && ys.maxOf { abs(it) } < yawDeg * crossRatio -> Gesture.NOD
            else -> null
        }
        if (g != null) {
            quietUntil = t + refractoryMs
            buf.clear()
        }
        return g
    }

    private fun wentAndCameBack(xs: List<Float>, limit: Float): Boolean {
        val out = xs.indexOfFirst { abs(it) > limit }
        return out >= 0 && xs.drop(out + 1).any { abs(it) < limit / 3 }
    }

    private fun swungBothWays(xs: List<Float>, limit: Float) = xs.any { it > limit } && xs.any { it < -limit }
}

/** Two deliberate blinks within a second = yes. Natural blinks rarely pair this fast. */
class DoubleBlink(
    private val closedRatio: Float = 0.55f,
    private val minBlinkMs: Long = 40,
    private val maxBlinkMs: Long = 500,
    private val maxGapMs: Long = 1000,
    private val refractoryMs: Long = 1500,
) {
    private var baseline = -1f
    private var closedAt = -1L
    private var lastBlinkEnd = Long.MIN_VALUE / 2
    private var quietUntil = Long.MIN_VALUE

    fun push(openness: Float, t: Long): Boolean {
        if (baseline < 0) baseline = openness
        val closed = openness < baseline * closedRatio
        if (!closed && openness > baseline * 0.7f) baseline += 0.05f * (openness - baseline)
        if (t < quietUntil) {
            closedAt = -1
            return false
        }
        if (closed && closedAt < 0) closedAt = t
        if (closed || closedAt < 0) return false
        val length = t - closedAt // eyes just reopened: was that a blink?
        closedAt = -1
        if (length < minBlinkMs || length > maxBlinkMs) return false
        if (t - length - lastBlinkEnd <= maxGapMs) {
            lastBlinkEnd = Long.MIN_VALUE / 2
            quietUntil = t + refractoryMs
            return true
        }
        lastBlinkEnd = t
        return false
    }
}
