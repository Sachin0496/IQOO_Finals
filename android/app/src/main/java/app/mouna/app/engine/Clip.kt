package app.mouna.app.engine

import app.mouna.app.sense.Frame
import app.mouna.app.sense.Sensor

/** One utterance: the face frames from just before the gate opened until it closed. */
class Clip(val frames: List<Frame>) {
    val size get() = frames.size
    val durationMs get() = if (frames.isEmpty()) 0L else frames.last().tMs - frames.first().tMs

    /** Auto-AVSR crops and camera timestamps, for free talk (frames without one reuse the previous crop). */
    fun avsrCrops(): Pair<List<ByteArray>, LongArray> {
        var last = frames.firstNotNullOfOrNull { it.avsr } ?: ByteArray(Sensor.CROP * Sensor.CROP)
        val crops = frames.map { f -> (f.avsr ?: last).also { last = it } }
        return crops to LongArray(frames.size) { frames[it].tMs }
    }

    /** Concatenated 96 x 96 grey crops and their timestamps, for EncoderInput.build. */
    fun crops(): Pair<ByteArray, DoubleArray> {
        val n = Sensor.CROP * Sensor.CROP
        val out = ByteArray(n * frames.size)
        frames.forEachIndexed { i, f -> f.crop!!.copyInto(out, i * n) }
        val t0 = frames.first().tMs
        return out to DoubleArray(frames.size) { (frames[it].tMs - t0).toDouble() }
    }
}

/**
 * Cuts the frame stream into utterances with the activity gate (the person never has to touch the phone).
 * Keeps a short pre-roll, because the gate opens a few frames after the lips start moving.
 */
class Segmenter(
    private val preRoll: Int = 6,
    private val minFrames: Int = 10,
    private val maxFrames: Int = 96, // ~3.2 s: the encoder window is 1.92 s, phrases are short
    /** Frames the gate may stay shut before the utterance ends: 0 for phrases; free talk bridges pauses between words. */
    private val tail: Int = 0,
    /** Still frames kept at the end of a clip (the rest of the [tail] is cut: long stillness invites repeated words). */
    private val keepTail: Int = Int.MAX_VALUE,
) {
    private val ring = ArrayDeque<Frame>()
    private var current: MutableList<Frame>? = null
    private var quiet = 0

    val recording get() = current != null

    /** Feed every frame; returns a finished clip on the frame the utterance ends. */
    fun push(f: Frame): Clip? {
        if (!f.face || f.features == null || f.crop == null) {
            // A lost face ends an utterance early; a clip with a gap would teach the wrong thing.
            return current?.let { finish() }
        }
        val c = current
        if (c == null) {
            if (f.gateOpen) { current = (ring.toMutableList() + f).toMutableList(); quiet = 0 }
            ring.addLast(f)
            while (ring.size > preRoll) ring.removeFirst()
            return null
        }
        c.add(f)
        quiet = if (f.gateOpen) 0 else quiet + 1
        return if (quiet > tail || c.size >= maxFrames) finish() else null
    }

    fun reset() {
        current = null
        ring.clear()
    }

    private fun finish(): Clip? {
        val c0 = current ?: return null
        val c = if (quiet > keepTail) c0.subList(0, c0.size - (quiet - keepTail)) else c0
        current = null
        quiet = 0
        ring.clear()
        return if (c.size >= minFrames) Clip(c) else null
    }
}
