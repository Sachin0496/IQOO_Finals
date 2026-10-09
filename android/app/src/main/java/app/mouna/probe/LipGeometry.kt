package app.mouna.probe

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Lip geometry from MediaPipe's 478 face landmarks. A line-for-line port of lab/src/core/lips.ts and gate.ts,
 * so the probe measures exactly what the Lab and the harness measure.
 */
object Lips {
    val OUTER = intArrayOf(61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291, 375, 321, 405, 314, 17, 84, 181, 91, 146)
    val INNER = intArrayOf(78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308, 324, 318, 402, 317, 14, 87, 178, 88, 95)
    val ALL = OUTER + INNER
    val FEATURE_DIM = ALL.size * 2
}

data class FaceFrame(
    /** 40 lip points in a face-aligned frame, unit = inter-ocular distance, interleaved x,y. */
    val features: FloatArray,
    val aperture: Float,
    val centerX: Float,
    val centerY: Float,
    /** Eye-line angle, radians. */
    val roll: Float,
    /** Inter-ocular distance, pixels. */
    val iod: Float,
    val yawDeg: Float,
)

/** [xs], [ys] are normalised landmark coordinates (0..1); [w], [h] the image size in pixels. */
fun analyseFace(xs: FloatArray, ys: FloatArray, w: Int, h: Int): FaceFrame {
    fun px(i: Int) = xs[i] * w
    fun py(i: Int) = ys[i] * h

    val iod = hypot(px(263) - px(33), py(263) - py(33))
    val roll = atan2(py(263) - py(33), px(263) - px(33))
    val c = cos(-roll)
    val s = sin(-roll)

    var cx = 0f
    var cy = 0f
    for (i in Lips.OUTER) {
        cx += px(i)
        cy += py(i)
    }
    cx /= Lips.OUTER.size
    cy /= Lips.OUTER.size

    val features = FloatArray(Lips.FEATURE_DIM)
    Lips.ALL.forEachIndexed { k, idx ->
        val dx = px(idx) - cx
        val dy = py(idx) - cy
        features[2 * k] = (dx * c - dy * s) / iod
        features[2 * k + 1] = (dx * s + dy * c) / iod
    }

    val width = hypot(px(291) - px(61), py(291) - py(61))
    val aperture = if (width > 0) hypot(px(14) - px(13), py(14) - py(13)) / width else 0f

    val midX = (px(33) + px(263)) / 2
    val midY = (py(33) + py(263)) / 2
    val along = ((px(1) - midX) * cos(roll) + (py(1) - midY) * sin(roll)) / (iod / 2)
    val yaw = (asin(along.coerceIn(-1f, 1f)) * 180 / PI).toFloat()

    return FaceFrame(features, aperture, cx, cy, roll, iod, yaw)
}

/** Mean per-point displacement between two aligned frames (lip speed, iod units per frame). */
fun lipMotion(a: FloatArray, b: FloatArray): Float {
    var sum = 0f
    var i = 0
    while (i < a.size) {
        sum += hypot(a[i] - b[i], a[i + 1] - b[i + 1])
        i += 2
    }
    return sum / (a.size / 2)
}

/** Hysteresis gate on lip speed and aperture change; same constants as the Lab. */
class ActivityGate(
    private val on: Float = 0.06f,
    private val off: Float = 0.035f,
    private val hangover: Int = 9,
    private val smoothing: Float = 0.35f,
    private val restRate: Float = 0.02f,
) {
    var score = 0f
        private set
    var open = false
        private set
    private var rest = -1f
    private var quiet = 0

    fun push(motion: Float, aperture: Float): Boolean {
        if (rest < 0) rest = aperture
        val raw = motion * 10 + abs(aperture - rest)
        score += smoothing * (raw - score)
        if (!open) {
            rest += restRate * (aperture - rest)
            if (score > on) {
                open = true
                quiet = 0
            }
        } else if (score < off) {
            if (++quiet >= hangover) open = false
        } else {
            quiet = 0
        }
        return open
    }
}
