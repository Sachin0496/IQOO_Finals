package app.mouna.probe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Telemetry(
    val fps: Float = 0f,
    val landmarkMs: Float = 0f,
    val cropMs: Float = 0f,
    val face: Boolean = false,
    val iod: Float = 0f,
    val yaw: Float = 0f,
    val gateOpen: Boolean = false,
    val delegate: String = "—",
    val imageW: Int = 0,
    val imageH: Int = 0,
    /** Outer lip contour in normalised image coordinates, for the overlay. */
    val outer: List<Pair<Float, Float>> = emptyList(),
    val crop: Bitmap? = null,
)

/**
 * CameraX analyzer: frame -> MediaPipe face landmarks -> aligned lips -> gate -> 96 px grey mouth crop.
 * Spike test S1: sustained 25 fps with a stable crop.
 */
class FaceTracker(context: Context) : ImageAnalysis.Analyzer, AutoCloseable {
    private val landmarker: FaceLandmarker
    private val delegate: String
    private val gate = ActivityGate()
    private var prev: FloatArray? = null
    private var lastTs = 0L
    private val _state = MutableStateFlow(Telemetry())
    val state: StateFlow<Telemetry> = _state.asStateFlow()

    private val crop = Bitmap.createBitmap(CROP, CROP, Bitmap.Config.ARGB_8888)
    private val cropCanvas = Canvas(crop)
    private val grey = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    }

    init {
        var made: FaceLandmarker? = null
        var used = "CPU"
        for (d in listOf(Delegate.GPU, Delegate.CPU)) {
            made = runCatching { FaceLandmarker.createFromOptions(context, options(d)) }.getOrNull()
            if (made != null) {
                used = d.name
                break
            }
        }
        landmarker = checkNotNull(made) { "Face landmarker failed on GPU and CPU" }
        delegate = used
    }

    private fun options(d: Delegate) = FaceLandmarker.FaceLandmarkerOptions.builder()
        .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").setDelegate(d).build())
        .setRunningMode(RunningMode.VIDEO)
        .setNumFaces(1)
        .build()

    override fun analyze(image: ImageProxy) {
        image.use { proxy ->
            val now = SystemClock.uptimeMillis()
            val frame = upright(proxy)
            val t0 = SystemClock.elapsedRealtimeNanos()
            val result = landmarker.detectForVideo(BitmapImageBuilder(frame).build(), now)
            val lmMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6f
            val fps = if (lastTs > 0) 1000f / (now - lastTs) else 0f
            lastTs = now

            val lm = result.faceLandmarks().firstOrNull()
            val s = _state.value
            if (lm == null) {
                prev = null
                _state.value = s.copy(fps = ema(s.fps, fps), landmarkMs = ema(s.landmarkMs, lmMs), face = false, outer = emptyList(), delegate = delegate)
                return
            }
            val xs = FloatArray(lm.size) { lm[it].x() }
            val ys = FloatArray(lm.size) { lm[it].y() }
            val f = analyseFace(xs, ys, frame.width, frame.height)
            val motion = prev?.let { lipMotion(f.features, it) } ?: 0f
            prev = f.features
            val open = gate.push(motion, f.aperture)

            val c0 = SystemClock.elapsedRealtimeNanos()
            cutMouth(frame, f)
            val cropMs = (SystemClock.elapsedRealtimeNanos() - c0) / 1e6f

            _state.value = Telemetry(
                fps = ema(s.fps, fps),
                landmarkMs = ema(s.landmarkMs, lmMs),
                cropMs = ema(s.cropMs, cropMs),
                face = true,
                iod = f.iod,
                yaw = f.yawDeg,
                gateOpen = open,
                delegate = delegate,
                imageW = frame.width,
                imageH = frame.height,
                outer = Lips.OUTER.map { xs[it] to ys[it] },
                crop = crop.copy(Bitmap.Config.ARGB_8888, false),
            )
        }
    }

    /** Same affine as the Lab's MouthCropper: centre on the mouth, undo roll, side = 1.1 x inter-ocular distance. */
    private fun cutMouth(src: Bitmap, f: FaceFrame) {
        val scale = CROP / (CROP_IOD * f.iod)
        val m = Matrix().apply {
            postTranslate(-f.centerX, -f.centerY)
            postRotate((-f.roll * 180 / Math.PI).toFloat())
            postScale(scale, scale)
            postTranslate(CROP / 2f, CROP / 2f)
        }
        cropCanvas.drawColor(android.graphics.Color.BLACK)
        cropCanvas.drawBitmap(src, m, grey)
    }

    private fun upright(proxy: ImageProxy): Bitmap {
        val bmp = proxy.toBitmap()
        val rot = proxy.imageInfo.rotationDegrees
        if (rot == 0) return bmp
        val m = Matrix().apply { postRotate(rot.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    override fun close() = landmarker.close()

    companion object {
        const val CROP = 96
        const val CROP_IOD = 1.1f
        private fun ema(prev: Float, next: Float, k: Float = 0.1f) = if (prev == 0f) next else prev + k * (next - prev)
    }
}
