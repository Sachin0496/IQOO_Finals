package app.mouna.app.engine

import app.mouna.app.sense.Frame
import app.mouna.app.sense.Sensor

/** One utterance: the face frames from just before the gate opened until it closed. */
class Clip(val frames: List<Frame>) {
    val size get() = frames.size
    val durationMs get() = if (frames.isEmpty()) 0L else frames.last().tMs - frames.first().tMs

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
) {
    private val ring = ArrayDeque<Frame>()
    private var current: MutableList<Frame>? = null

    val recording get() = current != null

    /** Feed every frame; returns a finished clip on the frame the utterance ends. */
    fun push(f: Frame): Clip? {
        if (!f.face || f.features == null || f.crop == null) {
            // A lost face ends an utterance early; a clip with a gap would teach the wrong thing.
            return current?.let { finish() }
        }
        val c = current
        if (c == null) {
            if (f.gateOpen) current = (ring.toMutableList() + f).toMutableList()
            ring.addLast(f)
            while (ring.size > preRoll) ring.removeFirst()
            return null
        }
        c.add(f)
        return if (!f.gateOpen || c.size >= maxFrames) finish() else null
    }

    fun reset() {
        current = null
        ring.clear()
    }

    private fun finish(): Clip? {
        val c = current ?: return null
        current = null
        ring.clear()
        return if (c.size >= minFrames) Clip(c) else null
    }
}
