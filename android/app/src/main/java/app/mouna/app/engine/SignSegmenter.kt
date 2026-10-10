package app.mouna.app.engine

import kotlin.math.sqrt

/**
 * Cuts the camera stream into single signs for [Isl]. A sign starts when a wrist rises above chest level and ends when
 * both drop back. "Hands visible" was the old trigger, but the hand model also sees hands resting at the sides or on a
 * table (94–97% of frames on INCLUDE's held-out clips), so signs never ended (models/isl/eval/README.md).
 *
 * Pure Kotlin, so the JVM test can check it against the Python reference (models/isl/eval/stream.py) frame by frame.
 */
class SignSegmenter {
    /**
     * One frame: [points] the 27 x 2 keypoints from Signer (null without a body), [wrists] the pose wrists as
     * (left y, right y, left visibility, right visibility), y in the same space as [points].
     */
    class Step(val points: FloatArray?, val wrists: FloatArray?)

    private val recent = ArrayDeque<Step>() // the frames before a sign starts: up to [PAD] of them become its lead-in
    private var rec: MutableList<Step>? = null
    private var up = 0
    private var down = 0
    private var raisedFrames = 0

    /** A sign is being recorded. */
    val recording get() = rec != null

    /** Feeds one frame. Returns a finished sign's keypoints (only frames with a body), or null. */
    fun push(s: Step): List<FloatArray>? {
        val r = raised(s)
        val cur = rec
        if (cur == null) {
            up = if (r) up + 1 else 0
            recent.addLast(s)
            if (recent.size > PAD + START) recent.removeFirst()
            if (up >= START) {
                // the raised frames plus up to PAD frames before them
                rec = recent.toMutableList()
                down = 0
                raisedFrames = up
                recent.clear()
            }
            return null
        }
        cur.add(s)
        down = if (r) 0 else down + 1
        if (r) raisedFrames++
        if (down < STOP && cur.size < MAX) return null
        rec = null
        up = 0
        val keep = cur.size - maxOf(0, down - PAD) // keep up to PAD frames of the hands coming down
        val frames = cur.subList(0, keep).mapNotNull { it.points }
        return frames.takeIf { it.size >= MIN && raisedFrames >= MIN_RAISED } // a twitch of the hand is not a sign
    }

    fun reset() {
        recent.clear(); rec = null; up = 0; down = 0
    }

    companion object {
        /** A wrist this many shoulder widths below the shoulder line, or higher, counts as raised. Resting hands sit near 2.8. */
        const val RAISE = 1.6f
        const val START = 3 // raised frames to start a sign
        const val STOP = 8 // lowered frames to end it
        const val PAD = 6 // lead-in and lead-out frames kept around the sign
        const val MAX = 150
        const val MIN = 12
        const val MIN_RAISED = 6
        private const val VIS = 0.5f

        fun raised(s: Step): Boolean {
            val p = s.points ?: return false
            val w = s.wrists ?: return false
            val sy = (p[7] + p[9]) / 2 // shoulders: points 3 and 4, y
            val dx = p[6] - p[8]
            val dy = p[7] - p[9]
            val sw = sqrt(dx * dx + dy * dy)
            if (sw <= 0f) return false
            return (w[2] > VIS && (w[0] - sy) / sw < RAISE) || (w[3] > VIS && (w[1] - sy) / sw < RAISE)
        }
    }
}
