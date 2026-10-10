package app.mouna.app.sense

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Body and hand keypoints for Indian Sign Language, in the layout AI4Bharat's OpenHands models were trained on:
 * MediaPipe Holistic's "minimal 27" points (models/isl/README.md). Two existing Google models: Pose Landmarker
 * (33 body points, same topology as Holistic) and Hand Landmarker (21 points per hand).
 */
class Signer(context: Context) : AutoCloseable {
    private val pose: PoseLandmarker
    private val hands: HandLandmarker

    init {
        fun buffer(name: String): ByteBuffer {
            val b = context.assets.open(name).use { it.readBytes() }
            return ByteBuffer.allocateDirect(b.size).order(ByteOrder.nativeOrder()).put(b).apply { rewind() }
        }
        val poseModel = buffer("pose_landmarker_lite.task")
        val handModel = buffer("hand_landmarker.task")
        fun <T> firstWorking(make: (Delegate) -> T): T =
            listOf(Delegate.GPU, Delegate.CPU).firstNotNullOfOrNull { d -> runCatching { make(d) }.onFailure { Log.w("Mouna", "signer on $d: ${it.message?.take(100)}") }.getOrNull() }
                ?: error("pose / hand landmarker failed on GPU and CPU")
        pose = firstWorking { d ->
            PoseLandmarker.createFromOptions(context, PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(poseModel).setDelegate(d).build())
                .setRunningMode(RunningMode.VIDEO).setNumPoses(1).build())
        }
        hands = try {
            firstWorking { d ->
                HandLandmarker.createFromOptions(context, HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(handModel).setDelegate(d).build())
                    .setRunningMode(RunningMode.VIDEO).setNumHands(2).build())
            }
        } catch (e: Throwable) {
            pose.close() // the constructor won't return: nobody else will free the pose model
            throw e
        }
    }

    /**
     * One frame -> 27 (x, y) points, or null without a body. Coordinates are rescaled to INCLUDE's 16:9 frame
     * (x by width/16, y by height/9) so the shoulder-based normalisation sees the same proportions as in training.
     * Hands go to the person's left / right slot by which pose wrist they are nearest (as Holistic does); a missing
     * hand is zeros, as in OpenHands' pose files.
     */
    fun keypoints(frame: Bitmap, tMs: Long): Seen? {
        val img = BitmapImageBuilder(frame).build()
        val body = pose.detectForVideo(img, tMs).landmarks().firstOrNull() ?: return null
        val found = hands.detectForVideo(img, tMs).landmarks()
        val sx = frame.width / 16f
        val sy = frame.height / 9f
        val out = FloatArray(27 * 2)
        fun put(slot: Int, p: NormalizedLandmark) {
            out[2 * slot] = p.x() * sx
            out[2 * slot + 1] = p.y() * sy
        }
        POSE.forEachIndexed { i, idx -> put(i, body[idx]) }
        // Holistic's left hand = the person's left = pose landmark 15 (left wrist); right = 16.
        val lw = body[15]
        val rw = body[16]
        fun d2(a: NormalizedLandmark, b: NormalizedLandmark) = (a.x() - b.x()) * (a.x() - b.x()) + (a.y() - b.y()) * (a.y() - b.y())
        var left: List<NormalizedLandmark>? = null
        var right: List<NormalizedLandmark>? = null
        for (h in found.sortedBy { minOf(d2(it[0], lw), d2(it[0], rw)) }) {
            val toLeft = d2(h[0], lw) <= d2(h[0], rw)
            if (toLeft && left == null) left = h else if (!toLeft && right == null) right = h else if (left == null) left = h else if (right == null) right = h
        }
        left?.let { h -> HAND.forEachIndexed { i, idx -> put(POSE.size + i, h[idx]) } }
        right?.let { h -> HAND.forEachIndexed { i, idx -> put(POSE.size + HAND.size + i, h[idx]) } }
        // The pose wrists, for SignSegmenter's "hands raised": the pose sees a wrist even when the hand model misses it.
        val wrists = floatArrayOf(lw.y() * sy, rw.y() * sy, lw.visibility().orElse(0f), rw.visibility().orElse(0f))
        return Seen(out, found.size, wrists)
    }

    /** One frame: the 27 (x, y) points, how many hands the hand model saw, the pose wrists (left y, right y, visibilities). */
    class Seen(val points: FloatArray, val hands: Int, val wrists: FloatArray)

    override fun close() {
        pose.close()
        hands.close()
    }

    companion object {
        private val POSE = intArrayOf(0, 2, 5, 11, 12, 13, 14)
        private val HAND = intArrayOf(0, 4, 5, 8, 9, 12, 13, 16, 17, 20)
    }
}
